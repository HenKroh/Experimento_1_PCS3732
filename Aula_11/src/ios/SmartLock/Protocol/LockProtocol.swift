import CoreBluetooth
import Foundation

/// Contrato compartilhado entre Raspberry Pi, Android e iOS.
///
/// Qualquer mudança aqui precisa ser espelhada nas outras duas implementações.
/// Todas as mensagens trafegam como JSON UTF-8 dentro das características GATT;
/// campos `Data` são codificados em Base64 (padrão do `JSONEncoder`).
enum LockProtocol {
    /// Versão do protocolo. O periférico rejeita mensagens com versão desconhecida.
    static let version = 1

    /// Contexto de domínio usado no HMAC do desbloqueio.
    /// Amarrar o comando à prova impede reaproveitar um MAC para outra operação.
    static let unlockContext = "unlock"
}

/// UUIDs do serviço e das características. Gerados para este projeto.
enum LockGATT {
    static let service = CBUUID(string: "A1B20001-5F6D-4C3E-9A2B-7E8F0D1C2B3A")

    /// Leitura. Identidade da fechadura (`DeviceInfoMessage`).
    static let deviceInfo = CBUUID(string: "A1B20002-5F6D-4C3E-9A2B-7E8F0D1C2B3A")
    /// Escrita com resposta. Pedido de cadastro (`AccessRequestMessage`).
    static let accessRequest = CBUUID(string: "A1B20003-5F6D-4C3E-9A2B-7E8F0D1C2B3A")
    /// Notificação. Resultado do botão físico (`ApprovalStatusMessage`).
    static let approvalStatus = CBUUID(string: "A1B20004-5F6D-4C3E-9A2B-7E8F0D1C2B3A")
    /// Leitura. Nonce do desafio (`ChallengeMessage`). Cada leitura gera um nonce novo.
    static let authChallenge = CBUUID(string: "A1B20005-5F6D-4C3E-9A2B-7E8F0D1C2B3A")
    /// Escrita com resposta. Prova criptográfica (`AuthResponseMessage`).
    static let authResponse = CBUUID(string: "A1B20006-5F6D-4C3E-9A2B-7E8F0D1C2B3A")
    /// Escrita com resposta. Comando de desbloqueio (`UnlockCommandMessage`).
    static let unlockCommand = CBUUID(string: "A1B20007-5F6D-4C3E-9A2B-7E8F0D1C2B3A")
    /// Notificação. Desfecho da operação (`OperationResultMessage`).
    static let operationResult = CBUUID(string: "A1B20008-5F6D-4C3E-9A2B-7E8F0D1C2B3A")

    /// Características que o app assina assim que descobre o serviço.
    static let notifying = [approvalStatus, operationResult]
}

// MARK: - Mensagens enviadas pelo celular

struct AccessRequestMessage: Encodable {
    var v = LockProtocol.version
    /// Identificador local do celular, gerado uma única vez por fechadura.
    let deviceId: String
    /// Nome exibido para o proprietário decidir se aprova.
    let deviceName: String
}

struct AuthResponseMessage: Encodable {
    var v = LockProtocol.version
    let deviceId: String
    /// `HMAC-SHA256(secret, contexto || 0x00 || deviceId || 0x00 || nonce)`
    let mac: Data
}

struct UnlockCommandMessage: Encodable {
    var v = LockProtocol.version
    let deviceId: String
}

// MARK: - Mensagens enviadas pela Raspberry

struct DeviceInfoMessage: Decodable {
    let v: Int
    /// Identidade estável da fechadura; é a chave usada no Keychain.
    let lockId: String
    let lockName: String
    let firmware: String?
}

enum ApprovalState: String, Decodable {
    case pending
    case approved
    case denied
    case timeout
}

struct ApprovalStatusMessage: Decodable {
    let v: Int
    let state: ApprovalState
    let deviceId: String
    /// Segredo de 32 bytes, presente apenas quando `state == .approved`.
    let secret: Data?
    let lockName: String?
}

struct ChallengeMessage: Decodable {
    let v: Int
    let nonce: Data
    /// Validade do nonce em segundos.
    let ttl: TimeInterval
}

enum OperationStatus: String, Decodable {
    case ok
    case denied
    case error
    /// Bloqueio temporário por excesso de tentativas inválidas.
    case rateLimited = "rate_limited"
}

struct OperationResultMessage: Decodable {
    let v: Int
    let op: String
    let status: OperationStatus
    let reason: String?
}

// MARK: - Serialização

enum LockCodec {
    static let encoder = JSONEncoder()
    static let decoder = JSONDecoder()

    static func encode<T: Encodable>(_ value: T) throws -> Data {
        try encoder.encode(value)
    }

    static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        let message = try decoder.decode(type, from: data)
        if let versioned = message as? VersionedMessage, versioned.v != LockProtocol.version {
            throw LockError.unsupportedProtocol(versioned.v)
        }
        return message
    }
}

/// Permite validar a versão sem repetir código em cada mensagem.
protocol VersionedMessage {
    var v: Int { get }
}

extension DeviceInfoMessage: VersionedMessage {}
extension ApprovalStatusMessage: VersionedMessage {}
extension ChallengeMessage: VersionedMessage {}
extension OperationResultMessage: VersionedMessage {}
