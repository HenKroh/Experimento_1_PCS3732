import Foundation
import Security

protocol CredentialStoring: AnyObject {
    func all() throws -> [LockCredential]
    func credential(for lockId: String) throws -> LockCredential?
    func save(_ credential: LockCredential) throws
    func delete(lockId: String) throws
}

/// Guarda um `LockCredential` por fechadura no Keychain.
///
/// `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` impede que o segredo saia
/// deste aparelho por backup ou restauração em outro iPhone — o cadastro é
/// vinculado ao dispositivo aprovado pelo botão físico.
final class KeychainCredentialStore: CredentialStoring {
    private let service: String

    init(service: String = "br.usp.pcs3732.smartlock.credential") {
        self.service = service
    }

    func all() throws -> [LockCredential] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnData as String: true,
        ]

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess else { throw LockError.keychain(status) }
        guard let items = result as? [Data] else { return [] }

        // Itens ilegíveis (formato antigo) são ignorados em vez de derrubar a lista.
        return items
            .compactMap { try? JSONDecoder().decode(LockCredential.self, from: $0) }
            .sorted { $0.createdAt < $1.createdAt }
    }

    func credential(for lockId: String) throws -> LockCredential? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: lockId,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecReturnData as String: true,
        ]

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw LockError.keychain(status) }
        guard let data = result as? Data else { throw LockError.keychain(errSecDecode) }
        return try JSONDecoder().decode(LockCredential.self, from: data)
    }

    func save(_ credential: LockCredential) throws {
        let payload = try JSONEncoder().encode(credential)
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: credential.lockId,
        ]

        let update: [String: Any] = [
            kSecValueData as String: payload,
            kSecAttrLabel as String: credential.lockName,
        ]

        let status = SecItemUpdate(base as CFDictionary, update as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw LockError.keychain(status) }

        var insert = base
        insert[kSecValueData as String] = payload
        insert[kSecAttrLabel as String] = credential.lockName
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly

        let addStatus = SecItemAdd(insert as CFDictionary, nil)
        guard addStatus == errSecSuccess else { throw LockError.keychain(addStatus) }
    }

    func delete(lockId: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: lockId,
        ]

        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw LockError.keychain(status)
        }
    }
}

/// Usado nos previews do SwiftUI, onde o Keychain do simulador não é confiável.
final class InMemoryCredentialStore: CredentialStoring {
    private var storage: [String: LockCredential] = [:]

    init(seed: [LockCredential] = []) {
        for credential in seed { storage[credential.lockId] = credential }
    }

    func all() throws -> [LockCredential] {
        storage.values.sorted { $0.createdAt < $1.createdAt }
    }

    func credential(for lockId: String) throws -> LockCredential? {
        storage[lockId]
    }

    func save(_ credential: LockCredential) throws {
        storage[credential.lockId] = credential
    }

    func delete(lockId: String) throws {
        storage[lockId] = nil
    }
}
