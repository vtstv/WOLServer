package com.vtstv.wolserver

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Fast asynchronous LAN scanner and ARP table parser.
 * Discovers active computers, NAS units, consoles, and smart devices
 * across the local subnet and extracts their MAC and hostnames.
 * 
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
class NetworkScanner(private val wakeOnLan: WakeOnLan) {

    companion object {
        private const val TAG = "NetworkScanner"
    }

    data class DiscoveredDevice(
        val ip: String,
        var mac: String,
        var hostname: String,
        var vendor: String = "",
        var isOnline: Boolean = true
    )

    /**
     * Scans the local subnet for active devices.
     */
    suspend fun scanLocalSubnet(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val subnetPrefix = getLocalSubnetPrefix()
        if (subnetPrefix.isBlank()) {
            Log.w(TAG, "Could not determine local subnet prefix")
            return@withContext emptyList()
        }

        Log.i(TAG, "Starting fast parallel subnet probe on $subnetPrefix.1..254")

        // 1. Send parallel lightweight UDP probes to populate the kernel ARP table
        val hostIps = (1..254).map { "$subnetPrefix.$it" }
        
        // Chunk requests in groups of 32 to avoid socket exhaustion
        hostIps.chunked(32).forEach { chunk ->
            chunk.map { ip ->
                async {
                    probeHost(ip)
                }
            }.awaitAll()
        }

        // 2. Read kernel ARP cache from /proc/net/arp
        val arpMap = readArpTable()
        Log.i(TAG, "ARP cache contains ${arpMap.size} entries")

        val discovered = mutableListOf<DiscoveredDevice>()
        val selfIp = getLocalIpAddress()

        // 3. Resolve hostnames and build device list
        arpMap.forEach { (ip, rawMac) ->
            if (ip != selfIp && wakeOnLan.isValidMacAddress(rawMac) && rawMac != "00:00:00:00:00:00") {
                val formattedMac = wakeOnLan.formatMacAddress(rawMac)
                val hostname = try {
                    val inet = InetAddress.getByName(ip)
                    val canonical = inet.canonicalHostName
                    if (canonical.isNotBlank() && canonical != ip) canonical else inet.hostName
                } catch (e: Exception) {
                    "Device"
                }

                val cleanHostname = if (hostname == ip || hostname.isBlank()) "Device ${formattedMac.takeLast(5)}" else hostname

                discovered.add(
                    DiscoveredDevice(
                        ip = ip,
                        mac = formattedMac,
                        hostname = cleanHostname,
                        vendor = guessDeviceType(cleanHostname, formattedMac),
                        isOnline = true
                    )
                )
            }
        }

        // Sort by IP address numerically
        discovered.sortedBy { ipToLong(it.ip) }
    }

    private fun probeHost(ip: String) {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 80
                val data = byteArrayOf(0)
                val address = InetAddress.getByName(ip)
                val packet = DatagramPacket(data, data.size, address, 7) // Echo port probe
                socket.send(packet)
            }
        } catch (e: Exception) {
            // Ignore - probe is just to trigger ARP resolution
        }
    }

    /**
     * Reads /proc/net/arp on Linux/Android.
     * Format: IP address | HW type | Flags | HW address | Mask | Device
     */
    private fun readArpTable(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                var line: String?
                // Skip header line
                reader.readLine()
                while (reader.readLine().also { line = it } != null) {
                    val tokens = line!!.trim().split(Regex("\\s+"))
                    if (tokens.size >= 4) {
                        val ip = tokens[0]
                        val flags = tokens[2]
                        val mac = tokens[3]
                        // Flags 0x2 means reachable/resolved ARP entry
                        if (flags != "0x0" && mac != "00:00:00:00:00:00") {
                            map[ip] = mac
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read /proc/net/arp: ${e.message}")
        }
        return map
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
            lower.contains("nas") || lower.contains("server") || lower.contains("synology") || lower.contains("qnap") || lower.contains("unraid") -> "server"
            lower.contains("laptop") || lower.contains("macbook") || lower.contains("thinkpad") -> "laptop"
            lower.contains("playstation") || lower.contains("ps5") || lower.contains("ps4") || lower.contains("xbox") || lower.contains("switch") -> "console"
            lower.contains("tv") || lower.contains("bravia") || lower.contains("lg") || lower.contains("samsung") -> "tv"
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
