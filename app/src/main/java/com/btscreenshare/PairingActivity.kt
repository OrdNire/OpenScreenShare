package com.btscreenshare

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class PairingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IS_SHARING = "is_sharing"
        const val EXTRA_REMOTE_IP = "remote_ip"
    }

    private lateinit var bluetoothSession: BluetoothSession
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var rvDevices: RecyclerView
    private lateinit var progressScan: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnScan: MaterialButton
    private lateinit var etIpAddress: TextInputEditText
    private lateinit var btnManualConnect: MaterialButton

    private var isSharing = true
    private val allDevices = mutableListOf<BluetoothDevice>()
    private var isConnecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        isSharing = intent.getBooleanExtra(EXTRA_IS_SHARING, true)

        // Set up toolbar
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Set up views
        rvDevices = findViewById(R.id.rvDevices)
        progressScan = findViewById(R.id.progressScan)
        tvStatus = findViewById(R.id.tvStatus)
        btnScan = findViewById(R.id.btnScan)
        etIpAddress = findViewById(R.id.etIpAddress)
        btnManualConnect = findViewById(R.id.btnManualConnect)

        // Set up manual connect
        btnManualConnect.setOnClickListener {
            val ipAddress = etIpAddress.text.toString().trim()
            if (ipAddress.isNotEmpty()) {
                connectToIp(ipAddress)
            } else {
                Toast.makeText(this, "Please enter IP address", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up RecyclerView
        deviceAdapter = DeviceAdapter(allDevices) { device ->
            if (!isConnecting) {
                connectToDevice(device)
            }
        }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        // Set up Bluetooth
        bluetoothSession = BluetoothSession(this)
        bluetoothSession.setCallback(object : BluetoothSession.Callback {
            override fun onDeviceFound(device: BluetoothDevice) {
                runOnUiThread {
                    if (device !in allDevices) {
                        allDevices.add(device)
                        deviceAdapter.notifyItemInserted(allDevices.size - 1)
                        tvStatus.text = "${allDevices.size} device(s) found"
                    }
                }
            }

            override fun onDiscoveryStarted() {
                runOnUiThread {
                    progressScan.visibility = View.VISIBLE
                    btnScan.isEnabled = false
                    btnScan.text = getString(R.string.scanning)
                }
            }

            override fun onDiscoveryFinished() {
                runOnUiThread {
                    progressScan.visibility = View.GONE
                    btnScan.isEnabled = true
                    btnScan.text = getString(R.string.scan_devices)
                    if (allDevices.isEmpty()) {
                        tvStatus.text = getString(R.string.no_devices_found)
                    }
                }
            }

            override fun onConnectionEstablished(remoteIp: String) {
                runOnUiThread {
                    isConnecting = false
                    Toast.makeText(this@PairingActivity, "Connected! Remote IP: $remoteIp", Toast.LENGTH_SHORT).show()

                    if (isSharing) {
                        val intent = Intent(this@PairingActivity, StreamShareActivity::class.java)
                        intent.putExtra(EXTRA_REMOTE_IP, remoteIp)
                        startActivity(intent)
                    } else {
                        val intent = Intent(this@PairingActivity, StreamViewActivity::class.java)
                        intent.putExtra(EXTRA_REMOTE_IP, remoteIp)
                        startActivity(intent)
                    }
                    finish()
                }
            }

            override fun onConnectionFailed(error: String) {
                runOnUiThread {
                    isConnecting = false
                    Toast.makeText(this@PairingActivity, "Connection failed: $error", Toast.LENGTH_LONG).show()
                    tvStatus.text = "Connection failed. Try again."
                    progressScan.visibility = View.GONE
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    Toast.makeText(this@PairingActivity, "Bluetooth disconnected", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Show paired devices initially
        val pairedDevices = bluetoothSession.getPairedDevices()
        for (device in pairedDevices) {
            if (device !in allDevices) {
                allDevices.add(device)
            }
        }
        if (allDevices.isNotEmpty()) {
            deviceAdapter.notifyDataSetChanged()
            tvStatus.text = "${allDevices.size} paired device(s). Tap scan for more."
        }

        // Scan button
        btnScan.setOnClickListener {
            allDevices.clear()
            deviceAdapter.notifyDataSetChanged()
            bluetoothSession.startDiscovery(this)
        }
    }

    private fun connectToIp(ipAddress: String) {
        isConnecting = true
        progressScan.visibility = View.VISIBLE
        tvStatus.text = "Connecting to $ipAddress..."

        // Navigate directly to streaming activity with the IP
        if (isSharing) {
            val intent = Intent(this, StreamShareActivity::class.java)
            intent.putExtra(EXTRA_REMOTE_IP, ipAddress)
            startActivity(intent)
        } else {
            val intent = Intent(this, StreamViewActivity::class.java)
            intent.putExtra(EXTRA_REMOTE_IP, ipAddress)
            startActivity(intent)
        }
        finish()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        isConnecting = true
        progressScan.visibility = View.VISIBLE
        tvStatus.text = "Connecting to ${device.name ?: device.address}..."

        // If sharing, we're the server (viewer connects to us)
        // If viewing, we connect to the sharer
        if (isSharing) {
            bluetoothSession.startServer()
            // Also try to connect to the device (one side will succeed)
            bluetoothSession.connectToDevice(device)
        } else {
            bluetoothSession.connectToDevice(device)
        }
    }

    override fun onDestroy() {
        bluetoothSession.close()
        super.onDestroy()
    }

    // RecyclerView Adapter
    inner class DeviceAdapter(
        private val devices: List<BluetoothDevice>,
        private val onItemClick: (BluetoothDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvDeviceName)
            val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.tvName.text = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        device.name ?: "Unknown Device"
                    } else {
                        "Unknown Device"
                    }
                } else {
                    @Suppress("DEPRECATION")
                    device.name ?: "Unknown Device"
                }
            } catch (e: Exception) {
                "Unknown Device"
            }
            holder.tvAddress.text = device.address
            holder.itemView.setOnClickListener { onItemClick(device) }
        }

        override fun getItemCount(): Int = devices.size
    }
}
