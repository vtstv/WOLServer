package com.vtstv.wolserver

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Fast asynchronous LAN scanner with multi-strategy host & MAC discovery.
 * Combines active multi-port TCP/ICMP sweeps with kernel ARP/neighbor table parsing
 * to discover active computers, NAS units, consoles, and smart devices across the subnet.
 * 
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
class NetworkScanner(private val wakeOnLan: WakeOnLan) {

    companion object {
        private const val TAG = "NetworkScanner"
        private val COMMON_PORTS = intArrayOf(80, 445, 22, 3389, 8080, 53, 139, 5000, 8000, 7, 9, 8888, 443)
    }

    data class DiscoveredDevice(
        val ip: String,
        var mac: String,
        var hostname: String,
        var vendor: String = "",
        var isOnline: Boolean = true
    )

    /**
     * Scans the local subnet for active devices using parallel probes
     * and multi-source ARP table discovery.
     */
    suspend fun scanLocalSubnet(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val subnetPrefix = getLocalSubnetPrefix()
        if (subnetPrefix.isBlank()) {
            Log.w(TAG, "Could not determine local subnet prefix")
            return@withContext emptyList()
        }

        val selfIp = getLocalIpAddress()
        Log.i(TAG, "Starting comprehensive subnet scan on $subnetPrefix.1..254 (Self: $selfIp)")

        // 1. Probing Phase: Parallel sweep across all 254 hosts
        val hostIps = (1..254).map { "$subnetPrefix.$it" }.filter { it != selfIp }
        
        val activeHostsMap = mutableMapOf<String, Boolean>()

        // Chunk in groups of 32 for optimal concurrency without socket exhaustion
        hostIps.chunked(32).forEach { chunk ->
            val chunkResults = chunk.map { ip ->
                async {
                    val isActive = probeAndDetectHost(ip)
                    ip to isActive
                }
            }.awaitAll()

            chunkResults.forEach { (ip, isActive) ->
                if (isActive) {
                    activeHostsMap[ip] = true
                }
            }
        }

        // 2. ARP Discovery Phase: Gather MAC addresses from all available sources
        val arpMap = readAllArpSources()
        Log.i(TAG, "Discovered ${activeHostsMap.size} active hosts, gathered ${arpMap.size} ARP entries")

        // Combine active probed hosts and any resolved ARP entries
        val allDiscoveredIps = (activeHostsMap.keys + arpMap.keys).toSet().filter { it != selfIp }
        val discoveredList = mutableListOf<DiscoveredDevice>()

        for (ip in allDiscoveredIps) {
            val rawMac = arpMap[ip] ?: ""
            val formattedMac = if (wakeOnLan.isValidMacAddress(rawMac) && rawMac != "00:00:00:00:00:00") {
                wakeOnLan.formatMacAddress(rawMac)
            } else {
                ""
            }

            val hostname = resolveHostname(ip, formattedMac)
            val devType = guessDeviceType(hostname, formattedMac)

            discoveredList.add(
                DiscoveredDevice(
                    ip = ip,
                    mac = formattedMac,
                    hostname = hostname,
                    vendor = devType,
                    isOnline = activeHostsMap[ip] ?: true
                )
            )
        }

        // Sort by IP address numerically
        discoveredList.sortedBy { ipToLong(it.ip) }
    }

    /**
     * Actively probes an IP address using ICMP reachability, TCP ports, and UDP packet.
     * Returns true if host responds on any vector.
     */
    private fun probeAndDetectHost(ip: String): Boolean {
        // Step 1: Send quick UDP trigger to force OS ARP cache resolution
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 60
                val data = byteArrayOf(0)
                val address = InetAddress.getByName(ip)
                val packet = DatagramPacket(data, data.size, address, 9)
                socket.send(packet)
            }
        } catch (ignored: Exception) {}

        // Step 2: ICMP isReachable check
        try {
            val inet = InetAddress.getByName(ip)
            if (inet.isReachable(100)) {
                return true
            }
        } catch (ignored: Exception) {}

        // Step 3: Fast TCP socket check across top service ports
        for (port in COMMON_PORTS) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), 80)
                    return true
                }
            } catch (ignored: Exception) {}
        }

        return false
    }

    /**
     * Collects ARP table data from multiple sources:
     * - /proc/net/arp FileReader
     * - 'ip neigh' process execution
     * - 'cat /proc/net/arp' process execution
     */
    private fun readAllArpSources(): Map<String, String> {
        val map = mutableMapOf<String, String>()

        // Source 1: Direct /proc/net/arp read
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                reader.readLine() // skip header
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val tokens = line!!.trim().split(Regex("\\s+"))
                    if (tokens.size >= 4) {
                        val ip = tokens[0]
                        val flags = tokens[2]
                        val mac = tokens[3]
                        if (flags != "0x0" && mac != "00:00:00:00:00:00" && wakeOnLan.isValidMacAddress(mac)) {
                            map[ip] = mac
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        // Source 2: 'ip neigh' command
        if (map.isEmpty()) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("ip", "neigh"))
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val tokens = line!!.trim().split(Regex("\\s+"))
                        val lladdrIdx = tokens.indexOf("lladdr")
                        if (lladdrIdx in 0 until tokens.size - 1) {
                            val ip = tokens[0]
                            val mac = tokens[lladdrIdx + 1]
                            if (wakeOnLan.isValidMacAddress(mac) && mac != "00:00:00:00:00:00") {
                                map[ip] = mac
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        // Source 3: 'cat /proc/net/arp' command
        if (map.isEmpty()) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("cat", "/proc/net/arp"))
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.readLine() // skip header
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val tokens = line!!.trim().split(Regex("\\s+"))
                        if (tokens.size >= 4) {
                            val ip = tokens[0]
                            val flags = tokens[2]
                            val mac = tokens[3]
                            if (flags != "0x0" && mac != "00:00:00:00:00:00" && wakeOnLan.isValidMacAddress(mac)) {
                                map[ip] = mac
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        return map
    }

    private fun resolveHostname(ip: String, mac: String): String {
        return try {
            val inet = InetAddress.getByName(ip)
            val canonical = inet.canonicalHostName
            if (canonical.isNotBlank() && canonical != ip) {
                canonical
            } else {
                val host = inet.hostName
                if (host.isNotBlank() && host != ip) host else defaultDeviceName(ip, mac)
            }
        } catch (e: Exception) {
            defaultDeviceName(ip, mac)
        }
    }

    private fun defaultDeviceName(ip: String, mac: String): String {
        return if (mac.isNotBlank()) {
            "Device ${mac.takeLast(5).replace(":", "")}"
        } else {
            "Host ${ip.substringAfterLast('.')}"
        }
    }

    private fun getLocalSubnetPrefix(): String {
        val ip = getLocalIpAddress()
        val lastDot = ip.lastIndexOf('.')
        return if (lastDot > 0) ip.substring(0, lastDot) else "192.168.0"
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        if (sAddr != null && sAddr.indexOf(':') < 0) {
                            return sAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP address", e)
        }
        return "192.168.0.1"
    }

    private fun guessDeviceType(hostname: String, mac: String): String {
        val lower = hostname.lowercase()
        return when {
            lower.contains("desktop") || lower.contains("pc") || lower.contains("rig") -> "desktop"
            lower.contains("nas") || lower.contains("server") || lower.contains("synology") || lower.contains("qnap") || lower.contains("unraid") || lower.contains("fritz") || lower.contains("router") -> "server"
            lower.contains("laptop") || lower.contains("macbook") || lower.contains("thinkpad") -> "laptop"
            lower.contains("playstation") || lower.contains("ps5") || lower.contains("ps4") || lower.contains("xbox") || lower.contains("switch") -> "console"
            lower.contains("tv") || lower.contains("bravia") || lower.contains("lg") || lower.contains("samsung") || lower.contains("firetv") -> "tv"
            else -> "desktop"
        }
    }

    private fun ipToLong(ip: String): Long {
        return try {
            val parts = ip.split(".").map { it.toLong() }
            (parts[0] shl 24) + (parts[1] shl 16) + (parts[2] shl 8) + parts[3]
        } catch (e: Exception) {
            0L
        }
    }
}
