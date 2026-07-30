import Foundation

/// Ponte entre os callbacks do Core Bluetooth e `async/await`.
///
/// Guarda o resultado caso ele chegue antes de alguém aguardar (acontece quando
/// o timeout dispara na mesma volta do run loop em que a operação foi criada),
/// e ignora resumos duplicados — reaproveitar uma continuation trava o app.
///
/// Toda a manipulação acontece na main queue; o `@unchecked Sendable` só existe
/// para poder capturar a instância na `Task` de timeout.
final class PendingOperation<T>: @unchecked Sendable {
    private var continuation: CheckedContinuation<T, Error>?
    private var earlyResult: Result<T, Error>?
    private var isFinished = false

    func attach(_ continuation: CheckedContinuation<T, Error>) {
        if let earlyResult {
            self.earlyResult = nil
            continuation.resume(with: earlyResult)
            return
        }
        self.continuation = continuation
    }

    func finish(_ result: Result<T, Error>) {
        guard !isFinished else { return }
        isFinished = true
        if let continuation {
            self.continuation = nil
            continuation.resume(with: result)
        } else {
            earlyResult = result
        }
    }

    func finish(throwing error: Error) {
        finish(.failure(error))
    }
}
