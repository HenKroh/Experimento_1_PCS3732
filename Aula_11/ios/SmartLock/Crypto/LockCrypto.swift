import CryptoKit
import Foundation

/// Desafio–resposta com HMAC-SHA256.
///
/// A mesma função tem de existir, byte a byte, na Raspberry e no Android.
/// Em Python:
///
/// ```python
/// msg = context.encode() + b"\x00" + device_id.encode() + b"\x00" + nonce
/// mac = hmac.new(secret, msg, hashlib.sha256).digest()
/// ```
enum LockCrypto {
    /// Tamanho esperado do segredo emitido pela Raspberry.
    static let secretLength = 32

    static func response(
        secret: Data,
        nonce: Data,
        deviceId: String,
        context: String = LockProtocol.unlockContext
    ) -> Data {
        var message = Data()
        message.append(Data(context.utf8))
        message.append(0x00)
        message.append(Data(deviceId.utf8))
        message.append(0x00)
        message.append(nonce)

        let code = HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: secret))
        return Data(code)
    }

    /// Comparação em tempo constante — usada pelo mock, que faz o papel da Raspberry.
    static func constantTimeEquals(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var diff: UInt8 = 0
        for (a, b) in zip(lhs, rhs) { diff |= a ^ b }
        return diff == 0
    }

    static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        guard status == errSecSuccess else {
            // SecRandom só falha se o sistema não tiver entropia; não há recuperação sensata.
            fatalError("SecRandomCopyBytes falhou: \(status)")
        }
        return Data(bytes)
    }

    /// Identificador do celular perante uma fechadura. Um por fechadura, para não
    /// correlacionar o mesmo aparelho entre instalações diferentes.
    static func newDeviceId() -> String {
        UUID().uuidString
    }
}
