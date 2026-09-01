package com.vtstv.wolserver.data.model

import java.net.InetAddress

/**
 * Global configuration data class for Simple WOL Server.
 */
data class WolConfig(
    var authToken: String = "default_token_change_me",
    var webPassword: String = "admin123",
    var devices: MutableList<WolDevice> = mutableListOf(),
    var broadcastAddress: String = "255.255.255.255",
    var wolPort: Int = 9,
    var httpPort: Int = 8085,
    var ipAllowlist: List<String> = emptyList(),
    var httpsEnabled: Boolean = false,
    var autoStartEnabled: Boolean = true,
    var requireAuthentication: Boolean = true,
    // Legacy single-device compatibility
    var targetMacAddress: String = ""
) {
    fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }

    fun isValidIpAddress(ip: String): Boolean {
        return try {
            InetAddress.getByName(ip)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Model for full configuration export and import backup.
 */
data class WolBackup(
    val app: String = "Simple WOL Server",
    val version: String = "2.0.0",
    val timestamp: Long = System.currentTimeMillis(),
    val config: WolConfig,
    val devices: List<WolDevice>,
    val schedules: List<WolSchedule>
)
