package com.example.locker.domain

import com.example.locker.ble.FakeLockTransport
import com.example.locker.crypto.LockCrypto
import com.example.locker.protocol.ApprovalState
import com.example.locker.protocol.ApprovalStatusMessage
import com.example.locker.protocol.LockProtocol
import com.example.locker.protocol.OperationResultMessage
import com.example.locker.protocol.OperationStatus
import com.example.locker.storage.InMemoryCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercita os fluxos de `protocol/state-machine.md` sem rádio BLE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockManagerTest {

    private lateinit var transport: FakeLockTransport
    private lateinit var store: InMemoryCredentialStore
    private lateinit var manager: LockManager

    private val lock = DiscoveredLock("AA:BB:CC:DD:EE:FF", "SmartLock-Sala", -50)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        transport = FakeLockTransport()
        store = InMemoryCredentialStore()
        manager = LockManager(transport, store)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun connect() {
        manager.select(lock)
        assertTrue(manager.connection.value.isConnected)
    }

    // ------------------------------------------------------------------ //
    // Cadastro
    // ------------------------------------------------------------------ //

    @Test
    fun `cadastro aprovado guarda a credencial`() = runTest {
        connect()

        manager.requestAccess("Pixel de teste")

        assertEquals(EnrollmentState.Approved, manager.enrollment.value)
        val credential = store.credential("lock-01")
        assertNotNull(credential)
        assertEquals("Fechadura da Sala", credential!!.lockName)
        assertEquals(LockProtocol.SECRET_LENGTH, credential.secret.size)
        assertEquals("Pixel de teste", transport.lastRequest?.deviceName)
        assertEquals(credential.deviceId, transport.lastRequest?.deviceId)
    }

    @Test
    fun `recadastro reaproveita o deviceId`() = runTest {
        connect()
        manager.requestAccess("Pixel")
        val first = store.credential("lock-01")!!.deviceId

        manager.requestAccess("Pixel")
        val second = store.credential("lock-01")!!.deviceId

        // A Raspberry substitui o registro antigo em vez de acumular linhas órfãs.
        assertEquals(first, second)
    }

    @Test
    fun `botao Negar deixa o app sem credencial`() = runTest {
        transport.approvalResponse = { request ->
            ApprovalStatusMessage(
                v = LockProtocol.VERSION,
                state = ApprovalState.DENIED,
                deviceId = request.deviceId,
            )
        }
        connect()

        manager.requestAccess("Pixel")

        assertEquals(EnrollmentState.Denied, manager.enrollment.value)
        assertNull(store.credential("lock-01"))
    }

    @Test
    fun `sem resposta a tempo vira timeout`() = runTest {
        transport.approvalResponse = { request ->
            ApprovalStatusMessage(
                v = LockProtocol.VERSION,
                state = ApprovalState.TIMEOUT,
                deviceId = request.deviceId,
            )
        }
        connect()

        manager.requestAccess("Pixel")

        assertEquals(EnrollmentState.TimedOut, manager.enrollment.value)
        assertNull(store.credential("lock-01"))
    }

    @Test
    fun `segredo de tamanho errado e recusado`() = runTest {
        transport.approvalResponse = { request ->
            ApprovalStatusMessage(
                v = LockProtocol.VERSION,
                state = ApprovalState.APPROVED,
                deviceId = request.deviceId,
                secret = ByteArray(16),
            )
        }
        connect()

        manager.requestAccess("Pixel")

        assertTrue(manager.enrollment.value is EnrollmentState.Failed)
        assertNull(store.credential("lock-01"))
    }

    // ------------------------------------------------------------------ //
    // Desbloqueio
    // ------------------------------------------------------------------ //

    @Test
    fun `desbloqueio envia o MAC do nonce recebido`() = runTest {
        connect()
        manager.requestAccess("Pixel")
        val credential = store.credential("lock-01")!!

        manager.unlockDoor()

        assertTrue(manager.unlock.value is UnlockState.Unlocked)
        assertEquals(1, transport.challengeReads)

        val expected = LockCrypto.response(
            secret = credential.secret,
            nonce = transport.challenge.nonce,
            deviceId = credential.deviceId,
        )
        assertArrayEquals(expected, transport.lastAuthResponse?.mac)
        assertEquals(credential.deviceId, transport.lastUnlockCommand?.deviceId)
    }

    @Test
    fun `desbloqueio marca o ultimo acesso`() = runTest {
        connect()
        manager.requestAccess("Pixel")
        assertNull(store.credential("lock-01")!!.lastUsedAt)

        manager.unlockDoor()

        assertNotNull(store.credential("lock-01")!!.lastUsedAt)
    }

    @Test
    fun `autenticacao negada nao manda o comando de desbloqueio`() = runTest {
        connect()
        manager.requestAccess("Pixel")
        transport.operationResults = mutableListOf(
            OperationResultMessage(
                LockProtocol.VERSION,
                "auth",
                OperationStatus.DENIED,
                "Prova criptográfica inválida.",
            )
        )

        manager.unlockDoor()

        val state = manager.unlock.value
        assertTrue(state is UnlockState.Failed)
        assertEquals("Prova criptográfica inválida.", (state as UnlockState.Failed).message)
        assertNull(transport.lastUnlockCommand)
    }

    @Test
    fun `bloqueio temporario aparece com o texto certo`() = runTest {
        connect()
        manager.requestAccess("Pixel")
        transport.operationResults = mutableListOf(
            OperationResultMessage(
                LockProtocol.VERSION,
                "auth",
                OperationStatus.RATE_LIMITED,
                "Bloqueado temporariamente.",
            )
        )

        manager.unlockDoor()

        val state = manager.unlock.value as UnlockState.Failed
        assertEquals(LockError.RateLimited.message, state.message)
        assertNull(transport.lastUnlockCommand)
    }

    @Test
    fun `desbloqueio sem credencial falha antes de tocar no radio`() = runTest {
        connect()

        manager.unlockDoor()

        assertTrue(manager.unlock.value is UnlockState.Failed)
        assertEquals(0, transport.challengeReads)
    }

    @Test
    fun `atuador recusado depois da autenticacao e reportado`() = runTest {
        connect()
        manager.requestAccess("Pixel")
        transport.operationResults = mutableListOf(
            OperationResultMessage(LockProtocol.VERSION, "auth", OperationStatus.OK, null),
            OperationResultMessage(
                LockProtocol.VERSION,
                "unlock",
                OperationStatus.ERROR,
                "Atuador indisponível.",
            ),
        )

        manager.unlockDoor()

        val state = manager.unlock.value as UnlockState.Failed
        assertEquals("Atuador indisponível.", state.message)
    }

    // ------------------------------------------------------------------ //
    // Credenciais e conexão
    // ------------------------------------------------------------------ //

    @Test
    fun `remover a credencial exige novo cadastro`() = runTest {
        connect()
        manager.requestAccess("Pixel")

        manager.removeCredential("lock-01")

        assertNull(store.credential("lock-01"))
        assertTrue(manager.credentials.value.isEmpty())
        assertEquals(EnrollmentState.Idle, manager.enrollment.value)
    }

    @Test
    fun `falha de conexao vira estado de falha`() = runTest {
        transport.connectError = LockError.Transport("Fechadura fora de alcance.")

        manager.select(lock)

        val state = manager.connection.value
        assertTrue(state is ConnectionState.Failed)
        assertEquals("Fechadura fora de alcance.", (state as ConnectionState.Failed).message)
    }

    @Test
    fun `queda de conexao vira estado de falha e alerta`() = runTest {
        connect()

        transport.emitUnexpectedDisconnect(LockError.Transport("A conexão caiu."))

        assertTrue(manager.connection.value is ConnectionState.Failed)
        assertEquals("A conexão caiu.", manager.alertMessage.value)
    }

    @Test
    fun `credenciais salvas sao recarregadas na criacao`() = runTest {
        val seeded = LockCredential(
            lockId = "lock-01",
            lockName = "Fechadura da Sala",
            deviceId = "abc",
            secret = ByteArray(32),
            createdAt = 1_000L,
        )

        val other = LockManager(FakeLockTransport(), InMemoryCredentialStore(listOf(seeded)))

        assertEquals(listOf(seeded), other.credentials.value)
    }
}
