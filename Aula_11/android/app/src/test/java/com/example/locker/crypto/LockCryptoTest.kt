package com.example.locker.crypto

import com.example.locker.protocol.ProtocolVectors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O desbloqueio inteiro depende deste HMAC bater byte a byte com o da
 * Raspberry (`raspberry/smartlock/authentication.py`) e com o do iOS
 * (`ios/SmartLock/Crypto/LockCrypto.swift`).
 */
class LockCryptoTest {

    @Test
    fun `response reproduz o vetor gerado pela Raspberry`() {
        val mac = LockCrypto.response(
            secret = ProtocolVectors.secret,
            nonce = ProtocolVectors.nonce,
            deviceId = ProtocolVectors.DEVICE_ID,
        )

        assertEquals(ProtocolVectors.EXPECTED_MAC_BASE64, ProtocolVectors.base64(mac))
        assertEquals(ProtocolVectors.EXPECTED_MAC_HEX, mac.joinToString("") { "%02x".format(it) })
        assertEquals(32, mac.size)
    }

    @Test
    fun `contexto diferente produz prova diferente`() {
        // É o que impede reaproveitar um MAC de desbloqueio em outra operação.
        val unlock = LockCrypto.response(
            ProtocolVectors.secret,
            ProtocolVectors.nonce,
            ProtocolVectors.DEVICE_ID,
        )
        val other = LockCrypto.response(
            ProtocolVectors.secret,
            ProtocolVectors.nonce,
            ProtocolVectors.DEVICE_ID,
            context = "enroll",
        )

        assertNotEquals(ProtocolVectors.base64(unlock), ProtocolVectors.base64(other))
    }

    @Test
    fun `separadores impedem colisao entre deviceId e nonce`() {
        // Sem o 0x00 entre os campos, "ab" + nonce e "a" + ("b" || nonce)
        // gerariam a mesma mensagem.
        val first = LockCrypto.response(ProtocolVectors.secret, byteArrayOf(1, 2), "ab")
        val second = LockCrypto.response(ProtocolVectors.secret, byteArrayOf(0x62, 1, 2), "a")

        assertNotEquals(ProtocolVectors.base64(first), ProtocolVectors.base64(second))
    }

    @Test
    fun `nonce diferente produz prova diferente`() {
        val first = LockCrypto.response(
            ProtocolVectors.secret,
            ProtocolVectors.nonce,
            ProtocolVectors.DEVICE_ID,
        )
        val second = LockCrypto.response(
            ProtocolVectors.secret,
            ByteArray(16) { 0 },
            ProtocolVectors.DEVICE_ID,
        )

        assertNotEquals(ProtocolVectors.base64(first), ProtocolVectors.base64(second))
    }

    @Test
    fun `comparacao em tempo constante`() {
        val mac = LockCrypto.response(
            ProtocolVectors.secret,
            ProtocolVectors.nonce,
            ProtocolVectors.DEVICE_ID,
        )

        assertTrue(LockCrypto.constantTimeEquals(mac, mac.copyOf()))
        assertFalse(LockCrypto.constantTimeEquals(mac, mac.copyOf(31)))
        assertFalse(LockCrypto.constantTimeEquals(mac, ByteArray(32)))
    }

    @Test
    fun `randomBytes devolve o tamanho pedido e nao se repete`() {
        val first = LockCrypto.randomBytes(32)
        val second = LockCrypto.randomBytes(32)

        assertEquals(32, first.size)
        assertEquals(32, second.size)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `deviceId e um UUID novo a cada chamada`() {
        val first = LockCrypto.newDeviceId()
        val second = LockCrypto.newDeviceId()

        assertNotEquals(first, second)
        assertEquals(36, first.length)
    }

    @Test
    fun `segredo do tamanho errado ainda produz MAC de 32 bytes`() {
        // O HMAC aceita chave de qualquer tamanho; quem recusa segredo fora do
        // padrão é o `LockManager`, no cadastro.
        val mac = LockCrypto.response(ByteArray(8), ProtocolVectors.nonce, "x")
        assertArrayEquals(intArrayOf(32), intArrayOf(mac.size))
    }
}
