package com.btscreenshare

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class LanConnectActivity : AppCompatActivity() {

    companion object {
        var selectedQuality: VideoQuality = VideoQuality.BALANCED
    }

    private lateinit var tvLocalIp: TextView
    private lateinit var tvNetworkHint: TextView
    private lateinit var etRemoteIp: TextInputEditText
    private lateinit var btnShareScreen: MaterialButton
    private lateinit var btnViewScreen: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lan_connect)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvLocalIp = findViewById(R.id.tvLocalIp)
        tvNetworkHint = findViewById(R.id.tvNetworkHint)
        etRemoteIp = findViewById(R.id.etRemoteIp)
        btnShareScreen = findViewById(R.id.btnShareScreen)
        btnViewScreen = findViewById(R.id.btnViewScreen)

        detectAndDisplayNetwork()

        btnShareScreen.setOnClickListener {
            showQualityDialog()
        }

        btnViewScreen.setOnClickListener {
            // View mode: need partner IP to connect
            val partnerIp = etRemoteIp.text.toString().trim()
            if (validateIp(partnerIp)) {
                val intent = Intent(this, StreamViewActivity::class.java)
                intent.putExtra(PairingActivity.EXTRA_REMOTE_IP, partnerIp)
                startActivity(intent)
                finish()
            }
        }
    }

    private fun detectAndDisplayNetwork() {
        // Try enhanced detection first (all interfaces)
        val interfaces = NetworkUtils.getAllNetworkInterfaces()

        if (interfaces.isNotEmpty()) {
            // Build multi-line display with labels
            val displayText = interfaces.joinToString("\n") { it.displayText }
            tvLocalIp.text = displayText

            // Set scenario hint
            tvNetworkHint.text = NetworkUtils.getScenarioHint(interfaces)

            // Copy first IP on click (for convenience)
            val primaryIp = interfaces.first().ip
            tvLocalIp.setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("IP Address", primaryIp)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.ip_copied), Toast.LENGTH_SHORT).show()
            }
        } else {
            // Fallback to legacy WiFi detection
            displayWifiIpFallback()
        }
    }

    /**
     * Legacy WiFi-only detection as fallback.
     */
    private fun displayWifiIpFallback() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress

        if (ipAddress == 0) {
            tvLocalIp.text = getString(R.string.no_wifi_connection)
            tvNetworkHint.text = getString(R.string.hint_no_network)
            return
        }

        val ip = formatIp(ipAddress)
        tvLocalIp.text = ip
        tvNetworkHint.text = getString(R.string.hint_wifi)

        tvLocalIp.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("IP Address", ip)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.ip_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showQualityDialog() {
        val qualityOptions = arrayOf("流畅", "均衡", "高清", "超清")
        val qualities = arrayOf(VideoQuality.LOW, VideoQuality.BALANCED, VideoQuality.HIGH, VideoQuality.ULTRA)
        val currentIndex = qualities.indexOf(selectedQuality)

        AlertDialog.Builder(this)
            .setTitle("选择视频清晰度")
            .setSingleChoiceItems(qualityOptions, currentIndex) { dialog, which ->
                selectedQuality = qualities[which]
            }
            .setPositiveButton("开始分享") { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(this, StreamShareActivity::class.java)
                startActivity(intent)
                finish()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun formatIp(ipAddress: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }

    private fun validateIp(ip: String): Boolean {
        if (ip.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_partner_ip), Toast.LENGTH_SHORT).show()
            return false
        }

        val parts = ip.split(".")
        if (parts.size != 4) {
            Toast.makeText(this, getString(R.string.invalid_ip_format), Toast.LENGTH_SHORT).show()
            return false
        }

        for (part in parts) {
            val num = part.toIntOrNull()
            if (num == null || num < 0 || num > 255) {
                Toast.makeText(this, getString(R.string.invalid_ip_format), Toast.LENGTH_SHORT).show()
                return false
            }
        }

        return true
    }
}
