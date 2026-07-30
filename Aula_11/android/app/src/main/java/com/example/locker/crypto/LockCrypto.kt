package com.example.locker.crypto

import com.example.locker.protocol.LockProtocol
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Desafio–resposta com HMAC-SHA256.
 *
 * A mesma função tem de existir, byte a byte, na Raspberry e no iOS.
 * Em Python:
 *
 * ```python
 * msg = context.encode() + b"\x00" + device_id.encode() + b"\x00" + nonce
 * mac = hmac.new(secret, msg, hashlib.sha256).digest()
 * ```
 *
 * Em Swift: `LockCrypto.response` em `ios/SmartLock/Crypto/LockCrypto.swift`.
 */
object LockCrypto {
    /** Tamanho esperado do segredo emitido pela Raspberry. */
    const val SECRET_LENGTH = LockProtocol.SECRET_LENGTH

    private const val HMAC_ALGORITHM = "HmacSHA256"

    private val random = SecureRandom()

    fun response(
        secret: ByteArray,
        nonce: ByteArray,
        deviceId: String,
        context: String = LockProtocol.UNLOCK_CONTEXT,
    ): ByteArray {
        // Os separadores 0x00 evitam que deviceId e nonce de tamanhos
        // diferentes produzam a mesma mensagem concatenada.
        val message = context.toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x00) +
            deviceId.toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x00) +
            nonce

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret, HMAC_ALGORITHM))
        return mac.doFinal(message)
    }

    /** Comparação em tempo constante. */
    fun constantTimeEquals(lhs: ByteArray, rhs: ByteArray): Boolean =
        MessageDigest.isEqual(lhs, rhs)

    fun randomBytes(count: Int): ByteArray =
        ByteArray(count).also(random::nextBytes)

    /**
     * Identificador do celular perante uma fechadura. Um por fechadura, para não
     * correlacionar o mesmo aparelho entre instalações diferentes.
     */
    fun newDeviceId(): String = UUID.randomUUID().toString()
}
