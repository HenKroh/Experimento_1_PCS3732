package com.example.locker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.locker.domain.DiscoveredLock
import com.example.locker.domain.LockManager

/**
 * Lista de fechaduras por perto e credenciais já salvas.
 *
 * Espelha `ios/SmartLock/Views/DiscoveryView.swift`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    manager: LockManager,
    onSelect: (DiscoveredLock) -> Unit,
    onToggleScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val discovered by manager.discovered.collectAsStateWithLifecycle()
    val isScanning by manager.isScanning.collectAsStateWithLifecycle()
    val credentials by manager.credentials.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Fechaduras") },
                actions = {
                    TextButton(onClick = onToggleScan) {
                        Text(if (isScanning) "Parar" else "Procurar")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item {
                SectionHeader("Por perto", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            if (discovered.isEmpty()) {
                item {
                    val text = if (isScanning) {
                        "Procurando fechaduras…"
                    } else {
                        "Nenhuma fechadura encontrada."
                    }
                    if (isScanning) {
                        BusyRow(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    } else {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            items(discovered, key = { it.address }) { lock ->
                LockRow(
                    lock = lock,
                    // O anúncio só traz o nome; o `lockId` real vem depois de
                    // conectar. O casamento por nome é dica visual, não garantia.
                    isPaired = credentials.any { it.lockName == lock.advertisedName },
                    onClick = { onSelect(lock) },
                )
            }

            if (credentials.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SectionHeader(
                        "Credenciais salvas",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                items(credentials, key = { it.lockId }) { credential ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(credential.lockName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                credential.lastUsedAt
                                    ?.let { "Último acesso: ${formatDateTime(it)}" }
                                    ?: "Cadastrada em ${formatDateTime(credential.createdAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { manager.removeCredential(credential.lockId) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remover credencial de ${credential.lockName}",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LockRow(
    lock: DiscoveredLock,
    isPaired: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = if (isPaired) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(Modifier.weight(1f)) {
            Text(
                lock.advertisedName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${lock.signalDescription} · ${lock.rssi} dBm" +
                    if (isPaired) " · já cadastrada" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
