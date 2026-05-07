package com.btscreenshare

import android.Manifest
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class BluetoothSession(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothSession"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val BT_SERVER_NAME = "BTScreenShare"
    }

    interface Callback {
        fun onDeviceFound(device: BluetoothDevice)
        fun onDiscoveryStarted()
        fun onDiscoveryFinished()
        fun onConnectionEstablished(remoteIp: String)
        fun onConnectionFailed(error: String)
        fun onDisconnected()
    }

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var connectedSocket: BluetoothSocket? = null
    private var callback: Callback? = null
    private var isServer = false
    private var serverThread: Thread? = null
    private var connectThread: Thread? = null
    private val discoveredDevices = CopyOnWriteArrayList<BluetoothDevice>()
    private var discoveryReceiver: BroadcastReceiver? = null

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun getPairedDevices(): List<BluetoothDevice> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                return emptyList()
            }
        }
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun startDiscovery(context: Context) {
        if (bluetoothAdapter == null) return
        discoveredDevices.clear()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this.context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                callback?.onConnectionFailed("BLUETOOTH_SCAN permission not granted")
                return
            }
        }

        // Register receiver for discovery
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let {
                            if (it !in discoveredDevices) {
                                discoveredDevices.add(it)
                                callback?.onDeviceFound(it)
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        callback?.onDiscoveryStarted()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        callback?.onDiscoveryFinished()
                    }
                }
            }
        }
        context.registerReceiver(discoveryReceiver, filter)
        bluetoothAdapter.startDiscovery()
    }

    fun stopDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) return
        }
        bluetoothAdapter?.cancelDiscovery()
        discoveryReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            discoveryReceiver = null
        }
    }

    fun startServer() {
        isServer = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                callback?.onConnectionFailed("BLUETOOTH_CONNECT permission not granted")
                return
            }
        }

        serverThread = Thread {
            try {
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(BT_SERVER_NAME, SPP_UUID)
                Log.d(TAG, "Server socket started, waiting for connection...")
                val socket = serverSocket?.accept()
                if (socket != null) {
                    connectedSocket = socket
                    Log.d(TAG, "Client connected via Bluetooth")
                    exchangeIpAndNotify(socket, isServerSide = true)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Server accept failed", e)
                callback?.onConnectionFailed("Server accept failed: ${e.message}")
            }
        }.apply { start() }
    }

    fun connectToDevice(device: BluetoothDevice) {
        isServer = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                callback?.onConnectionFailed("BLUETOOTH_CONNECT permission not granted")
                return
            }
        }

        bluetoothAdapter?.cancelDiscovery()

        connectThread = Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                connectedSocket = socket
                Log.d(TAG, "Connected to server via Bluetooth")
                exchangeIpAndNotify(socket, isServerSide = false)
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed", e)
                callback?.onConnectionFailed("Connection failed: ${e.message}")
            }
        }.apply { start() }
    }

    private fun exchangeIpAndNotify(socket: BluetoothSocket, isServerSide: Boolean) {
        try {
            val localIp = getLocalIpAddress() ?: "0.0.0.0"
            val inputStream = socket.inputStream
            val outputStream = socket.outputStream

            if (isServerSide) {
                // Server sends IP first, then reads client IP
                val ipBytes = localIp.toByteArray(Charsets.UTF_8)
                outputStream.write(ipBytes.size)
                outputStream.write(ipBytes)
                outputStream.flush()

                val len = inputStream.read()
                if (len > 0) {
                    val buffer = ByteArray(len)
                    inputStream.read(buffer, 0, len)
                    val remoteIp = String(buffer, Charsets.UTF_8)
                    callback?.onConnectionEstablished(remoteIp)
                } else {
                    callback?.onConnectionFailed("Failed to read remote IP")
                }
            } else {
                // Client reads server IP first, then sends own IP
                val len = inputStream.read()
                if (len > 0) {
                    val buffer = ByteArray(len)
                    inputStream.read(buffer, 0, len)
                    val remoteIp = String(buffer, Charsets.UTF_8)

                    val ipBytes = localIp.toByteArray(Charsets.UTF_8)
                    outputStream.write(ipBytes.size)
                    outputStream.write(ipBytes)
                    outputStream.flush()

                    callback?.onConnectionEstablished(remoteIp)
                } else {
                    callback?.onConnectionFailed("Failed to read remote IP")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "IP exchange failed", e)
            callback?.onConnectionFailed("IP exchange failed: ${e.message}")
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return null
    }

    fun close() {
        stopDiscovery()
        try { serverSocket?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { connectedSocket?.close() } catch (_: Exception) {}
        serverThread?.interrupt()
        connectThread?.interrupt()
        connectedSocket = null
        serverSocket = null
        clientSocket = null
    }

    fun isConnected(): Boolean = connectedSocket?.isConnected == true
}
