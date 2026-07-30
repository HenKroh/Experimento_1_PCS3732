package com.example.locker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.locker.domain.ConnectionState
import com.example.locker.domain.DiscoveredLock
import com.example.locker.domain.EnrollmentState
import com.example.locker.domain.LockCredential
import com.example.locker.domain.LockManager
import com.example.locker.domain.UnlockState

/**
 * Cadastro e desbloqueio de uma fechadura.
 *
 * Espelha `ios/SmartLock/Views/LockDetailView.swift`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockDetailScreen(
    lock: DiscoveredLock,
    manager: LockManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection by manager.connection.collectAsStateWithLifecycle()
    val enrollment by manager.enrollment.collectAsStateWithLifecycle()
    val unlock by manager.unlock.collectAsStateWithLifecycle()
    val credentials by manager.credentials.collectAsStateWithLifecycle()

    val credential = connection.identity?.let { identity ->
        credentials.firstOrNull { it.lockId == identity.lockId }
    }

    var deviceName by rememberSaveable { mutableStateOf(android.os.Build.MODEL ?: "Android") }
    var showRemoveConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(lock.advertisedName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("Conexão")
            ConnectionRow(connection)
            if (connection is ConnectionState.Failed) {
                OutlinedButton(onClick = { manager.select(lock) }) {
                    Text("Tentar novamente")
                }
            }

            if (connection.isConnected) {
                HorizontalDivider()

                if (credential == null) {
                    EnrollmentSection(
                        deviceName = deviceName,
                        onDeviceNameChange = { deviceName = it },
                        state = enrollment,
                        onRequest = { manager.requestAccess(deviceName.trim()) },
                    )
                } else {
                    UnlockSection(
                        state = unlock,
                        onUnlock = { manager.unlockDoor() },
                    )
                    HorizontalDivider()
                    CredentialSection(
                        credential = credential,
                        firmware = connection.identity?.firmware,
                        onRemove = { showRemoveConfirmation = true },
                    )
                }
            }
        }
    }

    if (showRemoveConfirmation) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirmation = false },
            title = { Text("Remover a credencial deste aparelho?") },
            text = {
                Text(
                    "Você precisará de uma nova aprovação pelo botão físico para " +
                        "voltar a abrir esta fechadura."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirmation = false
                    connection.identity?.let { manager.removeCredential(it.lockId) }
                }) {
                    Text("Remover", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirmation = false }) { Text("Cancelar") }
            },
        )
    }
}

// ------------------------------------------------------------------------- //
// Cadastro
// ------------------------------------------------------------------------- //

@Composable
private fun EnrollmentSection(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    state: EnrollmentState,
    onRequest: () -> Unit,
) {
    SectionHeader("Cadastro")

    OutlinedTextField(
        value = deviceName,
        onValueChange = onDeviceNameChange,
        label = { Text("Nome deste aparelho") },
        singleLine = true,
        enabled = !state.isBusy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = onRequest,
        enabled = !state.isBusy && deviceName.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Solicitar acesso")
    }

    EnrollmentStatusRow(state)

    Text(
        "Depois de solicitar, alguém precisa apertar o botão Permitir na fechadura. " +
            "O segredo enviado é guardado pelo Android Keystore e nunca sai deste aparelho.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EnrollmentStatusRow(state: EnrollmentState) {
    when (state) {
        EnrollmentState.Idle -> Unit
        EnrollmentState.Requesting -> BusyRow("Enviando solicitação…")
        is EnrollmentState.AwaitingApproval -> BusyRow("Aguardando o botão físico…")
        EnrollmentState.Approved -> StatusRow(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "Acesso aprovado",
            "Credencial salva no Keystore.",
        )

        EnrollmentState.Denied -> StatusRow(
            Icons.Default.Clear,
            MaterialTheme.colorScheme.error,
            "Acesso negado",
            "O botão Negar foi pressionado.",
        )

        EnrollmentState.TimedOut -> StatusRow(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.tertiary,
            "Sem resposta",
            "Ninguém decidiu a tempo. Tente de novo.",
        )

        is EnrollmentState.Failed -> StatusRow(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "Falha no cadastro",
            state.message,
        )
    }
}

// ------------------------------------------------------------------------- //
// Desbloqueio
// ------------------------------------------------------------------------- //

@Composable
private fun UnlockSection(state: UnlockState, onUnlock: () -> Unit) {
    SectionHeader("Fechadura")

    Button(
        onClick = onUnlock,
        enabled = !state.isBusy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Text("Desbloquear", fontWeight = FontWeight.SemiBold)
        }
    }

    when (state) {
        UnlockState.Idle -> Unit
        UnlockState.Authenticating -> BusyRow("Respondendo ao desafio…")
        UnlockState.Unlocking -> BusyRow("Acionando a fechadura…")
        is UnlockState.Unlocked -> StatusRow(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "Porta destravada",
            formatTime(state.at),
        )

        is UnlockState.Failed -> StatusRow(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "Não foi possível abrir",
            state.message,
        )
    }

    Text(
        "O app responde a um desafio aleatório da fechadura. " +
            "Nenhuma chave reutilizável trafega pelo ar.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
    )
}

// ------------------------------------------------------------------------- //
// Credencial
// ------------------------------------------------------------------------- //

@Composable
private fun CredentialSection(
    credential: LockCredential,
    firmware: String?,
    onRemove: () -> Unit,
) {
    SectionHeader("Credencial")
    LabeledValue("Fechadura", credential.lockName)
    LabeledValue("ID deste aparelho", credential.deviceId.take(8) + "…")
    LabeledValue("Cadastrada em", formatDateTime(credential.createdAt))
    if (firmware != null) LabeledValue("Firmware", firmware)

    OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
        Text("Remover credencial deste aparelho", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------------- //
// Conexão
// ------------------------------------------------------------------------- //

@Composable
private fun ConnectionRow(state: ConnectionState) {
    when (state) {
        ConnectionState.Disconnected -> StatusRow(
            Icons.Default.Info,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Desconectado",
        )

        ConnectionState.Connecting -> BusyRow("Conectando…")

        is ConnectionState.Connected -> StatusRow(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary,
            state.identity.lockName,
            state.identity.lockId,
        )

        is ConnectionState.Failed -> StatusRow(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "Falha na conexão",
            state.message,
        )
    }
}
