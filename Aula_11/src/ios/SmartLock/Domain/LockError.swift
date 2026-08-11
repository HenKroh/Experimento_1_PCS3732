import Foundation

enum LockError: LocalizedError, Equatable {
    case bluetoothUnavailable(String)
    case notConnected
    case characteristicMissing(String)
    case timeout
    case cancelled
    case unsupportedProtocol(Int)
    case malformedResponse
    case noCredential
    case accessDenied(String?)
    case rateLimited
    case keychain(OSStatus)
    case transport(String)

    var errorDescription: String? {
        switch self {
        case .bluetoothUnavailable(let reason):
            return reason
        case .notConnected:
            return "Sem conexão com a fechadura."
        case .characteristicMissing(let name):
            return "A fechadura não expõe a característica \(name)."
        case .timeout:
            return "A fechadura não respondeu a tempo."
        case .cancelled:
            return "Operação cancelada."
        case .unsupportedProtocol(let version):
            return "Fechadura usa a versão \(version) do protocolo; o app usa a \(LockProtocol.version)."
        case .malformedResponse:
            return "Resposta da fechadura em formato inválido."
        case .noCredential:
            return "Este iPhone ainda não tem credencial para esta fechadura."
        case .accessDenied(let reason):
            return reason ?? "Acesso negado pela fechadura."
        case .rateLimited:
            return "Muitas tentativas inválidas. Aguarde antes de tentar de novo."
        case .keychain(let status):
            return "Falha no Keychain (código \(status))."
        case .transport(let message):
            return message
        }
    }
}
