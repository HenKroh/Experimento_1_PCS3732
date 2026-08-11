package com.example.locker.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID

/**
 * Contrato compartilhado entre Raspberry Pi, Android e iOS.
 *
 * Espelha `ios/SmartLock/Protocol/LockProtocol.swift` e
 * `raspberry/smartlock/protocol.py`. Qualquer mudança aqui precisa ser
 * refletida nas outras duas implementações.
 *
 * Todas as mensagens trafegam como JSON UTF-8 dentro das características GATT;
 * campos binários viajam em Base64, que é como o `JSONEncoder` do Swift
 * codifica `Data` por padrão.
 */
object LockProtocol {
    /** Versão do protocolo. O periférico rejeita mensagens com versão desconhecida. */
    const val VERSION = 1

    /**
     * Contexto de domínio usado no HMAC do desbloqueio.
     * Amarrar o comando à prova impede reaproveitar um MAC para outra operação.
     */
    const val UNLOCK_CONTEXT = "unlock"

    /** Tamanho do segredo emitido no cadastro. O app recusa segredo de outro tamanho. */
    const val SECRET_LENGTH = 32
}

/** UUIDs do serviço e das características. Gerados para este projeto. */
object LockGatt {
    val service: UUID = UUID.fromString("A1B20001-5F6D-4C3E-9A2B-7E8F0D1C2B3A")

    /** Leitura. Identidade da fechadura ([DeviceInfoMessage]). */
    val deviceInfo: UUID = UUID.fromString("A1B20002-5F6D-4C3E-9A2B-7E8F0D1C2B3A")

    /** Escrita com resposta. Pedido de cadastro ([AccessRequestMessage]). */
    val accessRequest: UUID = UUID.fromString("A1B20003-5F6D-4C3E-9A2B-7E8F0D1C2B3A")

    /** Notificação. Resultado do botão físico ([ApprovalStatusMessage]). */
    val approvalStatus: UUID = UUID.fromString("A1B20004-5F6D-4C3E-9A2B-7E8F0D1C2B3A")

    /** Leitura. Nonce do desafio ([ChallengeMessage]). Cada leitura gera um nonce novo. */
    val authChallenge: UUID = UUID.fromString("A1B20005-5F6D-4C3E-9A2B-7E8F0D1C2B3A")

    /** Escrita com resposta. Prova criptográfica ([AuthResponseMessage]). */
    val authResponse: UUID = UUID.fromString("A1B20006-5F6D-4C3E-9A2B-7E8F0D1C2B3A")

    /** Escrita com resposta. Comando de desbloqueio ([UnlockCommandMessage]). */
    val unlockCommand: UUID = UUID.fromString("A1B20007-5F6D-4C3E-9A2B-7E8F0D1C2B3A")

    /** Notificação. Desfecho da operação ([OperationResultMessage]). */
    val operationResult: UUID = UUID.fromString("A1B20008-5F6D-4C3E-9A2B-7E8F0D1C2B3A")

    /** Descritor padrão de configuração de notificação (CCCD). */
    val clientCharacteristicConfig: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** Características que o app assina assim que descobre o serviço. */
    val notifying = listOf(approvalStatus, operationResult)

    /** Nome legível, usado nas mensagens de erro mostradas ao usuário. */
    fun nameOf(uuid: UUID): String = when (uuid) {
        deviceInfo -> "Device Information"
        accessRequest -> "Access Request"
        approvalStatus -> "Approval Status"
        authChallenge -> "Authentication Challenge"
        authResponse -> "Authentication Response"
        unlockCommand -> "Unlock Command"
        operationResult -> "Operation Result"
        else -> uuid.toString()
    }
}

/**
 * `ByteArray` como string Base64, para casar com o `JSONEncoder` do Swift e com
 * o `base64.b64encode` da Raspberry.
 */
object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}

// --------------------------------------------------------------------------- //
// Mensagens enviadas pelo celular
// --------------------------------------------------------------------------- //

@Serializable
data class AccessRequestMessage(
    /** Identificador local do celular, gerado uma única vez por fechadura. */
    val deviceId: String,
    /** Nome exibido para o proprietário decidir se aprova. */
    val deviceName: String,
    val v: Int = LockProtocol.VERSION,
)

@Serializable
data class AuthResponseMessage(
    val deviceId: String,
    /** `HMAC-SHA256(secret, contexto || 0x00 || deviceId || 0x00 || nonce)` */
    @Serializable(with = Base64ByteArraySerializer::class) val mac: ByteArray,
    val v: Int = LockProtocol.VERSION,
) {
    // `ByteArray` usa igualdade por referência; o `data class` gerado seria enganoso.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthResponseMessage) return false
        return v == other.v && deviceId == other.deviceId && mac.contentEquals(other.mac)
    }

    override fun hashCode(): Int =
        (v * 31 + deviceId.hashCode()) * 31 + mac.contentHashCode()
}

@Serializable
data class UnlockCommandMessage(
    val deviceId: String,
    val v: Int = LockProtocol.VERSION,
)

// --------------------------------------------------------------------------- //
// Mensagens enviadas pela Raspberry
// --------------------------------------------------------------------------- //

/** Permite validar a versão sem repetir código em cada mensagem. */
sealed interface VersionedMessage {
    val v: Int
}

@Serializable
data class DeviceInfoMessage(
    override val v: Int,
    /** Identidade estável da fechadura; é a chave usada no armazenamento local. */
    val lockId: String,
    val lockName: String,
    val firmware: String? = null,
) : VersionedMessage

@Serializable
enum class ApprovalState {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("denied") DENIED,
    @SerialName("timeout") TIMEOUT,
}

@Serializable
data class ApprovalStatusMessage(
    override val v: Int,
    val state: ApprovalState,
    val deviceId: String,
    /** Segredo de 32 bytes, presente apenas quando [state] é [ApprovalState.APPROVED]. */
    @Serializable(with = Base64ByteArraySerializer::class) val secret: ByteArray? = null,
    val lockName: String? = null,
) : VersionedMessage {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApprovalStatusMessage) return false
        return v == other.v &&
            state == other.state &&
            deviceId == other.deviceId &&
            lockName == other.lockName &&
            secret.contentEquals(other.secret)
    }

    override fun hashCode(): Int {
        var result = v
        result = 31 * result + state.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + (secret?.contentHashCode() ?: 0)
        result = 31 * result + (lockName?.hashCode() ?: 0)
        return result
    }
}

@Serializable
data class ChallengeMessage(
    override val v: Int,
    @Serializable(with = Base64ByteArraySerializer::class) val nonce: ByteArray,
    /** Validade do nonce em segundos. */
    val ttl: Double,
) : VersionedMessage {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChallengeMessage) return false
        return v == other.v && ttl == other.ttl && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int =
        (v * 31 + nonce.contentHashCode()) * 31 + ttl.hashCode()
}

@Serializable
enum class OperationStatus {
    @SerialName("ok") OK,
    @SerialName("denied") DENIED,
    @SerialName("error") ERROR,

    /** Bloqueio temporário por excesso de tentativas inválidas. */
    @SerialName("rate_limited") RATE_LIMITED,
}

@Serializable
data class OperationResultMessage(
    override val v: Int,
    val op: String,
    val status: OperationStatus,
    val reason: String? = null,
) : VersionedMessage

// --------------------------------------------------------------------------- //
// Serialização
// --------------------------------------------------------------------------- //

/** Lançada quando o payload não é a mensagem esperada. */
class MalformedMessageException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Lançada quando a fechadura fala uma versão diferente do protocolo. */
class UnsupportedProtocolException(val version: Int) :
    Exception("Versão de protocolo não suportada: $version")

object LockCodec {
    val json = Json {
        // A Raspberry pode acrescentar campos; versão nova de app não deve
        // quebrar por causa disso.
        ignoreUnknownKeys = true
        // Sem isto o `v = 1` (valor padrão) não seria emitido, e a Raspberry
        // rejeitaria a mensagem por versão ausente.
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(message: AccessRequestMessage): ByteArray =
        json.encodeToString(AccessRequestMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    fun encode(message: AuthResponseMessage): ByteArray =
        json.encodeToString(AuthResponseMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    fun encode(message: UnlockCommandMessage): ByteArray =
        json.encodeToString(UnlockCommandMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    /**
     * Desserializa e valida a versão do envelope, como o `LockCodec` do iOS e o
     * `protocol.decode` da Raspberry.
     */
    inline fun <reified T : VersionedMessage> decode(payload: ByteArray): T {
        val message = try {
            json.decodeFromString<T>(payload.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw MalformedMessageException(
                "Resposta da fechadura em formato inválido.",
                error,
            )
        }
        if (message.v != LockProtocol.VERSION) {
            throw UnsupportedProtocolException(message.v)
        }
        return message
    }
}
