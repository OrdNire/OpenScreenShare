package com.btscreenshare

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

enum class NetworkType {
    WIFI, HOTSPOT, USB, OTHER
}

data class NetworkInfo(
    val ip: String,
    val interfaceName: String,
    val type: NetworkType
) {
    val label: String
        get() = when (type) {
            NetworkType.WIFI -> "WiFi"
            NetworkType.HOTSPOT -> "热点"
            NetworkType.USB -> "USB"
            NetworkType.OTHER -> "其他"
        }

    val displayText: String
        get() = "$label: $ip"
}

object NetworkUtils {

    private const val TAG = "NetworkUtils"

    // Known hotspot interface names on various Android devices
    private val HOTSPOT_INTERFACES = setOf("ap0", "softap0", "swlan0", "wlan1", "ap1")
    private val USB_INTERFACES = setOf("rndis0", "usb0")
    private val EXCLUDED_INTERFACES = setOf("lo", "dummy0", "rmnet0", "rmnet1", "rmnet2", "rmnet_data0", "rmnet_data1", "ccmni0", "ccmni1")

    /**
     * Scan all network interfaces and return info for each valid one.
     */
    fun getAllNetworkInterfaces(): List<NetworkInfo> {
        val result = mutableListOf<NetworkInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (ni in interfaces) {
                val name = ni.name

                // Skip loopback
                if (ni.isLoopback) {
                    Log.d(TAG, "Skipping loopback: $name")
                    continue
                }

                // Skip down interfaces
                if (!ni.isUp) {
                    Log.d(TAG, "Skipping down interface: $name")
                    continue
                }

                // Skip known virtual/excluded interfaces
                if (name in EXCLUDED_INTERFACES) {
                    Log.d(TAG, "Skipping excluded interface: $name")
                    continue
                }

                // Skip interfaces starting with "rmnet" (mobile data) or "dummy"
                if (name.startsWith("rmnet") || name.startsWith("dummy") || name.startsWith("p2p-")) {
                    Log.d(TAG, "Skipping virtual/mobile interface: $name")
                    continue
                }

                // Get IPv4 address
                val inetAddress = ni.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }

                if (inetAddress == null) {
                    Log.d(TAG, "No IPv4 address for: $name")
                    continue
                }

                val ip = inetAddress.hostAddress ?: continue
                val type = categorizeInterface(name)

                Log.d(TAG, "Found interface: $name, IP: $ip, type: $type")
                result.add(NetworkInfo(ip = ip, interfaceName = name, type = type))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning network interfaces", e)
        }

        // Sort: WIFI first, then HOTSPOT, then USB, then OTHER
        return result.sortedBy { it.type.ordinal }
    }

    /**
     * Get all available IPs as a formatted display string.
     * Each line shows: "label: ip"
     */
    fun getAllIpsDisplayText(): String? {
        val interfaces = getAllNetworkInterfaces()
        if (interfaces.isEmpty()) return null
        return interfaces.joinToString("\n") { it.displayText }
    }

    /**
     * Get scenario hint based on detected network interfaces.
     */
    fun getScenarioHint(interfaces: List<NetworkInfo>): String {
        if (interfaces.isEmpty()) {
            return "请连接WiFi或开启热点"
        }

        val hasWifi = interfaces.any { it.type == NetworkType.WIFI }
        val hasHotspot = interfaces.any { it.type == NetworkType.HOTSPOT }

        return when {
            hasWifi && hasHotspot -> "检测到WiFi和热点，请确认两台设备在同一网络"
            interfaces.size > 1 -> "检测到多个网络，请确认两台设备在同一网络"
            hasHotspot -> "热点已开启，对方连接此热点后输入上方IP"
            hasWifi -> "两台设备需连接同一WiFi"
            else -> "检测到网络连接"
        }
    }

    /**
     * Categorize an interface by its name.
     */
    private fun categorizeInterface(name: String): NetworkType {
        val lower = name.lowercase()
        return when {
            lower == "wlan0" -> NetworkType.WIFI
            lower in HOTSPOT_INTERFACES -> NetworkType.HOTSPOT
            lower in USB_INTERFACES -> NetworkType.USB
            lower.startsWith("wlan") -> NetworkType.WIFI  // wlan2, etc. also WiFi
            else -> NetworkType.OTHER
        }
    }
}
