package com.example.locker.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.locker.domain.DiscoveredLock
import com.example.locker.domain.LockError
import com.example.locker.domain.LockIdentity
import com.example.locker.protocol.AccessRequestMessage
import com.example.locker.protocol.ApprovalState
import com.example.locker.protocol.ApprovalStatusMessage
import com.example.locker.protocol.AuthResponseMessage
import com.example.locker.protocol.ChallengeMessage
import com.example.locker.protocol.DeviceInfoMessage
import com.example.locker.protocol.LockCodec
import com.example.locker.protocol.LockGatt
import com.example.locker.protocol.OperationResultMessage
import com.example.locker.protocol.UnlockCommandMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Central BLE que conversa com o serviço exposto pela Raspberry Pi.
 *
 * Espelha `ios/SmartLock/Bluetooth/BLELockTransport.swift`. Duas diferenças
 * impostas pela plataforma:
 *
 * * Os callbacks do [BluetoothGattCallback] chegam em threads de binder, não na
 *   main thread. Todo o estado mutável fica atrás de [stateLock].
 * * A pilha GATT do Android aceita **uma** operação por vez; [gattMutex]
 *   serializa leitura, escrita e assinatura. O Core Bluetooth enfileira sozinho.
 */
@SuppressLint("MissingPermission") // As permissões são conferidas em `requirePermissions`.
class BleLockTransport(context: Context) : LockTransport {

    private val appContext = context.applicationContext

    private val _discovered = MutableStateFlow<List<DiscoveredLock>>(emptyList())
    override val discovered: StateFlow<List<DiscoveredLock>> = _discovered.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _unexpectedDisconnects = MutableSharedFlow<LockError>(extraBufferCapacity = 8)
    override val unexpectedDisconnects: SharedFlow<LockError> =
        _unexpectedDisconnects.asSharedFlow()

    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter

    /** Serializa as operações GATT: a pilha do Android só aceita uma por vez. */
    private val gattMutex = Mutex()

    private val stateLock = Any()

    // Estado protegido por `stateLock`.
    private val advertised = mutableMapOf<String, Pair<BluetoothDevice, DiscoveredLock>>()
    private var gatt: BluetoothGatt? = null
    private val characteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()
    private var negotiatedMtu = DEFAULT_ATT_MTU

    private var connectOp: CompletableDeferred<Unit>? = null
    private var discoveryOp: CompletableDeferred<Unit>? = null
    private var mtuOp: CompletableDeferred<Int>? = null
    private val readOps = mutableMapOf<UUID, CompletableDeferred<ByteArray>>()
    private val writeOps = mutableMapOf<UUID, CompletableDeferred<Unit>>()
    private val notifyStateOps = mutableMapOf<UUID, CompletableDeferred<Unit>>()
    private val notificationOps = mutableMapOf<UUID, CompletableDeferred<ByteArray>>()

    /** Notificações que chegaram antes de alguém aguardá-las. */
    private val bufferedNotifications = mutableMapOf<UUID, ByteArray>()

    // ----------------------------------------------------------------- //
    // Descoberta
    // ----------------------------------------------------------------- //

    override fun startScan() {
        requirePermission(scanPermission(), "procurar dispositivos Bluetooth")
        val scanner = requireEnabledAdapter().bluetoothLeScanner
            ?: throw LockError.BluetoothUnavailable("O Bluetooth está indisponível no momento.")

        synchronized(stateLock) { advertised.clear() }
        _discovered.value = emptyList()

        // A Raspberry anuncia o UUID do serviço; filtrar no rádio economiza
        // bateria e mantém a lista só com fechaduras.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(LockGatt.service)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)
        _isScanning.value = true
    }

    override fun stopScan() {
        if (!_isScanning.value) return
        _isScanning.value = false
        // Sem permissão ou com o rádio desligado não há varredura para encerrar.
        val scanner = runCatching { adapter?.bluetoothLeScanner }.getOrNull() ?: return
        runCatching { scanner.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName
                ?: runCatching { device.name }.getOrNull()
                ?: "Fechadura"
            val lock = DiscoveredLock(
                address = device.address,
                advertisedName = name,
                rssi = result.rssi,
            )
            synchronized(stateLock) { advertised[device.address] = device to lock }
            publishDiscovery()
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Falha na varredura BLE: código $errorCode")
            _isScanning.value = false
            _unexpectedDisconnects.tryEmit(
                LockError.Transport("Não foi possível procurar fechaduras (código $errorCode).")
            )
        }
    }

    private fun publishDiscovery() {
        _discovered.value = synchronized(stateLock) {
            advertised.values.map { it.second }
        }.sortedByDescending { it.rssi }
    }

    // ----------------------------------------------------------------- //
    // Conexão
    // ----------------------------------------------------------------- //

    override suspend fun connect(lock: DiscoveredLock): LockIdentity {
        requirePermission(connectPermission(), "conectar a dispositivos Bluetooth")
        requireEnabledAdapter()

        val device = synchronized(stateLock) { advertised[lock.address]?.first }
            ?: throw LockError.Transport(
                "A fechadura não está mais anunciando. Procure novamente."
            )
        stopScan()

        // Uma conexão anterior deixaria callbacks órfãos chegando neste mesmo objeto.
        closeGatt()

        await(
            timeoutMillis = CONNECT_TIMEOUT_MS,
            register = { connectOp = it },
            unregister = { if (connectOp === it) connectOp = null },
        ) {
            val handle = device.connectGatt(
                appContext,
                /* autoConnect = */ false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            ) ?: throw LockError.Transport("Não foi possível iniciar a conexão com a fechadura.")
            synchronized(stateLock) { gatt = handle }
        }

        val handle = requireGatt()

        // O `Approval Status` aprovado tem ~140 bytes: não cabe no MTU ATT
        // padrão de 23. O iOS negocia 185 sozinho; no Android é preciso pedir.
        negotiateMtu(handle)

        await(
            timeoutMillis = DISCOVERY_TIMEOUT_MS,
            register = { discoveryOp = it },
            unregister = { if (discoveryOp === it) discoveryOp = null },
        ) {
            if (!handle.discoverServices()) {
                throw LockError.Transport("Não foi possível descobrir os serviços da fechadura.")
            }
        }

        // Assinar antes de qualquer escrita: a Raspberry pode notificar o
        // resultado imediatamente após receber o comando.
        for (uuid in LockGatt.notifying) {
            enableNotifications(handle, uuid)
        }

        val payload = read(LockGatt.deviceInfo, READ_TIMEOUT_MS)
        val info = LockCodec.decode<DeviceInfoMessage>(payload)
        return LockIdentity(
            lockId = info.lockId,
            lockName = info.lockName,
            firmware = info.firmware,
        )
    }

    override fun disconnect() {
        closeGatt()
        failPendingOperations(LockError.Cancelled)
    }

    private fun closeGatt() {
        val handle = synchronized(stateLock) {
            val current = gatt
            gatt = null
            characteristics.clear()
            bufferedNotifications.clear()
            negotiatedMtu = DEFAULT_ATT_MTU
            current
        }
        handle?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
    }

    /**
     * Um MTU pequeno demais trunca o `Approval Status` em silêncio; a falha
     * aparece depois como JSON inválido. Pedir mais é um pedido, não uma
     * garantia — se a fechadura recusar, seguimos com o que houver e o
     * [write] recusa mensagens que não couberem.
     */
    private suspend fun negotiateMtu(handle: BluetoothGatt) {
        try {
            val mtu = await(
                timeoutMillis = MTU_TIMEOUT_MS,
                register = { mtuOp = it },
                unregister = { if (mtuOp === it) mtuOp = null },
            ) {
                if (!handle.requestMtu(PREFERRED_MTU)) {
                    throw LockError.Transport("A fechadura recusou o pedido de MTU.")
                }
            }
            synchronized(stateLock) { negotiatedMtu = mtu }
        } catch (error: LockError) {
            Log.w(TAG, "MTU não negociado, seguindo com $DEFAULT_ATT_MTU: ${error.message}")
        }
    }

    private suspend fun enableNotifications(handle: BluetoothGatt, uuid: UUID) {
        val characteristic = synchronized(stateLock) { characteristics[uuid] }
            ?: throw LockError.CharacteristicMissing(LockGatt.nameOf(uuid))

        gattMutex.withLock {
            await(
                timeoutMillis = NOTIFY_TIMEOUT_MS,
                register = { notifyStateOps[uuid] = it },
                unregister = { if (notifyStateOps[uuid] === it) notifyStateOps.remove(uuid) },
            ) {
                if (!handle.setCharacteristicNotification(characteristic, true)) {
                    throw LockError.Transport(
                        "Não foi possível assinar ${LockGatt.nameOf(uuid)}."
                    )
                }
                val descriptor = characteristic.getDescriptor(LockGatt.clientCharacteristicConfig)
                    ?: throw LockError.Transport(
                        "A característica ${LockGatt.nameOf(uuid)} não tem descritor de notificação."
                    )
                writeCccd(handle, descriptor)
            }
        }
    }

    /** Antes da API 33 o valor ia no próprio descritor, não na chamada. */
    @Suppress("DEPRECATION")
    private fun writeCccd(handle: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            handle.writeDescriptor(descriptor, value) == BLUETOOTH_STATUS_SUCCESS
        } else {
            descriptor.value = value
            handle.writeDescriptor(descriptor)
        }
        if (!ok) throw LockError.Transport("Não foi possível ativar as notificações.")
    }

    // ----------------------------------------------------------------- //
    // Protocolo
    // ----------------------------------------------------------------- //

    override suspend fun requestAccess(
        request: AccessRequestMessage,
        timeoutMillis: Long,
    ): ApprovalStatusMessage {
        synchronized(stateLock) { bufferedNotifications.remove(LockGatt.approvalStatus) }
        write(LockCodec.encode(request), LockGatt.accessRequest, WRITE_TIMEOUT_MS)

        // A Raspberry pode mandar `pending` assim que acende o LED; só o desfecho
        // do botão físico encerra a espera.
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw LockError.Timeout

            val payload = notification(LockGatt.approvalStatus, remaining)
            val status = LockCodec.decode<ApprovalStatusMessage>(payload)

            // O BlueZ difunde a notificação para todas as centrais inscritas;
            // o que não é deste aparelho é descartado (ver protocol/messages.md).
            if (status.deviceId != request.deviceId) continue
            if (status.state != ApprovalState.PENDING) return status
        }
    }

    override suspend fun readChallenge(): ChallengeMessage =
        LockCodec.decode(read(LockGatt.authChallenge, READ_TIMEOUT_MS))

    override suspend fun sendAuthResponse(response: AuthResponseMessage) {
        synchronized(stateLock) { bufferedNotifications.remove(LockGatt.operationResult) }
        write(LockCodec.encode(response), LockGatt.authResponse, WRITE_TIMEOUT_MS)
    }

    override suspend fun sendUnlockCommand(command: UnlockCommandMessage) {
        write(LockCodec.encode(command), LockGatt.unlockCommand, WRITE_TIMEOUT_MS)
    }

    override suspend fun awaitOperationResult(timeoutMillis: Long): OperationResultMessage =
        LockCodec.decode(notification(LockGatt.operationResult, timeoutMillis))

    // ----------------------------------------------------------------- //
    // Primitivas GATT
    // ----------------------------------------------------------------- //

    private suspend fun read(uuid: UUID, timeoutMillis: Long): ByteArray {
        val (handle, characteristic) = requireCharacteristic(uuid)
        return gattMutex.withLock {
            await(
                timeoutMillis = timeoutMillis,
                register = { readOps[uuid] = it },
                unregister = { if (readOps[uuid] === it) readOps.remove(uuid) },
            ) {
                if (!handle.readCharacteristic(characteristic)) {
                    throw LockError.Transport("Não foi possível ler ${LockGatt.nameOf(uuid)}.")
                }
            }
        }
    }

    private suspend fun write(data: ByteArray, uuid: UUID, timeoutMillis: Long) {
        val (handle, characteristic) = requireCharacteristic(uuid)

        // Três bytes de cabeçalho ATT. Melhor recusar do que deixar a fechadura
        // receber a mensagem truncada.
        val maxPayload = synchronized(stateLock) { negotiatedMtu } - ATT_HEADER_SIZE
        if (data.size > maxPayload) {
            throw LockError.Transport("Mensagem maior que o MTU negociado com a fechadura.")
        }

        gattMutex.withLock {
            await(
                timeoutMillis = timeoutMillis,
                register = { writeOps[uuid] = it },
                unregister = { if (writeOps[uuid] === it) writeOps.remove(uuid) },
            ) {
                if (!writeCompat(handle, characteristic, data)) {
                    throw LockError.Transport("Não foi possível escrever em ${LockGatt.nameOf(uuid)}.")
                }
            }
        }
    }

    /** Antes da API 33 o valor ia na própria característica, não na chamada. */
    @Suppress("DEPRECATION")
    private fun writeCompat(
        handle: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            handle.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BLUETOOTH_STATUS_SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = data
            handle.writeCharacteristic(characteristic)
        }

    private suspend fun notification(uuid: UUID, timeoutMillis: Long): ByteArray {
        synchronized(stateLock) { bufferedNotifications.remove(uuid) }?.let { return it }
        if (synchronized(stateLock) { gatt } == null) throw LockError.NotConnected

        return await(
            timeoutMillis = timeoutMillis,
            register = { notificationOps[uuid] = it },
            unregister = { if (notificationOps[uuid] === it) notificationOps.remove(uuid) },
        ) {
            // Nada a disparar: a fechadura notifica por conta própria.
        }
    }

    private fun requireGatt(): BluetoothGatt =
        synchronized(stateLock) { gatt } ?: throw LockError.NotConnected

    private fun requireCharacteristic(uuid: UUID): Pair<BluetoothGatt, BluetoothGattCharacteristic> =
        synchronized(stateLock) {
            val handle = gatt ?: throw LockError.NotConnected
            val characteristic = characteristics[uuid]
                ?: throw LockError.CharacteristicMissing(LockGatt.nameOf(uuid))
            handle to characteristic
        }

    // ----------------------------------------------------------------- //
    // Estado do rádio e permissões
    // ----------------------------------------------------------------- //

    private fun requireEnabledAdapter(): BluetoothAdapter {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            throw LockError.BluetoothUnavailable("Este aparelho não tem Bluetooth Low Energy.")
        }
        val adapter = adapter
            ?: throw LockError.BluetoothUnavailable("Este aparelho não tem Bluetooth.")
        if (!adapter.isEnabled) {
            throw LockError.BluetoothUnavailable(
                "O Bluetooth está desligado. Ligue nas Configurações."
            )
        }
        return adapter
    }

    private fun requirePermission(permission: String, action: String) {
        if (permission.isEmpty()) return
        val granted = ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw LockError.BluetoothUnavailable(
                "O app não tem permissão para $action. Conceda em Configurações > Apps > Locker."
            )
        }
    }

    /** Antes do Android 12 a varredura BLE exigia permissão de localização. */
    private fun scanPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    /**
     * `BLUETOOTH_CONNECT` só existe a partir do Android 12. Abaixo disso o
     * `BLUETOOTH` do manifesto já vem concedido na instalação, e pedir a
     * permissão nova daria negado sempre.
     */
    private fun connectPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            ""
        }

    // ----------------------------------------------------------------- //
    // Infraestrutura de espera
    // ----------------------------------------------------------------- //

    /**
     * Ponte entre os callbacks do GATT e as corrotinas.
     *
     * O slot é registrado **antes** de [start] disparar a operação: o callback
     * pode chegar antes de a chamada retornar.
     */
    private suspend fun <T> await(
        timeoutMillis: Long,
        register: (CompletableDeferred<T>) -> Unit,
        unregister: (CompletableDeferred<T>) -> Unit,
        start: () -> Unit,
    ): T {
        val deferred = CompletableDeferred<T>()
        synchronized(stateLock) { register(deferred) }

        try {
            start()
        } catch (error: Throwable) {
            synchronized(stateLock) { unregister(deferred) }
            throw error
        }

        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            throw LockError.Timeout
        } finally {
            synchronized(stateLock) { unregister(deferred) }
        }
    }

    private fun failPendingOperations(error: LockError) {
        val pending = synchronized(stateLock) {
            val all = mutableListOf<CompletableDeferred<*>>()
            connectOp?.let { all.add(it) }
            discoveryOp?.let { all.add(it) }
            mtuOp?.let { all.add(it) }
            all.addAll(readOps.values)
            all.addAll(writeOps.values)
            all.addAll(notifyStateOps.values)
            all.addAll(notificationOps.values)
            connectOp = null
            discoveryOp = null
            mtuOp = null
            readOps.clear()
            writeOps.clear()
            notifyStateOps.clear()
            notificationOps.clear()
            all
        }
        pending.forEach { it.completeExceptionally(error) }
    }

    // ----------------------------------------------------------------- //
    // Callbacks GATT
    // ----------------------------------------------------------------- //

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                val op = synchronized(stateLock) { connectOp.also { connectOp = null } }
                op?.complete(Unit)
                return
            }

            if (newState != BluetoothProfile.STATE_DISCONNECTED) return

            val (isCurrent, wasConnecting) = synchronized(stateLock) {
                (this@BleLockTransport.gatt === gatt) to (connectOp != null)
            }

            // Uma conexão anterior já substituída ainda entrega este callback;
            // deixá-lo passar derrubaria a conexão nova.
            if (!isCurrent && !wasConnecting) {
                runCatching { gatt.close() }
                return
            }

            val error = LockError.Transport(
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    "A conexão com a fechadura foi encerrada."
                } else {
                    "A conexão com a fechadura caiu (código $status)."
                }
            )

            closeGatt()
            failPendingOperations(error)

            // Quedas durante o `connect()` já viram exceção na própria chamada;
            // desconexão pedida pelo app passou por `disconnect()`.
            if (isCurrent && !wasConnecting && status != BluetoothGatt.GATT_SUCCESS) {
                _unexpectedDisconnects.tryEmit(error)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val op = synchronized(stateLock) { mtuOp.also { mtuOp = null } }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                op?.complete(mtu)
            } else {
                op?.completeExceptionally(
                    LockError.Transport("A fechadura recusou o MTU (código $status).")
                )
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val op = synchronized(stateLock) { discoveryOp.also { discoveryOp = null } }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                op?.completeExceptionally(
                    LockError.Transport("Falha ao descobrir os serviços (código $status).")
                )
                return
            }

            val service = gatt.getService(LockGatt.service)
            if (service == null) {
                op?.completeExceptionally(
                    LockError.Transport("Serviço Smart Lock não encontrado neste dispositivo.")
                )
                return
            }

            synchronized(stateLock) {
                characteristics.clear()
                service.characteristics.forEach { characteristics[it.uuid] = it }
            }
            op?.complete(Unit)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val uuid = descriptor.characteristic.uuid
            val op = synchronized(stateLock) { notifyStateOps.remove(uuid) }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                op?.complete(Unit)
            } else {
                op?.completeExceptionally(
                    LockError.Transport(
                        "Não foi possível assinar ${LockGatt.nameOf(uuid)} (código $status)."
                    )
                )
            }
        }

        // A partir da API 33 o valor vem por parâmetro; abaixo dela, em
        // `characteristic.value`. O framework chama só uma das duas.
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = completeRead(characteristic.uuid, value, status)

        @Deprecated("Mantido para Android 12 e anteriores.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) = completeRead(characteristic.uuid, characteristic.value ?: ByteArray(0), status)

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val uuid = characteristic.uuid
            val op = synchronized(stateLock) { writeOps.remove(uuid) }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                op?.complete(Unit)
            } else {
                op?.completeExceptionally(
                    LockError.Transport(
                        "A fechadura recusou a escrita em ${LockGatt.nameOf(uuid)} (código $status)."
                    )
                )
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = deliverNotification(characteristic.uuid, value)

        @Deprecated("Mantido para Android 12 e anteriores.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) = deliverNotification(characteristic.uuid, characteristic.value ?: ByteArray(0))
    }

    private fun completeRead(uuid: UUID, value: ByteArray, status: Int) {
        val op = synchronized(stateLock) { readOps.remove(uuid) }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            op?.complete(value)
        } else {
            op?.completeExceptionally(
                LockError.Transport(
                    "Não foi possível ler ${LockGatt.nameOf(uuid)} (código $status)."
                )
            )
        }
    }

    /** Guarda a notificação se ninguém estiver esperando por ela ainda. */
    private fun deliverNotification(uuid: UUID, value: ByteArray) {
        val op: CompletableDeferred<ByteArray>? = synchronized(stateLock) {
            val waiting = notificationOps.remove(uuid)
            if (waiting == null) bufferedNotifications[uuid] = value
            waiting
        }
        op?.complete(value)
    }

    private companion object {
        const val TAG = "BleLockTransport"

        /** MTU mínimo do ATT; não cabe o `Approval Status` aprovado. */
        const val DEFAULT_ATT_MTU = 23
        const val ATT_HEADER_SIZE = 3

        /** O mesmo que o iOS negocia, com folga para o segredo em Base64. */
        const val PREFERRED_MTU = 185

        /** `BluetoothStatusCodes.SUCCESS`, que só existe a partir da API 33. */
        const val BLUETOOTH_STATUS_SUCCESS = 0

        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DISCOVERY_TIMEOUT_MS = 15_000L
        const val MTU_TIMEOUT_MS = 5_000L
        const val NOTIFY_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 10_000L
        const val WRITE_TIMEOUT_MS = 10_000L
    }
}
