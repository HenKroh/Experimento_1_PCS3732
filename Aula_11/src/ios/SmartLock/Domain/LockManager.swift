import Foundation
import SwiftUI

/// Máquina de estados do app: descoberta, cadastro e desbloqueio.
///
/// Espelha os fluxos que a Raspberry implementa; qualquer divergência aqui
/// aparece como erro de protocolo em campo.
@MainActor
final class LockManager: ObservableObject {
    @Published private(set) var discovered: [DiscoveredLock] = []
    @Published private(set) var isScanning = false
    @Published private(set) var connection: ConnectionState = .disconnected
    @Published private(set) var enrollment: EnrollmentState = .idle
    @Published private(set) var unlock: UnlockState = .idle
    @Published private(set) var credentials: [LockCredential] = []
    @Published var alertMessage: String?

    /// Quanto tempo o app espera alguém apertar o botão físico na Raspberry.
    private let approvalTimeout: TimeInterval = 60
    /// Margem para a Raspberry acionar o relé e responder.
    private let operationTimeout: TimeInterval = 8

    let transport: LockTransport
    private let store: CredentialStoring
    private var activeTask: Task<Void, Never>?

    init(transport: LockTransport, store: CredentialStoring) {
        self.transport = transport
        self.store = store

        transport.onDiscoveryChange = { [weak self] locks in
            self?.discovered = locks
        }
        transport.onUnexpectedDisconnect = { [weak self] error in
            guard let self else { return }
            self.connection = .failed(error.localizedDescription)
            self.alertMessage = error.localizedDescription
            if case .awaitingApproval = self.enrollment {
                self.enrollment = .failed(error.localizedDescription)
            }
            if self.unlock == .authenticating || self.unlock == .unlocking {
                self.unlock = .failed(error.localizedDescription)
            }
        }

        reloadCredentials()
    }

    // MARK: - Credenciais

    func reloadCredentials() {
        do {
            credentials = try store.all()
        } catch {
            credentials = []
            alertMessage = error.localizedDescription
        }
    }

    func credential(for lockId: String) -> LockCredential? {
        credentials.first { $0.lockId == lockId }
    }

    var currentCredential: LockCredential? {
        connection.identity.flatMap { credential(for: $0.lockId) }
    }

    func removeCredential(lockId: String) {
        do {
            try store.delete(lockId: lockId)
            reloadCredentials()
            enrollment = .idle
            unlock = .idle
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    // MARK: - Descoberta

    func startScan() {
        do {
            discovered = []
            try transport.startScan()
            isScanning = transport.isScanning
        } catch {
            isScanning = false
            alertMessage = error.localizedDescription
        }
    }

    func stopScan() {
        transport.stopScan()
        isScanning = false
    }

    // MARK: - Conexão

    func select(_ lock: DiscoveredLock) {
        run { [self] in
            connection = .connecting
            enrollment = .idle
            unlock = .idle
            do {
                let identity = try await transport.connect(to: lock)
                connection = .connected(identity)
            } catch {
                connection = .failed(error.localizedDescription)
            }
        }
    }

    func disconnect() {
        activeTask?.cancel()
        activeTask = nil
        transport.disconnect()
        connection = .disconnected
        enrollment = .idle
        unlock = .idle
    }

    // MARK: - Cadastro

    func requestAccess(deviceName: String) {
        guard let identity = connection.identity else {
            alertMessage = LockError.notConnected.localizedDescription
            return
        }

        run { [self] in
            enrollment = .requesting

            // Um deviceId por fechadura: um novo cadastro na mesma fechadura
            // reaproveita o identificador, então a Raspberry substitui o
            // registro antigo em vez de acumular linhas órfãs.
            let deviceId = credential(for: identity.lockId)?.deviceId ?? LockCrypto.newDeviceId()
            let request = AccessRequestMessage(deviceId: deviceId, deviceName: deviceName)

            do {
                enrollment = .awaitingApproval(deadline: Date().addingTimeInterval(approvalTimeout))
                let status = try await transport.requestAccess(request, timeout: approvalTimeout)

                switch status.state {
                case .approved:
                    guard let secret = status.secret, secret.count == LockCrypto.secretLength else {
                        enrollment = .failed("A fechadura aprovou o acesso mas não enviou um segredo válido.")
                        return
                    }
                    let credential = LockCredential(
                        lockId: identity.lockId,
                        lockName: status.lockName ?? identity.lockName,
                        deviceId: deviceId,
                        secret: secret,
                        createdAt: Date(),
                        lastUsedAt: nil
                    )
                    try store.save(credential)
                    reloadCredentials()
                    enrollment = .approved

                case .denied:
                    enrollment = .denied
                case .timeout:
                    enrollment = .timedOut
                case .pending:
                    enrollment = .failed("Resposta inesperada da fechadura.")
                }
            } catch LockError.timeout {
                enrollment = .timedOut
            } catch {
                enrollment = .failed(error.localizedDescription)
            }
        }
    }

    // MARK: - Desbloqueio

    func unlockDoor() {
        guard let identity = connection.identity else {
            alertMessage = LockError.notConnected.localizedDescription
            return
        }
        guard var credential = credential(for: identity.lockId) else {
            unlock = .failed(LockError.noCredential.localizedDescription)
            return
        }

        run { [self] in
            unlock = .authenticating
            do {
                let challenge = try await transport.readChallenge()
                let mac = LockCrypto.response(
                    secret: credential.secret,
                    nonce: challenge.nonce,
                    deviceId: credential.deviceId
                )

                try await transport.sendAuthResponse(
                    AuthResponseMessage(deviceId: credential.deviceId, mac: mac)
                )
                let auth = try await transport.awaitOperationResult(timeout: operationTimeout)
                guard auth.status == .ok else {
                    unlock = .failed(describe(auth))
                    return
                }

                unlock = .unlocking
                try await transport.sendUnlockCommand(
                    UnlockCommandMessage(deviceId: credential.deviceId)
                )
                let result = try await transport.awaitOperationResult(timeout: operationTimeout)
                guard result.status == .ok else {
                    unlock = .failed(describe(result))
                    return
                }

                unlock = .unlocked(at: Date())
                credential.lastUsedAt = Date()
                try? store.save(credential)
                reloadCredentials()
            } catch {
                unlock = .failed(error.localizedDescription)
            }
        }
    }

    // MARK: - Auxiliares

    private func describe(_ result: OperationResultMessage) -> String {
        switch result.status {
        case .ok:
            return "Operação concluída."
        case .rateLimited:
            return LockError.rateLimited.localizedDescription
        case .denied:
            return result.reason ?? "Operação negada pela fechadura."
        case .error:
            return result.reason ?? "A fechadura relatou um erro interno."
        }
    }

    /// Só uma operação de cada vez: duas escritas concorrentes nas mesmas
    /// características embaralhariam desafio e resposta.
    private func run(_ body: @escaping () async -> Void) {
        activeTask?.cancel()
        activeTask = Task { @MainActor in
            await body()
        }
    }
}
