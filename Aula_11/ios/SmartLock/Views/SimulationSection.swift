import SwiftUI

/// Controles que fazem o papel do hardware da Raspberry enquanto ela não existe.
/// Só aparece quando o transporte em uso é o `MockLockTransport`.
struct SimulationSection: View {
    let mock: MockLockTransport

    @EnvironmentObject private var manager: LockManager
    @State private var behavior: MockLockTransport.ApprovalBehavior
    @State private var delay: TimeInterval

    init(mock: MockLockTransport) {
        self.mock = mock
        _behavior = State(initialValue: mock.approvalBehavior)
        _delay = State(initialValue: mock.buttonPressDelay)
    }

    var body: some View {
        Section {
            Picker("Botão físico", selection: $behavior) {
                ForEach(MockLockTransport.ApprovalBehavior.allCases) { option in
                    Text(option.rawValue).tag(option)
                }
            }
            .onChange(of: behavior) { _, newValue in
                mock.approvalBehavior = newValue
            }

            VStack(alignment: .leading) {
                Text("Demora para apertar: \(Int(delay)) s")
                    .font(.caption)
                Slider(value: $delay, in: 0...10, step: 1)
                    .onChange(of: delay) { _, newValue in
                        mock.buttonPressDelay = newValue
                    }
            }

            Button("Revogar todos os dispositivos") {
                mock.revokeAllDevices()
            }

            Button("Zerar a fechadura simulada", role: .destructive) {
                mock.resetLock()
                for credential in manager.credentials {
                    manager.removeCredential(lockId: credential.lockId)
                }
            }
        } header: {
            Text("Simulação")
        } footer: {
            Text("Revogar mantém a credencial no iPhone mas a fechadura passa a recusá-la — é assim que se testa o cenário de dispositivo revogado.")
        }
    }
}
