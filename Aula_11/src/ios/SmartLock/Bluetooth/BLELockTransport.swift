import CoreBluetooth
import Foundation

/// Central BLE que conversa com o serviço exposto pela Raspberry Pi.
///
/// O `CBCentralManager` é criado com `queue: .main`, então todos os callbacks
/// do Core Bluetooth chegam na main queue — não há trabalho concorrente aqui.
final class BLELockTransport: NSObject, LockTransport {
    var onDiscoveryChange: (([DiscoveredLock]) -> Void)?
    var onUnexpectedDisconnect: ((LockError) -> Void)?

    private(set) var isScanning = false

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var characteristics: [CBUUID: CBCharacteristic] = [:]
    private var advertised: [UUID: (peripheral: CBPeripheral, lock: DiscoveredLock)] = [:]

    /// O usuário pediu scan antes de o rádio ficar pronto.
    private var scanRequested = false

    private var powerOnOperation: PendingOperation<Void>?
    private var connectOperation: PendingOperation<Void>?
    private var discoveryOperation: PendingOperation<Void>?
    private var readOperations: [CBUUID: PendingOperation<Data>] = [:]
    private var writeOperations: [CBUUID: PendingOperation<Void>] = [:]
    private var notifyStateOperations: [CBUUID: PendingOperation<Void>] = [:]
    private var notificationOperations: [CBUUID: PendingOperation<Data>] = [:]
    /// Notificações que chegaram antes de alguém aguardá-las.
    private var bufferedNotifications: [CBUUID: Data] = [:]

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: - Descoberta

    func startScan() throws {
        advertised.removeAll()
        onDiscoveryChange?([])

        switch central.state {
        case .poweredOn:
            scanRequested = false
            isScanning = true
            central.scanForPeripherals(
                withServices: [LockGATT.service],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
            )
        case .unknown, .resetting:
            // O rádio ainda está inicializando; o scan começa em didUpdateState.
            scanRequested = true
        default:
            throw stateError(central.state)
        }
    }

    func stopScan() {
        scanRequested = false
        guard isScanning else { return }
        isScanning = false
        central.stopScan()
    }

    // MARK: - Conexão

    func connect(to lock: DiscoveredLock) async throws -> LockIdentity {
        try await waitForPoweredOn()

        guard let entry = advertised[lock.id] else {
            throw LockError.transport("A fechadura não está mais anunciando. Procure novamente.")
        }
        stopScan()

        let target = entry.peripheral
        target.delegate = self
        peripheral = target
        characteristics.removeAll()
        bufferedNotifications.removeAll()

        try await awaiting(timeout: 15, register: { self.connectOperation = $0 }) {
            self.central.connect(target, options: nil)
        }

        try await awaiting(timeout: 15, register: { self.discoveryOperation = $0 }) {
            target.discoverServices([LockGATT.service])
        }

        // Assinar antes de qualquer escrita: a Raspberry pode notificar o
        // resultado imediatamente após receber o comando.
        for uuid in LockGATT.notifying {
            guard let characteristic = characteristics[uuid] else {
                throw LockError.characteristicMissing(name(of: uuid))
            }
            try await awaiting(timeout: 10, register: { self.notifyStateOperations[uuid] = $0 }) {
                target.setNotifyValue(true, for: characteristic)
            }
        }

        let payload = try await read(LockGATT.deviceInfo, timeout: 10)
        let info = try LockCodec.decode(DeviceInfoMessage.self, from: payload)
        return LockIdentity(lockId: info.lockId, lockName: info.lockName, firmware: info.firmware)
    }

    func disconnect() {
        guard let peripheral else { return }
        self.peripheral = nil
        characteristics.removeAll()
        bufferedNotifications.removeAll()
        central.cancelPeripheralConnection(peripheral)
        failPendingOperations(with: .cancelled)
    }

    // MARK: - Protocolo

    func requestAccess(
        _ request: AccessRequestMessage,
        timeout: TimeInterval
    ) async throws -> ApprovalStatusMessage {
        bufferedNotifications[LockGATT.approvalStatus] = nil
        try await write(LockCodec.encode(request), to: LockGATT.accessRequest, timeout: 10)

        // A Raspberry pode mandar `pending` assim que acende o LED; só o desfecho
        // do botão físico encerra a espera.
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            let remaining = deadline.timeIntervalSinceNow
            guard remaining > 0 else { throw LockError.timeout }

            let payload = try await notification(LockGATT.approvalStatus, timeout: remaining)
            let status = try LockCodec.decode(ApprovalStatusMessage.self, from: payload)
            guard status.deviceId == request.deviceId else { continue }
            if status.state != .pending { return status }
        }
    }

    func readChallenge() async throws -> ChallengeMessage {
        let payload = try await read(LockGATT.authChallenge, timeout: 10)
        return try LockCodec.decode(ChallengeMessage.self, from: payload)
    }

    func sendAuthResponse(_ response: AuthResponseMessage) async throws {
        bufferedNotifications[LockGATT.operationResult] = nil
        try await write(LockCodec.encode(response), to: LockGATT.authResponse, timeout: 10)
    }

    func sendUnlockCommand(_ command: UnlockCommandMessage) async throws {
        try await write(LockCodec.encode(command), to: LockGATT.unlockCommand, timeout: 10)
    }

    func awaitOperationResult(timeout: TimeInterval) async throws -> OperationResultMessage {
        let payload = try await notification(LockGATT.operationResult, timeout: timeout)
        return try LockCodec.decode(OperationResultMessage.self, from: payload)
    }

    // MARK: - Primitivas GATT

    private func read(_ uuid: CBUUID, timeout: TimeInterval) async throws -> Data {
        let (peripheral, characteristic) = try requireCharacteristic(uuid)
        return try await awaiting(timeout: timeout, register: { self.readOperations[uuid] = $0 }) {
            peripheral.readValue(for: characteristic)
        }
    }

    private func write(_ data: Data, to uuid: CBUUID, timeout: TimeInterval) async throws {
        let (peripheral, characteristic) = try requireCharacteristic(uuid)
        guard data.count <= peripheral.maximumWriteValueLength(for: .withResponse) else {
            throw LockError.transport("Mensagem maior que o MTU negociado com a fechadura.")
        }
        try await awaiting(timeout: timeout, register: { self.writeOperations[uuid] = $0 }) {
            peripheral.writeValue(data, for: characteristic, type: .withResponse)
        }
    }

    private func notification(_ uuid: CBUUID, timeout: TimeInterval) async throws -> Data {
        if let buffered = bufferedNotifications.removeValue(forKey: uuid) {
            return buffered
        }
        guard peripheral != nil else { throw LockError.notConnected }
        return try await awaiting(timeout: timeout, register: { self.notificationOperations[uuid] = $0 }) {}
    }

    private func requireCharacteristic(_ uuid: CBUUID) throws -> (CBPeripheral, CBCharacteristic) {
        guard let peripheral, peripheral.state == .connected else { throw LockError.notConnected }
        guard let characteristic = characteristics[uuid] else {
            throw LockError.characteristicMissing(name(of: uuid))
        }
        return (peripheral, characteristic)
    }

    // MARK: - Estado do rádio

    private func waitForPoweredOn() async throws {
        switch central.state {
        case .poweredOn:
            return
        case .unknown, .resetting:
            try await awaiting(timeout: 5, register: { self.powerOnOperation = $0 }) {}
        default:
            throw stateError(central.state)
        }
    }

    private func stateError(_ state: CBManagerState) -> LockError {
        switch state {
        case .poweredOff:
            return .bluetoothUnavailable("O Bluetooth está desligado. Ligue nos Ajustes.")
        case .unauthorized:
            return .bluetoothUnavailable("O app não tem permissão para usar Bluetooth. Ajuste em Ajustes > SmartLock.")
        case .unsupported:
            return .bluetoothUnavailable("Este aparelho não tem Bluetooth Low Energy.")
        default:
            return .bluetoothUnavailable("Bluetooth indisponível no momento.")
        }
    }

    // MARK: - Infraestrutura async

    private func awaiting<T>(
        timeout: TimeInterval,
        register: (PendingOperation<T>) -> Void,
        start: () throws -> Void
    ) async throws -> T {
        let operation = PendingOperation<T>()
        register(operation)

        let timeoutTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            guard !Task.isCancelled else { return }
            operation.finish(throwing: LockError.timeout)
        }
        defer { timeoutTask.cancel() }

        do {
            try start()
        } catch {
            operation.finish(throwing: error)
        }

        return try await withCheckedThrowingContinuation { operation.attach($0) }
    }

    private func failPendingOperations(with error: LockError) {
        connectOperation?.finish(throwing: error)
        connectOperation = nil
        discoveryOperation?.finish(throwing: error)
        discoveryOperation = nil
        for (_, operation) in readOperations { operation.finish(throwing: error) }
        readOperations.removeAll()
        for (_, operation) in writeOperations { operation.finish(throwing: error) }
        writeOperations.removeAll()
        for (_, operation) in notifyStateOperations { operation.finish(throwing: error) }
        notifyStateOperations.removeAll()
        for (_, operation) in notificationOperations { operation.finish(throwing: error) }
        notificationOperations.removeAll()
    }

    private func name(of uuid: CBUUID) -> String {
        switch uuid {
        case LockGATT.deviceInfo: return "Device Information"
        case LockGATT.accessRequest: return "Access Request"
        case LockGATT.approvalStatus: return "Approval Status"
        case LockGATT.authChallenge: return "Authentication Challenge"
        case LockGATT.authResponse: return "Authentication Response"
        case LockGATT.unlockCommand: return "Unlock Command"
        case LockGATT.operationResult: return "Operation Result"
        default: return uuid.uuidString
        }
    }

    private func publishDiscovery() {
        let locks = advertised.values.map(\.lock).sorted { $0.rssi > $1.rssi }
        onDiscoveryChange?(locks)
    }
}

// MARK: - CBCentralManagerDelegate

extension BLELockTransport: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            powerOnOperation?.finish(.success(()))
            powerOnOperation = nil
            if scanRequested {
                try? startScan()
            }
        case .unknown, .resetting:
            break
        default:
            let error = stateError(central.state)
            scanRequested = false
            isScanning = false
            powerOnOperation?.finish(throwing: error)
            powerOnOperation = nil
            if peripheral != nil {
                peripheral = nil
                characteristics.removeAll()
                onUnexpectedDisconnect?(error)
            }
            failPendingOperations(with: error)
            advertised.removeAll()
            publishDiscovery()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?? peripheral.name
            ?? "Fechadura"
        let lock = DiscoveredLock(id: peripheral.identifier, advertisedName: name, rssi: RSSI.intValue)
        advertised[peripheral.identifier] = (peripheral, lock)
        publishDiscovery()
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectOperation?.finish(.success(()))
        connectOperation = nil
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        let failure = LockError.transport(error?.localizedDescription ?? "Não foi possível conectar à fechadura.")
        connectOperation?.finish(throwing: failure)
        connectOperation = nil
        self.peripheral = nil
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard peripheral.identifier == self.peripheral?.identifier else { return }
        self.peripheral = nil
        characteristics.removeAll()

        let failure = LockError.transport(error?.localizedDescription ?? "A conexão com a fechadura caiu.")
        failPendingOperations(with: failure)
        if error != nil {
            onUnexpectedDisconnect?(failure)
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BLELockTransport: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error {
            discoveryOperation?.finish(throwing: LockError.transport(error.localizedDescription))
            discoveryOperation = nil
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == LockGATT.service }) else {
            discoveryOperation?.finish(throwing: LockError.transport("Serviço Smart Lock não encontrado neste dispositivo."))
            discoveryOperation = nil
            return
        }
        peripheral.discoverCharacteristics(nil, for: service)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        if let error {
            discoveryOperation?.finish(throwing: LockError.transport(error.localizedDescription))
            discoveryOperation = nil
            return
        }
        for characteristic in service.characteristics ?? [] {
            characteristics[characteristic.uuid] = characteristic
        }
        discoveryOperation?.finish(.success(()))
        discoveryOperation = nil
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        let operation = notifyStateOperations.removeValue(forKey: characteristic.uuid)
        if let error {
            operation?.finish(throwing: LockError.transport(error.localizedDescription))
        } else {
            operation?.finish(.success(()))
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        let uuid = characteristic.uuid

        // O mesmo callback atende leituras e notificações; uma leitura pendente
        // sempre tem prioridade porque foi pedida explicitamente.
        if let operation = readOperations.removeValue(forKey: uuid) {
            if let error {
                operation.finish(throwing: LockError.transport(error.localizedDescription))
            } else if let value = characteristic.value {
                operation.finish(.success(value))
            } else {
                operation.finish(throwing: LockError.malformedResponse)
            }
            return
        }

        guard error == nil, let value = characteristic.value else { return }

        if let operation = notificationOperations.removeValue(forKey: uuid) {
            operation.finish(.success(value))
        } else {
            bufferedNotifications[uuid] = value
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        let operation = writeOperations.removeValue(forKey: characteristic.uuid)
        if let error {
            operation?.finish(throwing: LockError.transport(error.localizedDescription))
        } else {
            operation?.finish(.success(()))
        }
    }
}
