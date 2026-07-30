package com.example.locker.ble

import com.example.locker.domain.DiscoveredLock
import com.example.locker.domain.LockError
import com.example.locker.domain.LockIdentity
import com.example.locker.protocol.AccessRequestMessage
import com.example.locker.protocol.ApprovalStatusMessage
import com.example.locker.protocol.AuthResponseMessage
import com.example.locker.protocol.ChallengeMessage
import com.example.locker.protocol.OperationResultMessage
import com.example.locker.protocol.UnlockCommandMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstrai o canal até a fechadura para que a lógica de domínio não dependa das
 * APIs de BLE do Android.
 *
 * Espelha `ios/SmartLock/Bluetooth/LockTransport.swift`. Duas implementações:
 * [BleLockTransport] (aparelho real) e `FakeLockTransport` (testes).
 */
interface LockTransport {
    /** Fechaduras anunciando no momento, ordenadas por sinal. */
    val discovered: StateFlow<List<DiscoveredLock>>

    val isScanning: StateFlow<Boolean>

    /** Desconexões não solicitadas: fechadura fora de alcance, Bluetooth desligado. */
    val unexpectedDisconnects: SharedFlow<LockError>

    /** Lança [LockError.BluetoothUnavailable] se o rádio não estiver utilizável. */
    fun startScan()

    fun stopScan()

    suspend fun connect(lock: DiscoveredLock): LockIdentity

    fun disconnect()

    /**
     * Envia o pedido de cadastro e devolve o desfecho do botão físico.
     * Lança [LockError.Timeout] se ninguém decidir dentro de [timeoutMillis].
     */
    suspend fun requestAccess(
        request: AccessRequestMessage,
        timeoutMillis: Long,
    ): ApprovalStatusMessage

    /** Cada leitura devolve um nonce novo. */
    suspend fun readChallenge(): ChallengeMessage

    suspend fun sendAuthResponse(response: AuthResponseMessage)

    suspend fun sendUnlockCommand(command: UnlockCommandMessage)

    /** Aguarda a notificação de `Operation Result`. */
    suspend fun awaitOperationResult(timeoutMillis: Long): OperationResultMessage
}
