package com.example.locker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.OutputStream
import java.util.*

class BluetoothClient(private val context: Context) {

    private val TAG = "BluetoothClient"
    // Standard UUID for Serial Port Profile (SPP)
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // REPLACE THIS with your Raspberry Pi's Bluetooth MAC Address
    private val RASPBERRY_PI_MAC_ADDRESS = "B8:27:EB:97:C8:48"

    // Define a callback interface to send data back to the UI (Activity/Fragment)
    var onMessageReceived: ((String) -> Unit)? = null

    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: java.io.InputStream? = null
    private var isConnected = false

    @SuppressLint("MissingPermission")
    fun connect() {
        if (isConnected) return

        Thread {
            try {
                val device: BluetoothDevice = bluetoothAdapter!!.getRemoteDevice(RASPBERRY_PI_MAC_ADDRESS)
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)

                // Cancel discovery before connecting to speed up the process
                bluetoothAdapter?.cancelDiscovery()

                // Establish the persistent connection
                socket?.connect()

                outputStream = socket?.outputStream
                inputStream = socket?.inputStream
                isConnected = true
                Log.d(TAG, "Connected to Raspberry Pi!")

                // Start listening for responses in the background
                startListening()

            } catch (e: IOException) {
                Log.e(TAG, "Connection failed: ${e.message}")
                closeConnection()
            }
        }.start()
    }

    private fun startListening() {
        val buffer = ByteArray(1024)
        var bytes: Int

        // This loop runs endlessly in the background while connected
        while (isConnected) {
            try {
                // Read from the InputStream (Blocks until data arrives)
                bytes = inputStream?.read(buffer) ?: 0
                if (bytes > 0) {
                    val incomingMessage = String(buffer, 0, bytes).trim()
                    Log.d(TAG, "Received: $incomingMessage")

                    // Send the message back to your UI layer (e.g., MainActivity)
                    onMessageReceived?.invoke(incomingMessage)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connection lost")
                closeConnection()
                break
            }
        }
    }

    // Call this when Button 1 is pressed
    fun requestNewKey() {
        sendCommand("REQUEST_KEY")
    }

    // Call this when Button 2 is pressed
    fun sendUnlock(storedKey: String) {
        sendCommand("UNLOCK:$storedKey")
    }

    private fun sendCommand(command: String) {
        if (!isConnected) {
            Log.e(TAG, "Cannot send command, device not connected.")
            return
        }

        Thread {
            try {
                outputStream?.write(command.toByteArray())
                outputStream?.flush()
                Log.d(TAG, "Sent command: $command")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send data")
            }
        }.start()
    }

    fun closeConnection() {
        try {
            isConnected = false
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing connection", e)
        }
    }
}