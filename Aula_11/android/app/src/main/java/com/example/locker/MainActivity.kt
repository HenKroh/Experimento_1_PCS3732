package com.example.locker

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.locker.ble.BleLockTransport
import com.example.locker.domain.DiscoveredLock
import com.example.locker.domain.LockManager
import com.example.locker.storage.KeystoreCredentialStore
import com.example.locker.ui.DiscoveryScreen
import com.example.locker.ui.LockDetailScreen
import com.example.locker.ui.LockerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // O transporte e o armazenamento vivem no `applicationContext`: o
        // `LockManager` é um ViewModel e sobrevive à rotação da tela.
        val factory = LockManager.factory(
            transport = BleLockTransport(applicationContext),
            store = KeystoreCredentialStore(applicationContext),
        )

        setContent {
            LockerTheme {
                SmartLockApp(factory)
            }
        }
    }
}

@Composable
private fun SmartLockApp(factory: ViewModelProvider.Factory) {
    val manager: LockManager = viewModel(factory = factory)

    var selected by remember { mutableStateOf<DiscoveredLock?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val alert by manager.alertMessage.collectAsStateWithLifecycle()

    // Sem as permissões o `BleLockTransport` recusa a operação com uma mensagem
    // explicativa; aqui só damos ao usuário a chance de concedê-las.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) manager.startScan()
    }

    LaunchedEffect(alert) {
        alert?.let {
            snackbarHostState.showSnackbar(it)
            manager.consumeAlert()
        }
    }

    LaunchedEffect(Unit) { permissionLauncher.launch(blePermissions()) }

    Box(Modifier.fillMaxSize()) {
        val current = selected
        if (current == null) {
            DiscoveryScreen(
                manager = manager,
                onSelect = { lock ->
                    selected = lock
                    manager.select(lock)
                },
                onToggleScan = {
                    if (manager.isScanning.value) {
                        manager.stopScan()
                    } else {
                        permissionLauncher.launch(blePermissions())
                    }
                },
            )
        } else {
            LockDetailScreen(
                lock = current,
                manager = manager,
                onBack = {
                    manager.disconnect()
                    selected = null
                },
            )
        }

        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * A partir do Android 12 a varredura BLE tem permissões próprias; antes disso
 * ela dependia da permissão de localização.
 */
private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
