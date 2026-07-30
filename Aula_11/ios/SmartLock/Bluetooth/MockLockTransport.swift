import Foundation

/// Raspberry Pi simulada, para rodar o fluxo completo no Simulador — que não
/// tem rádio BLE — e antes de o firmware existir.
///
/// Reproduz o lado servidor do protocolo de verdade: emite o segredo, gera
/// nonces, confere o HMAC, expira desafios, rejeita repetição e bloqueia após
/// tentativas inválidas seguidas. Se o app funciona contra este mock, o que
/// sobra para a Raspberry é hardware e transporte, não regra de protocolo.
final class MockLockTransport: LockTransport {
    /// Como o "proprietário" reage ao pedido de cadastro.
    enum ApprovalBehavior: String, CaseIterable, Identifiable {
        case approve = "Aprovar"
        case deny = "Negar"
        case ignore = "Ignorar (timeout)"

        var id: String { rawValue }
    }

    /// Alterável pela tela de simulação enquanto o app roda.
    var approvalBehavior: ApprovalBehavior = .approve
    /// Quanto tempo o "proprietário" leva para apertar o botão físico.
    var buttonPressDelay: TimeInterval = 3

    var onDiscoveryChange: (([DiscoveredLock]) -> Void)?
    var onUnexpectedDisconnect: ((LockError) -> Void)?
    private(set) var isScanning = false

    // Estado que na vida real mora no SQLite da Raspberry.
    private let identity = LockIdentity(
        lockId: "mock-lock-01",
        lockName: "Fechadura (simulada)",
        firmware: "mock-1.0"
    )
    private var registeredSecrets: [String: Data] = [:]
    private var revokedDevices: Set<String> = []
    private var activeChallenge: (nonce: Data, deviceId: String?, expiresAt: Date)?
    private var authenticatedUntil: Date?
    private var usedNonces: Set<Data> = []
    private var failedAttempts = 0
    private var lockedOutUntil: Date?

    private var isConnected = false
    private var scanTask: Task<Void, Never>?
    private var pendingResult: OperationResultMessage?

    private let ttl: TimeInterval = 5
    private let maxFailedAttempts = 3
    private let lockoutDuration: TimeInterval = 30

    // MARK: - Descoberta

    func startScan() throws {
        isScanning = true
        onDiscoveryChange?([])
        scanTask?.cancel()
        scanTask = Task { @MainActor in
            // Duas fechaduras: a nossa e uma vizinha, para exercitar a lista.
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard !Task.isCancelled else { return }
            self.onDiscoveryChange?([
                DiscoveredLock(id: Self.primaryID, advertisedName: "SmartLock-Sala", rssi: -48)
            ])
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled else { return }
            self.onDiscoveryChange?([
                DiscoveredLock(id: Self.primaryID, advertisedName: "SmartLock-Sala", rssi: -48),
                DiscoveredLock(id: Self.secondaryID, advertisedName: "SmartLock-Lab", rssi: -81),
            ])
        }
    }

    func stopScan() {
        isScanning = false
        scanTask?.cancel()
        scanTask = nil
    }

    // MARK: - Conexão

    func connect(to lock: DiscoveredLock) async throws -> LockIdentity {
        stopScan()
        try await sleep(0.6)
        guard lock.id == Self.primaryID else {
            throw LockError.transport("A fechadura simulada 'SmartLock-Lab' está fora de alcance.")
        }
        isConnected = true
        pendingResult = nil
        return identity
    }

    func disconnect() {
        isConnected = false
        activeChallenge = nil
        authenticatedUntil = nil
        pendingResult = nil
    }

    // MARK: - Cadastro

    func requestAccess(
        _ request: AccessRequestMessage,
        timeout: TimeInterval
    ) async throws -> ApprovalStatusMessage {
        try requireConnection()
        try await sleep(0.4)

        switch approvalBehavior {
        case .ignore:
            // O LED fica piscando e ninguém aperta nada.
            try await sleep(min(timeout, 8))
            throw LockError.timeout

        case .deny:
            try await sleep(buttonPressDelay)
            return ApprovalStatusMessage(
                v: LockProtocol.version,
                state: .denied,
                deviceId: request.deviceId,
                secret: nil,
                lockName: identity.lockName
            )

        case .approve:
            try await sleep(buttonPressDelay)
            let secret = LockCrypto.randomBytes(LockCrypto.secretLength)
            registeredSecrets[request.deviceId] = secret
            revokedDevices.remove(request.deviceId)
            return ApprovalStatusMessage(
                v: LockProtocol.version,
                state: .approved,
                deviceId: request.deviceId,
                secret: secret,
                lockName: identity.lockName
            )
        }
    }

    // MARK: - Desafio–resposta

    func readChallenge() async throws -> ChallengeMessage {
        try requireConnection()
        try await sleep(0.2)
        if let lockedOutUntil, lockedOutUntil > Date() { throw LockError.rateLimited }

        let nonce = LockCrypto.randomBytes(16)
        activeChallenge = (nonce: nonce, deviceId: nil, expiresAt: Date().addingTimeInterval(ttl))
        authenticatedUntil = nil
        return ChallengeMessage(v: LockProtocol.version, nonce: nonce, ttl: ttl)
    }

    func sendAuthResponse(_ response: AuthResponseMessage) async throws {
        try requireConnection()
        try await sleep(0.2)

        if let lockedOutUntil, lockedOutUntil > Date() {
            pendingResult = result(op: "auth", status: .rateLimited, reason: "Bloqueado temporariamente.")
            return
        }

        guard let challenge = activeChallenge else {
            pendingResult = result(op: "auth", status: .error, reason: "Nenhum desafio ativo.")
            return
        }
        activeChallenge = nil

        guard challenge.expiresAt > Date() else {
            pendingResult = result(op: "auth", status: .denied, reason: "Desafio expirado.")
            return
        }
        guard !usedNonces.contains(challenge.nonce) else {
            pendingResult = result(op: "auth", status: .denied, reason: "Nonce já utilizado.")
            return
        }
        guard !revokedDevices.contains(response.deviceId), let secret = registeredSecrets[response.deviceId] else {
            registerFailure()
            pendingResult = result(op: "auth", status: .denied, reason: "Dispositivo não autorizado.")
            return
        }

        let expected = LockCrypto.response(
            secret: secret,
            nonce: challenge.nonce,
            deviceId: response.deviceId
        )
        guard LockCrypto.constantTimeEquals(expected, response.mac) else {
            registerFailure()
            pendingResult = result(op: "auth", status: .denied, reason: "Prova criptográfica inválida.")
            return
        }

        usedNonces.insert(challenge.nonce)
        failedAttempts = 0
        // A autenticação vale por uma janela curta e por um único comando.
        authenticatedUntil = Date().addingTimeInterval(ttl)
        activeChallenge = (nonce: challenge.nonce, deviceId: response.deviceId, expiresAt: challenge.expiresAt)
        pendingResult = result(op: "auth", status: .ok, reason: nil)
    }

    func sendUnlockCommand(_ command: UnlockCommandMessage) async throws {
        try requireConnection()
        try await sleep(0.3)

        guard let authenticatedUntil, authenticatedUntil > Date(),
              activeChallenge?.deviceId == command.deviceId
        else {
            pendingResult = result(op: "unlock", status: .denied, reason: "Sessão não autenticada.")
            return
        }

        // Consome a autenticação: o mesmo desafio não abre a porta duas vezes.
        self.authenticatedUntil = nil
        activeChallenge = nil
        pendingResult = result(op: "unlock", status: .ok, reason: nil)
    }

    func awaitOperationResult(timeout: TimeInterval) async throws -> OperationResultMessage {
        try requireConnection()
        guard let pendingResult else { throw LockError.timeout }
        self.pendingResult = nil
        return pendingResult
    }

    // MARK: - Controles da tela de simulação

    func revokeAllDevices() {
        revokedDevices.formUnion(registeredSecrets.keys)
    }

    func resetLock() {
        registeredSecrets.removeAll()
        revokedDevices.removeAll()
        usedNonces.removeAll()
        activeChallenge = nil
        authenticatedUntil = nil
        failedAttempts = 0
        lockedOutUntil = nil
    }

    // MARK: - Auxiliares

    private static let primaryID = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
    private static let secondaryID = UUID(uuidString: "66666666-7777-8888-9999-AAAAAAAAAAAA")!

    private func requireConnection() throws {
        guard isConnected else { throw LockError.notConnected }
    }

    private func registerFailure() {
        failedAttempts += 1
        if failedAttempts >= maxFailedAttempts {
            lockedOutUntil = Date().addingTimeInterval(lockoutDuration)
            failedAttempts = 0
        }
    }

    private func result(op: String, status: OperationStatus, reason: String?) -> OperationResultMessage {
        OperationResultMessage(v: LockProtocol.version, op: op, status: status, reason: reason)
    }

    private func sleep(_ seconds: TimeInterval) async throws {
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }
}
