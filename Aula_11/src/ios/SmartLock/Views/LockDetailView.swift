import SwiftUI

struct LockDetailView: View {
    let lock: DiscoveredLock

    @EnvironmentObject private var manager: LockManager
    @Environment(\.dismiss) private var dismiss
    @AppStorage("deviceName") private var deviceName = "iPhone"
    @State private var showRemoveConfirmation = false

    var body: some View {
        List {
            Section("Conexão") {
                ConnectionRow(state: manager.connection)
                if case .failed = manager.connection {
                    Button("Tentar novamente") { manager.select(lock) }
                }
            }

            if manager.connection.isConnected {
                if manager.currentCredential == nil {
                    enrollmentSection
                } else {
                    unlockSection
                    credentialSection
                }
            }
        }
        .navigationTitle(lock.advertisedName)
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { manager.disconnect() }
        .confirmationDialog(
            "Remover a credencial deste iPhone?",
            isPresented: $showRemoveConfirmation,
            titleVisibility: .visible
        ) {
            Button("Remover", role: .destructive) {
                if let lockId = manager.connection.identity?.lockId {
                    manager.removeCredential(lockId: lockId)
                }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Você precisará de uma nova aprovação pelo botão físico para voltar a abrir esta fechadura.")
        }
    }

    // MARK: - Cadastro

    private var enrollmentSection: some View {
        Section {
            TextField("Nome deste aparelho", text: $deviceName)
                .textInputAutocapitalization(.words)
                .disabled(isEnrollmentBusy)

            Button {
                manager.requestAccess(deviceName: deviceName)
            } label: {
                Label("Solicitar acesso", systemImage: "hand.raised")
            }
            .disabled(isEnrollmentBusy || deviceName.trimmingCharacters(in: .whitespaces).isEmpty)

            EnrollmentStatusRow(state: manager.enrollment)
        } header: {
            Text("Cadastro")
        } footer: {
            Text("Depois de solicitar, alguém precisa apertar o botão **Permitir** na fechadura. O segredo enviado fica no Keychain e nunca sai deste iPhone.")
        }
    }

    private var isEnrollmentBusy: Bool {
        switch manager.enrollment {
        case .requesting, .awaitingApproval: return true
        default: return false
        }
    }

    // MARK: - Desbloqueio

    private var unlockSection: some View {
        Section {
            Button {
                manager.unlockDoor()
            } label: {
                Label("Desbloquear", systemImage: "lock.open")
                    .frame(maxWidth: .infinity)
                    .font(.headline)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
            .disabled(isUnlockBusy)

            UnlockStatusRow(state: manager.unlock)
        } header: {
            Text("Fechadura")
        } footer: {
            Text("O app responde a um desafio aleatório da fechadura. Nenhuma chave reutilizável trafega pelo ar.")
        }
    }

    private var isUnlockBusy: Bool {
        manager.unlock == .authenticating || manager.unlock == .unlocking
    }

    // MARK: - Credencial

    @ViewBuilder
    private var credentialSection: some View {
        if let credential = manager.currentCredential {
            Section("Credencial") {
                LabeledContent("Fechadura", value: credential.lockName)
                LabeledContent("ID deste aparelho", value: String(credential.deviceId.prefix(8)) + "…")
                LabeledContent("Cadastrada em", value: credential.createdAt.formatted(date: .abbreviated, time: .shortened))
                if let firmware = manager.connection.identity?.firmware {
                    LabeledContent("Firmware", value: firmware)
                }
                Button("Remover credencial deste iPhone", role: .destructive) {
                    showRemoveConfirmation = true
                }
            }
        }
    }
}

// MARK: - Linhas de estado

private struct ConnectionRow: View {
    let state: ConnectionState

    var body: some View {
        switch state {
        case .disconnected:
            StatusRow(icon: "bolt.horizontal", tint: .secondary, title: "Desconectado")
        case .connecting:
            HStack(spacing: 12) {
                ProgressView()
                Text("Conectando…")
            }
        case .connected(let identity):
            StatusRow(icon: "checkmark.circle.fill", tint: .green, title: identity.lockName, subtitle: identity.lockId)
        case .failed(let message):
            StatusRow(icon: "exclamationmark.triangle.fill", tint: .orange, title: "Falha na conexão", subtitle: message)
        }
    }
}

private struct EnrollmentStatusRow: View {
    let state: EnrollmentState

    var body: some View {
        switch state {
        case .idle:
            EmptyView()
        case .requesting:
            HStack(spacing: 12) {
                ProgressView()
                Text("Enviando solicitação…")
            }
        case .awaitingApproval(let deadline):
            HStack(spacing: 12) {
                ProgressView()
                VStack(alignment: .leading, spacing: 2) {
                    Text("Aguardando o botão físico…")
                    Text(deadline, style: .timer)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        case .approved:
            StatusRow(icon: "checkmark.seal.fill", tint: .green, title: "Acesso aprovado", subtitle: "Credencial salva no Keychain.")
        case .denied:
            StatusRow(icon: "hand.thumbsdown.fill", tint: .red, title: "Acesso negado", subtitle: "O botão Negar foi pressionado.")
        case .timedOut:
            StatusRow(icon: "clock.badge.exclamationmark", tint: .orange, title: "Sem resposta", subtitle: "Ninguém decidiu a tempo. Tente de novo.")
        case .failed(let message):
            StatusRow(icon: "exclamationmark.triangle.fill", tint: .orange, title: "Falha no cadastro", subtitle: message)
        }
    }
}

private struct UnlockStatusRow: View {
    let state: UnlockState

    var body: some View {
        switch state {
        case .idle:
            EmptyView()
        case .authenticating:
            HStack(spacing: 12) {
                ProgressView()
                Text("Respondendo ao desafio…")
            }
        case .unlocking:
            HStack(spacing: 12) {
                ProgressView()
                Text("Acionando a fechadura…")
            }
        case .unlocked(let date):
            StatusRow(
                icon: "lock.open.fill",
                tint: .green,
                title: "Porta destravada",
                subtitle: date.formatted(date: .omitted, time: .standard)
            )
        case .failed(let message):
            StatusRow(icon: "xmark.octagon.fill", tint: .red, title: "Não foi possível abrir", subtitle: message)
        }
    }
}

private struct StatusRow: View {
    let icon: String
    let tint: Color
    let title: String
    var subtitle: String?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(tint)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        LockDetailView(
            lock: DiscoveredLock(
                id: UUID(),
                advertisedName: "SmartLock-Sala",
                rssi: -52
            )
        )
        .environmentObject(
            LockManager(transport: MockLockTransport(), store: InMemoryCredentialStore())
        )
    }
}
