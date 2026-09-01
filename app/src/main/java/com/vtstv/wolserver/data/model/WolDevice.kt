package com.vtstv.wolserver.data.model

import java.util.UUID

/**
 * Model representing an individual Wake-on-LAN target device.
 */
data class WolDevice(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "My Computer",
    var macAddress: String = "",
    var broadcastAddress: String = "255.255.255.255",
    var port: Int = 9,
    var ipAddress: String = "", // Optional IP address for ping / status checks
    var pingPort: Int = 0,     // 0 = ICMP/auto, or specific port (3389 RDP, 22 SSH, 80, 445)
    var iconType: String = "desktop", // "desktop", "server", "laptop", "console", "tv"
    var lastWokenTimestamp: Long = 0
) {
    fun isValidMacAddress(): Boolean {
        val clean = macAddress.replace(":", "").replace("-", "").replace(".", "").trim()
        return clean.length == 12 && clean.matches(Regex("^[0-9A-Fa-f]{12}$"))
    }

    fun isValidPort(): Boolean {
        return port in 1..65535
    }
}
