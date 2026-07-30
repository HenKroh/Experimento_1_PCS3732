package com.example.locker.protocol

import java.util.Base64

/**
 * Vetores gerados pela própria implementação da Raspberry:
 *
 * ```
 * python3 -c "
 * import base64, sys; sys.path.insert(0, 'raspberry')
 * from smartlock import protocol
 * from smartlock.authentication import response_mac
 * secret = bytes(range(32)); nonce = bytes(range(0x10, 0x20))
 * device_id = '8f14e45f-ea34-4b9e-9c31-1d2f3a4b5c6d'
 * print(base64.b64encode(response_mac(secret, nonce, device_id)).decode())
 * print(protocol.challenge(nonce, 5.0).decode())
 * "
 * ```
 *
 * Se algum destes valores mudar, as três implementações deixaram de falar a
 * mesma língua.
 */
object ProtocolVectors {
    val secret: ByteArray = ByteArray(32) { it.toByte() }
    val nonce: ByteArray = ByteArray(16) { (0x10 + it).toByte() }
    const val DEVICE_ID = "8f14e45f-ea34-4b9e-9c31-1d2f3a4b5c6d"

    const val EXPECTED_MAC_BASE64 = "VWngmQaY5M5MWaTuAecd0x/PJJnzGhB9XMqz9uL91JY="
    const val EXPECTED_MAC_HEX =
        "5569e0990698e4ce4c59a4ee01e71dd31fcf2499f31a107d5ccab3f6e2fdd496"

    const val CHALLENGE_JSON =
        """{"v":1,"nonce":"EBESExQVFhcYGRobHB0eHw==","ttl":5.0}"""

    const val APPROVAL_APPROVED_JSON =
        """{"v":1,"state":"approved","deviceId":"8f14e45f-ea34-4b9e-9c31-1d2f3a4b5c6d","secret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","lockName":"Fechadura da Sala"}"""

    const val APPROVAL_DENIED_JSON =
        """{"v":1,"state":"denied","deviceId":"8f14e45f-ea34-4b9e-9c31-1d2f3a4b5c6d","secret":null,"lockName":null}"""

    const val DEVICE_INFO_JSON =
        """{"v":1,"lockId":"lock-01","lockName":"Fechadura da Sala","firmware":"1.0"}"""

    const val OPERATION_RESULT_JSON =
        """{"v":1,"op":"auth","status":"rate_limited","reason":"Bloqueado temporariamente."}"""

    fun base64(raw: ByteArray): String = Base64.getEncoder().encodeToString(raw)

    fun bytes(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)
}
