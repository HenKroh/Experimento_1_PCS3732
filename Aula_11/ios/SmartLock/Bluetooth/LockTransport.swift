import Foundation

/// Abstrai o canal até a fechadura para que a lógica de domínio não dependa de
/// Core Bluetooth. Duas implementações: `BLELockTransport` (iPhone físico) e
/// `MockLockTransport` (simulador, que não tem rádio BLE).
///
/// Todas as chamadas e callbacks acontecem na main queue.
protocol LockTransport: AnyObject {
    /// Chamado sempre que a lista de fechaduras anunciando muda.
    var onDiscoveryChange: (([DiscoveredLock]) -> Void)? { get set }
    /// Chamado em desconexões não solicitadas (fechadura fora de alcance, BLE desligado).
    var onUnexpectedDisconnect: ((LockError) -> Void)? { get set }

    var isScanning: Bool { get }

    func startScan() throws
    func stopScan()

    func connect(to lock: DiscoveredLock) async throws -> LockIdentity
    func disconnect()

    /// Envia o pedido de cadastro e devolve o desfecho do botão físico.
    /// Lança `.timeout` se ninguém decidir dentro de `timeout`.
    func requestAccess(_ request: AccessRequestMessage, timeout: TimeInterval) async throws -> ApprovalStatusMessage

    /// Cada leitura devolve um nonce novo.
    func readChallenge() async throws -> ChallengeMessage

    func sendAuthResponse(_ response: AuthResponseMessage) async throws
    func sendUnlockCommand(_ command: UnlockCommandMessage) async throws

    /// Aguarda a notificação de `Operation Result`.
    func awaitOperationResult(timeout: TimeInterval) async throws -> OperationResultMessage
}
