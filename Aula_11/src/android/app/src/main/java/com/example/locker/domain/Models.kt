package com.example.locker.domain

/**
 * Fechadura vista no anúncio BLE, antes de qualquer conexão.
 *
 * Espelha `ios/SmartLock/Domain/Models.swift`.
 */
data class DiscoveredLock(
    /**
     * Endereço BLE do periférico. Estável enquanto a Raspberry não randomizar o
     * endereço, mas **não** é o `lockId` — esse só chega depois de conectar.
     */
    val address: String,
    val advertisedName: String,
    val rssi: Int,
) {
    val signalDescription: String
        get() = when {
            rssi >= -55 -> "Sinal forte"
            rssi >= -75 -> "Sinal médio"
            else -> "Sinal fraco"
        }
}

/** Identidade da fechadura, lida após conectar. */
data class LockIdentity(
    val lockId: String,
    val lockName: String,
    val firmware: String?,
)

/** Credencial persistida no Keystore. */
data class LockCredential(
    val lockId: String,
    val lockName: String,
    val deviceId: String,
    val secret: ByteArray,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LockCredential) return false
        return lockId == other.lockId &&
            lockName == other.lockName &&
            deviceId == other.deviceId &&
            createdAt == other.createdAt &&
            lastUsedAt == other.lastUsedAt &&
            secret.contentEquals(other.secret)
    }

    override fun hashCode(): Int {
        var result = lockId.hashCode()
        result = 31 * result + lockName.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (lastUsedAt?.hashCode() ?: 0)
        return result
    }

    /** O segredo nunca aparece em log — ver `protocol/security.md`. */
    override fun toString(): String =
        "LockCredential(lockId=$lockId, lockName=$lockName, deviceId=$deviceId, " +
            "createdAt=$createdAt, lastUsedAt=$lastUsedAt, secret=<oculto>)"
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val identity: LockIdentity) : ConnectionState
    data class Failed(val message: String) : ConnectionState

    val identity: LockIdentity?
        get() = (this as? Connected)?.identity

    val isConnected: Boolean
        get() = this is Connected
}

sealed interface EnrollmentState {
    data object Idle : EnrollmentState
    data object Requesting : EnrollmentState

    /** Aguardando alguém apertar o botão físico na Raspberry. */
    data class AwaitingApproval(val deadline: Long) : EnrollmentState
    data object Approved : EnrollmentState
    data object Denied : EnrollmentState
    data object TimedOut : EnrollmentState
    data class Failed(val message: String) : EnrollmentState

    val isBusy: Boolean
        get() = this is Requesting || this is AwaitingApproval
}

sealed interface UnlockState {
    data object Idle : UnlockState
    data object Authenticating : UnlockState
    data object Unlocking : UnlockState
    data class Unlocked(val at: Long) : UnlockState
    data class Failed(val message: String) : UnlockState

    val isBusy: Boolean
        get() = this is Authenticating || this is Unlocking
}
