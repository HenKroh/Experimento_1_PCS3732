package com.example.locker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothClient: BluetoothClient
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnRequest: Button
    private lateinit var btnUnlock: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Link code variables to the UI elements in the XML layout
        tvStatus = findViewById(R.id.tvStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnRequest = findViewById(R.id.btnRequest)
        btnUnlock = findViewById(R.id.btnUnlock)

        // Initialize the client class we created earlier
        bluetoothClient = BluetoothClient(this)

        // Define what happens when the background thread receives a message from the Pi
        bluetoothClient.onMessageReceived = { message ->
            // UI updates MUST happen on the main thread
            runOnUiThread {
                handleIncomingMessage(message)
            }
        }

        btnConnect.setOnClickListener {
            checkPermissionsAndConnect()
        }

        btnRequest.setOnClickListener {
            tvStatus.text = "Status: Requesting key...\nPress physical button on Pi!"
            bluetoothClient.requestNewKey()
        }

        btnUnlock.setOnClickListener {
            // Retrieve the securely stored key from the phone's local storage
            val prefs = getSharedPreferences("LockPrefs", Context.MODE_PRIVATE)
            val storedKey = prefs.getString("SECRET_KEY", null)

            if (storedKey != null) {
                tvStatus.text = "Status: Sending unlock command..."
                bluetoothClient.sendUnlock(storedKey)
            } else {
                Toast.makeText(this, "No key found. Request one first.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleIncomingMessage(message: String) {
        if (message.startsWith("KEY:")) {
            // Extract the key part from "KEY:a83b4c12"
            val newKey = message.substring(4)

            // Save the key permanently using SharedPreferences so it survives app restarts
            val prefs = getSharedPreferences("LockPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("SECRET_KEY", newKey).apply()

            tvStatus.text = "Status: Key received and saved!"
            Toast.makeText(this, "Authorized! Key Saved: $newKey", Toast.LENGTH_LONG).show()
        } else {
            // Display other messages (like ACCESS_GRANTED or ACCESS_DENIED)
            tvStatus.text = "Status: $message"
        }
    }

    private fun checkPermissionsAndConnect() {
        // Android 12+ requires explicit BLUETOOTH_CONNECT permission at runtime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
                return
            }
        }
        tvStatus.text = "Status: Connecting to Pi..."
        bluetoothClient.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the connection when the app closes
        bluetoothClient.closeConnection()
    }
}