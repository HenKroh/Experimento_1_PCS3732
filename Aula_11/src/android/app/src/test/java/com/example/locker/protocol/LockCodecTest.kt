package com.example.locker.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O JSON precisa casar com `raspberry/smartlock/protocol.py` nos dois sentidos:
 * o que a Raspberry emite tem de ser lido aqui, e o que sai daqui tem de passar
 * pelos `parse_*` de lá.
 */
class LockCodecTest {

    // ------------------------------------------------------------------ //
    // Mensagens vindas da Raspberry
    // ------------------------------------------------------------------ //

    @Test
    fun `decodifica Device Information`() {
        val info = LockCodec.decode<DeviceInfoMessage>(
            ProtocolVectors.bytes(ProtocolVectors.DEVICE_INFO_JSON)
        )

        assertEquals(1, info.v)
        assertEquals("lock-01", info.lockId)
        assertEquals("Fechadura da Sala", info.lockName)
        assertEquals("1.0", info.firmware)
    }

    @Test
    fun `decodifica Approval Status aprovado com o segredo em Base64`() {
        val status = LockCodec.decode<ApprovalStatusMessage>(
            ProtocolVectors.bytes(ProtocolVectors.APPROVAL_APPROVED_JSON)
        )

        assertEquals(ApprovalState.APPROVED, status.state)
        assertEquals(ProtocolVectors.DEVICE_ID, status.deviceId)
        assertEquals("Fechadura da Sala", status.lockName)
        assertArrayEquals(ProtocolVectors.secret, status.secret)
        assertEquals(LockProtocol.SECRET_LENGTH, status.secret?.size)
    }

    @Test
    fun `decodifica Approval Status negado sem segredo`() {
        val status = LockCodec.decode<ApprovalStatusMessage>(
            ProtocolVectors.bytes(ProtocolVectors.APPROVAL_DENIED_JSON)
        )

        assertEquals(ApprovalState.DENIED, status.state)
        assertNull(status.secret)
        assertNull(status.lockName)
    }

    @Test
    fun `decodifica o desafio`() {
        val challenge = LockCodec.decode<ChallengeMessage>(
            ProtocolVectors.bytes(ProtocolVectors.CHALLENGE_JSON)
        )

        assertArrayEquals(ProtocolVectors.nonce, challenge.nonce)
        assertEquals(16, challenge.nonce.size)
        assertEquals(5.0, challenge.ttl, 0.0)
    }

    @Test
    fun `decodifica Operation Result com status rate_limited`() {
        val result = LockCodec.decode<OperationResultMessage>(
            ProtocolVectors.bytes(ProtocolVectors.OPERATION_RESULT_JSON)
        )

        assertEquals("auth", result.op)
        assertEquals(OperationStatus.RATE_LIMITED, result.status)
        assertEquals("Bloqueado temporariamente.", result.reason)
    }

    @Test
    fun `versao diferente e recusada`() {
        val payload = ProtocolVectors.bytes(
            """{"v":2,"lockId":"lock-01","lockName":"X","firmware":null}"""
        )

        val error = assertThrows(UnsupportedProtocolException::class.java) {
            LockCodec.decode<DeviceInfoMessage>(payload)
        }
        assertEquals(2, error.version)
    }

    @Test
    fun `campo desconhecido nao quebra a leitura`() {
        // A Raspberry pode ganhar campos novos sem obrigar todo mundo a atualizar.
        val info = LockCodec.decode<DeviceInfoMessage>(
            ProtocolVectors.bytes(
                """{"v":1,"lockId":"lock-01","lockName":"X","firmware":"1.0","extra":42}"""
            )
        )

        assertEquals("lock-01", info.lockId)
    }

    @Test
    fun `payload invalido vira MalformedMessageException`() {
        assertThrows(MalformedMessageException::class.java) {
            LockCodec.decode<DeviceInfoMessage>(ProtocolVectors.bytes("nao e json"))
        }
        assertThrows(MalformedMessageException::class.java) {
            LockCodec.decode<ChallengeMessage>(ProtocolVectors.bytes("""{"v":1,"ttl":5.0}"""))
        }
    }

    @Test
    fun `Approval Status truncado nao passa por valido`() {
        // É o sintoma de MTU pequeno demais descrito em protocol/messages.md.
        val truncated = ProtocolVectors.APPROVAL_APPROVED_JSON.take(20)

        assertThrows(MalformedMessageException::class.java) {
            LockCodec.decode<ApprovalStatusMessage>(ProtocolVectors.bytes(truncated))
        }
    }

    // ------------------------------------------------------------------ //
    // Mensagens enviadas pelo celular
    // ------------------------------------------------------------------ //

    @Test
    fun `Access Request carrega a versao do protocolo`() {
        val json = LockCodec.encode(
            AccessRequestMessage(deviceId = ProtocolVectors.DEVICE_ID, deviceName = "Pixel 8")
        ).toString(Charsets.UTF_8)

        // Sem `encodeDefaults` o `v` sumiria e a Raspberry recusaria a mensagem.
        assertTrue(json, json.contains(""""v":1"""))
        assertTrue(json, json.contains(""""deviceId":"${ProtocolVectors.DEVICE_ID}""""))
        assertTrue(json, json.contains(""""deviceName":"Pixel 8""""))
        // JSON compacto, como o `json.dumps(separators=(",", ":"))` da Raspberry.
        assertTrue(json, !json.contains(", "))
    }

    @Test
    fun `Authentication Response manda o MAC em Base64`() {
        val mac = java.util.Base64.getDecoder().decode(ProtocolVectors.EXPECTED_MAC_BASE64)

        val json = LockCodec.encode(
            AuthResponseMessage(deviceId = ProtocolVectors.DEVICE_ID, mac = mac)
        ).toString(Charsets.UTF_8)

        assertTrue(json, json.contains(""""mac":"${ProtocolVectors.EXPECTED_MAC_BASE64}""""))
        assertTrue(json, json.contains(""""v":1"""))
    }

    @Test
    fun `Unlock Command leva apenas o deviceId`() {
        val json = LockCodec.encode(UnlockCommandMessage(deviceId = ProtocolVectors.DEVICE_ID))
            .toString(Charsets.UTF_8)

        assertTrue(json, json.contains(""""deviceId":"${ProtocolVectors.DEVICE_ID}""""))
        assertTrue(json, json.contains(""""v":1"""))
        assertTrue(json, !json.contains("secret"))
    }

    @Test
    fun `mensagens do celular cabem no MTU negociado`() {
        // 185 bytes de MTU, menos 3 de cabeçalho ATT.
        val limit = 185 - 3
        val request = LockCodec.encode(
            AccessRequestMessage(deviceId = ProtocolVectors.DEVICE_ID, deviceName = "Pixel 8 Pro")
        )
        val response = LockCodec.encode(
            AuthResponseMessage(
                deviceId = ProtocolVectors.DEVICE_ID,
                mac = ByteArray(32),
            )
        )

        assertTrue("${request.size} bytes", request.size <= limit)
        assertTrue("${response.size} bytes", response.size <= limit)
    }

    @Test
    fun `Approval Status aprovado nao cabe no MTU padrao`() {
        // Justifica o `requestMtu` do transporte: com 23 bytes a mensagem chega
        // truncada e o cadastro falharia sem explicação.
        val payload = ProtocolVectors.bytes(ProtocolVectors.APPROVAL_APPROVED_JSON)

        assertTrue("${payload.size} bytes", payload.size > 23 - 3)
        assertTrue("${payload.size} bytes", payload.size <= 185 - 3)
    }
}
