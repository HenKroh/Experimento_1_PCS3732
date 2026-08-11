import SwiftUI

@main
struct SmartLockApp: App {
    @StateObject private var manager = LockManager(
        transport: AppEnvironment.makeTransport(),
        store: KeychainCredentialStore()
    )

    var body: some Scene {
        WindowGroup {
            DiscoveryView()
                .environmentObject(manager)
        }
    }
}

enum AppEnvironment {
    /// O Simulador não tem rádio BLE: lá o app fala com a Raspberry simulada.
    static var isSimulator: Bool {
        #if targetEnvironment(simulator)
        true
        #else
        false
        #endif
    }

    static func makeTransport() -> LockTransport {
        isSimulator ? MockLockTransport() : BLELockTransport()
    }
}
