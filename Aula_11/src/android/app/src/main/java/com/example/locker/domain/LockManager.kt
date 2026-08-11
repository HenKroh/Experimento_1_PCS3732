package com.example.locker.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.locker.ble.LockTransport
import com.example.locker.crypto.LockCrypto
import com.example.locker.protocol.AccessRequestMessage
import com.example.locker.protocol.ApprovalState
import com.example.locker.protocol.AuthResponseMessage
import com.example.locker.protocol.OperationResultMessage
import com.example.locker.protocol.OperationStatus
import com.example.locker.protocol.UnlockCommandMessage
import com.example.locker.protocol.UnsupportedProtocolException
import com.example.locker.storage.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Máquina de estados do app: descoberta, cadastro e desbloqueio.
 *
 * Espelha `ios/SmartLock/Domain/LockManager.swift`. Qualquer divergência aqui
 * aparece como erro de protocolo em campo.
 */
class LockManager(
    private val transport: LockTransport,
    private val store: CredentialStore,
) : ViewModel() {

    val discovered: StateFlow<List<DiscoveredLock>> = transport.discovered
    val isScanning: StateFlow<Boolean> = transport.isScanning

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _enrollment = MutableStateFlow<EnrollmentState>(EnrollmentState.Idle)
    val enrollment: StateFlow<EnrollmentState> = _enrollment.asStateFlow()

    private val _unlock = MutableStateFlow<UnlockState>(UnlockState.Idle)
    val unlock: StateFlow<UnlockState> = _unlock.asStateFlow()

    private val _credentials = MutableStateFlow<List<LockCredential>>(emptyList())
    val credentials: StateFlow<List<LockCredential>> = _credentials.asStateFlow()

    private val _alertMessage = MutableStateFlow<String?>(null)
    val alertMessage: StateFlow<String?> = _alertMessage.asStateFlow()

    /** Só uma operação por vez: duas escritas concorrentes embaralhariam desafio e resposta. */
    private var activeJob: Job? = null

    init {
        viewModelScope.launch {
            transport.unexpectedDisconnects.collect { error ->
                val message = error.userMessage()
                _connection.value = ConnectionState.Failed(message)
                _alertMessage.value = message
                if (_enrollment.value is EnrollmentState.AwaitingApproval) {
                    _enrollment.value = EnrollmentState.Failed(message)
                }
                if (_unlock.value.isBusy) {
                    _unlock.value = UnlockState.Failed(message)
                }
            }
        }
        reloadCredentials()
    }

    // ----------------------------------------------------------------- //
    // Credenciais
    // ----------------------------------------------------------------- //

    fun reloadCredentials() {
        _credentials.value = try {
            store.all()
        } catch (error: Exception) {
            _alertMessage.value = error.userMessage()
            emptyList()
        }
    }

    fun credential(lockId: String): LockCredential? =
        _credentials.value.firstOrNull { it.lockId == lockId }

    val currentCredential: LockCredential?
        get() = _connection.value.identity?.let { credential(it.lockId) }

    fun removeCredential(lockId: String) {
        try {
            store.delete(lockId)
            reloadCredentials()
            _enrollment.value = EnrollmentState.Idle
            _unlock.value = UnlockState.Idle
        } catch (error: Exception) {
            _alertMessage.value = error.userMessage()
        }
    }

    fun consumeAlert() {
        _alertMessage.value = null
    }

    // ----------------------------------------------------------------- //
    // Descoberta
    // ----------------------------------------------------------------- //

    fun startScan() {
        try {
            transport.startScan()
        } catch (error: Exception) {
            _alertMessage.value = error.userMessage()
        }
    }

    fun stopScan() = transport.stopScan()

    // ----------------------------------------------------------------- //
    // Conexão
    // ----------------------------------------------------------------- //

    fun select(lock: DiscoveredLock) = launchExclusive {
        _connection.value = ConnectionState.Connecting
        _enrollment.value = EnrollmentState.Idle
        _unlock.value = UnlockState.Idle
        try {
            _connection.value = ConnectionState.Connected(transport.connect(lock))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _connection.value = ConnectionState.Failed(error.userMessage())
        }
    }

    fun disconnect() {
        activeJob?.cancel()
        activeJob = null
        transport.disconnect()
        _connection.value = ConnectionState.Disconnected
        _enrollment.value = EnrollmentState.Idle
        _unlock.value = UnlockState.Idle
    }

    // ----------------------------------------------------------------- //
    // Cadastro
    // ----------------------------------------------------------------- //

    fun requestAccess(deviceName: String) {
        val identity = _connection.value.identity
        if (identity == null) {
            _alertMessage.value = LockError.NotConnected.message
            return
        }

        launchExclusive {
            _enrollment.value = EnrollmentState.Requesting

            // Um deviceId por fechadura: um novo cadastro na mesma fechadura
            // reaproveita o identificador, então a Raspberry substitui o
            // registro antigo em vez de acumular linhas órfãs.
            val deviceId = credential(identity.lockId)?.deviceId ?: LockCrypto.newDeviceId()
            val request = AccessRequestMessage(deviceId = deviceId, deviceName = deviceName)

            try {
                _enrollment.value = EnrollmentState.AwaitingApproval(
                    deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS
                )
                val status = transport.requestAccess(request, APPROVAL_TIMEOUT_MS)

                when (status.state) {
                    ApprovalState.APPROVED -> {
                        val secret = status.secret
                        if (secret == null || secret.size != LockCrypto.SECRET_LENGTH) {
                            _enrollment.value = EnrollmentState.Failed(
                                "A fechadura aprovou o acesso mas não enviou um segredo válido."
                            )
                            return@launchExclusive
                        }
                        store.save(
                            LockCredential(
                                lockId = identity.lockId,
                                lockName = status.lockName ?: identity.lockName,
                                deviceId = deviceId,
                                secret = secret,
                                createdAt = System.currentTimeMillis(),
                            )
                        )
                        reloadCredentials()
                        _enrollment.value = EnrollmentState.Approved
                    }

                    ApprovalState.DENIED -> _enrollment.value = EnrollmentState.Denied
                    ApprovalState.TIMEOUT -> _enrollment.value = EnrollmentState.TimedOut
                    ApprovalState.PENDING -> _enrollment.value =
                        EnrollmentState.Failed("Resposta inesperada da fechadura.")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _enrollment.value = when (error) {
                    is LockError.Timeout -> EnrollmentState.TimedOut
                    else -> EnrollmentState.Failed(error.protocolAwareMessage())
                }
            }
        }
    }

    // ----------------------------------------------------------------- //
    // Desbloqueio
    // ----------------------------------------------------------------- //

    fun unlockDoor() {
        val identity = _connection.value.identity
        if (identity == null) {
            _alertMessage.value = LockError.NotConnected.message
            return
        }
        val credential = credential(identity.lockId)
        if (credential == null) {
            _unlock.value = UnlockState.Failed(LockError.NoCredential.message.orEmpty())
            return
        }

        launchExclusive {
            _unlock.value = UnlockState.Authenticating
            try {
                val challenge = transport.readChallenge()
                val mac = LockCrypto.response(
                    secret = credential.secret,
                    nonce = challenge.nonce,
                    deviceId = credential.deviceId,
                )

                transport.sendAuthResponse(
                    AuthResponseMessage(deviceId = credential.deviceId, mac = mac)
                )
                val auth = transport.awaitOperationResult(OPERATION_TIMEOUT_MS)
                if (auth.status != OperationStatus.OK) {
                    _unlock.value = UnlockState.Failed(describe(auth))
                    return@launchExclusive
                }

                _unlock.value = UnlockState.Unlocking
                transport.sendUnlockCommand(UnlockCommandMessage(deviceId = credential.deviceId))
                val result = transport.awaitOperationResult(OPERATION_TIMEOUT_MS)
                if (result.status != OperationStatus.OK) {
                    _unlock.value = UnlockState.Failed(describe(result))
                    return@launchExclusive
                }

                _unlock.value = UnlockState.Unlocked(at = System.currentTimeMillis())
                runCatching {
                    store.save(credential.copy(lastUsedAt = System.currentTimeMillis()))
                }
                reloadCredentials()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _unlock.value = UnlockState.Failed(error.protocolAwareMessage())
            }
        }
    }

    // ----------------------------------------------------------------- //
    // Auxiliares
    // ----------------------------------------------------------------- //

    private fun describe(result: OperationResultMessage): String = when (result.status) {
        OperationStatus.OK -> "Operação concluída."
        OperationStatus.RATE_LIMITED -> LockError.RateLimited.message.orEmpty()
        OperationStatus.DENIED -> result.reason ?: "Operação negada pela fechadura."
        OperationStatus.ERROR -> result.reason ?: "A fechadura relatou um erro interno."
    }

    /** Traduz a divergência de versão para o mesmo texto que o iOS mostra. */
    private fun Throwable.protocolAwareMessage(): String = when (this) {
        is UnsupportedProtocolException -> LockError.UnsupportedProtocol(version).message.orEmpty()
        else -> userMessage()
    }

    private fun launchExclusive(body: suspend () -> Unit) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch { body() }
    }

    override fun onCleared() {
        super.onCleared()
        transport.stopScan()
        transport.disconnect()
    }

    companion object {
        /** Quanto tempo o app espera alguém apertar o botão físico na Raspberry. */
        const val APPROVAL_TIMEOUT_MS = 60_000L

        /** Margem para a Raspberry acionar o relé e responder. */
        const val OPERATION_TIMEOUT_MS = 8_000L

        fun factory(
            transport: LockTransport,
            store: CredentialStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { LockManager(transport, store) }
        }
    }
}
