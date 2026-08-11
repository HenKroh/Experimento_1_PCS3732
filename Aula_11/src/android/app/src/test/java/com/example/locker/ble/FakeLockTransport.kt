package com.example.locker.ble

import com.example.locker.domain.DiscoveredLock
import com.example.locker.domain.LockError
import com.example.locker.domain.LockIdentity
import com.example.locker.protocol.AccessRequestMessage
import com.example.locker.protocol.ApprovalState
import com.example.locker.protocol.ApprovalStatusMessage
import com.example.locker.protocol.AuthResponseMessage
import com.example.locker.protocol.ChallengeMessage
import com.example.locker.protocol.LockProtocol
import com.example.locker.protocol.OperationResultMessage
import com.example.locker.protocol.OperationStatus
import com.example.locker.protocol.UnlockCommandMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Raspberry de mentira, no lugar do rádio BLE.
 *
 * Faz o papel do `MockLockTransport` do app iOS: permite exercitar cadastro e
 * desbloqueio sem hardware.
 */
class FakeLockTransport(
    private val identity: LockIdentity = LockIdentity("lock-01", "Fechadura da Sala", "1.0"),
) : LockTransport {

    private val _discovered = MutableStateFlow<List<DiscoveredLock>>(emptyList())
    override val discovered: StateFlow<List<DiscoveredLock>> = _discovered

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning

    private val _unexpectedDisconnects = MutableSharedFlow<LockError>(extraBufferCapacity = 8)
    override val unexpectedDisconnects: SharedFlow<LockError> = _unexpectedDisconnects

    /** Respostas programadas pelo teste. */
    var approvalResponse: (AccessRequestMessage) -> ApprovalStatusMessage = { request ->
        ApprovalStatusMessage(
            v = LockProtocol.VERSION,
            state = ApprovalState.APPROVED,
            deviceId = request.deviceId,
            secret = ByteArray(LockProtocol.SECRET_LENGTH) { it.toByte() },
            lockName = identity.lockName,
        )
    }
    var challenge: ChallengeMessage =
        ChallengeMessage(LockProtocol.VERSION, ByteArray(16) { (0x10 + it).toByte() }, 5.0)
    var operationResults: MutableList<OperationResultMessage> = mutableListOf()
    var connectError: Exception? = null

    /** O que o teste inspeciona depois. */
    var lastRequest: AccessRequestMessage? = null
        private set
    var lastAuthResponse: AuthResponseMessage? = null
        private set
    var lastUnlockCommand: UnlockCommandMessage? = null
        private set
    var challengeReads = 0
        private set

    fun publish(locks: List<DiscoveredLock>) {
        _discovered.value = locks
    }

    suspend fun emitUnexpectedDisconnect(error: LockError) {
        _unexpectedDisconnects.emit(error)
    }

    override fun startScan() {
        _isScanning.value = true
    }

    override fun stopScan() {
        _isScanning.value = false
    }

    override suspend fun connect(lock: DiscoveredLock): LockIdentity {
        connectError?.let { throw it }
        return identity
    }

    override fun disconnect() = Unit

    override suspend fun requestAccess(
        request: AccessRequestMessage,
        timeoutMillis: Long,
    ): ApprovalStatusMessage {
        lastRequest = request
        return approvalResponse(request)
    }

    override suspend fun readChallenge(): ChallengeMessage {
        challengeReads++
        return challenge
    }

    override suspend fun sendAuthResponse(response: AuthResponseMessage) {
        lastAuthResponse = response
    }

    override suspend fun sendUnlockCommand(command: UnlockCommandMessage) {
        lastUnlockCommand = command
    }

    override suspend fun awaitOperationResult(timeoutMillis: Long): OperationResultMessage =
        operationResults.removeFirstOrNull()
            ?: OperationResultMessage(LockProtocol.VERSION, "auth", OperationStatus.OK, null)
}
