import Foundation

/// Fechadura vista no anúncio BLE, antes de qualquer conexão.
struct DiscoveredLock: Identifiable, Hashable {
    /// Identificador do periférico atribuído pelo iOS. Estável para o mesmo
    /// aparelho enquanto o app estiver instalado, mas **não** é o `lockId`.
    let id: UUID
    let advertisedName: String
    let rssi: Int

    var signalDescription: String {
        switch rssi {
        case (-55)...: return "Sinal forte"
        case (-75)..<(-55): return "Sinal médio"
        default: return "Sinal fraco"
        }
    }
}

/// Identidade da fechadura, lida após conectar.
struct LockIdentity: Equatable {
    let lockId: String
    let lockName: String
    let firmware: String?
}

/// Credencial persistida no Keychain.
struct LockCredential: Codable, Equatable, Identifiable {
    let lockId: String
    var lockName: String
    let deviceId: String
    let secret: Data
    let createdAt: Date
    var lastUsedAt: Date?

    var id: String { lockId }
}

enum ConnectionState: Equatable {
    case disconnected
    case connecting
    case connected(LockIdentity)
    case failed(String)

    var identity: LockIdentity? {
        if case .connected(let identity) = self { return identity }
        return nil
    }

    var isConnected: Bool { identity != nil }
}

enum EnrollmentState: Equatable {
    case idle
    case requesting
    /// Aguardando alguém apertar o botão físico na Raspberry.
    case awaitingApproval(deadline: Date)
    case approved
    case denied
    case timedOut
    case failed(String)
}

enum UnlockState: Equatable {
    case idle
    case authenticating
    case unlocking
    case unlocked(at: Date)
    case failed(String)
}
