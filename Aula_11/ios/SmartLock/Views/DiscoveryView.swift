import SwiftUI

struct DiscoveryView: View {
    @EnvironmentObject private var manager: LockManager
    @State private var selection: DiscoveredLock?

    var body: some View {
        NavigationStack {
            List {
                nearbySection

                if !manager.credentials.isEmpty {
                    credentialsSection
                }

                if let mock = manager.transport as? MockLockTransport {
                    SimulationSection(mock: mock)
                }
            }
            .navigationTitle("Fechaduras")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(manager.isScanning ? "Parar" : "Procurar") {
                        manager.isScanning ? manager.stopScan() : manager.startScan()
                    }
                }
            }
            .navigationDestination(item: $selection) { lock in
                LockDetailView(lock: lock)
                    .environmentObject(manager)
            }
            .onAppear { manager.startScan() }
            .onDisappear { manager.stopScan() }
            .alert(
                "Aviso",
                isPresented: Binding(
                    get: { manager.alertMessage != nil },
                    set: { if !$0 { manager.alertMessage = nil } }
                ),
                presenting: manager.alertMessage
            ) { _ in
                Button("OK", role: .cancel) { manager.alertMessage = nil }
            } message: { message in
                Text(message)
            }
        }
    }

    private var nearbySection: some View {
        Section {
            if manager.discovered.isEmpty {
                HStack(spacing: 12) {
                    if manager.isScanning {
                        ProgressView()
                    }
                    Text(manager.isScanning ? "Procurando fechaduras…" : "Nenhuma fechadura encontrada.")
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }

            ForEach(manager.discovered) { lock in
                Button {
                    selection = lock
                    manager.select(lock)
                } label: {
                    LockRow(lock: lock, isPaired: isPaired(lock))
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text("Por perto")
        } footer: {
            if AppEnvironment.isSimulator {
                Text("Rodando no Simulador: as fechaduras são simuladas, sem BLE real.")
            }
        }
    }

    private var credentialsSection: some View {
        Section("Credenciais salvas") {
            ForEach(manager.credentials) { credential in
                VStack(alignment: .leading, spacing: 4) {
                    Text(credential.lockName)
                        .font(.body)
                    Text(credential.lastUsedAt.map { "Último acesso: \($0.formatted(date: .abbreviated, time: .shortened))" }
                        ?? "Cadastrada em \(credential.createdAt.formatted(date: .abbreviated, time: .shortened))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .swipeActions {
                    Button("Remover", role: .destructive) {
                        manager.removeCredential(lockId: credential.lockId)
                    }
                }
            }
        }
    }

    /// A lista de anúncios só traz o nome; o `lockId` real vem depois de
    /// conectar. O casamento por nome é uma dica visual, não uma garantia.
    private func isPaired(_ lock: DiscoveredLock) -> Bool {
        manager.credentials.contains { $0.lockName == lock.advertisedName }
    }
}

private struct LockRow: View {
    let lock: DiscoveredLock
    let isPaired: Bool

    var body: some View {
        HStack {
            Image(systemName: isPaired ? "lock.badge.clock" : "lock")
                .font(.title3)
                .foregroundStyle(isPaired ? Color.accentColor : .secondary)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text(lock.advertisedName)
                Text("\(lock.signalDescription) · \(lock.rssi) dBm")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }
}

#Preview {
    DiscoveryView()
        .environmentObject(
            LockManager(transport: MockLockTransport(), store: InMemoryCredentialStore())
        )
}
