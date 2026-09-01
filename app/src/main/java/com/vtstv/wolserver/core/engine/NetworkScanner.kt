package com.vtstv.wolserver.core.engine

import android.util.Log
import com.vtstv.wolserver.data.model.DiscoveredDevice
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
 * and NetBIOS / UPnP resolution to discover active computers, NAS units, and consoles.
 */
class NetworkScanner(private val wakeOnLan: WakeOnLan) {

    companion object {
        private const val TAG = "NetworkScanner"
        private val COMMON_PORTS = intArrayOf(80, 445, 22, 3389, 8080, 53, 139, 5000, 8000, 7, 9, 8888, 443)
    }

    suspend fun scanLocalSubnet(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val subnetPrefix = getLocalSubnetPrefix()
        if (subnetPrefix.isBlank()) {
            Log.w(TAG, "Could not determine local subnet prefix")
            return@withContext emptyList()
        }

        val selfIp = getLocalIpAddress()
        Log.i(TAG, "Starting comprehensive subnet scan on $subnetPrefix.1..254 (Self: $selfIp)")

        val hostIps = (1..254).map { "$subnetPrefix.$it" }.filter { it != selfIp }
        val activeHostsMap = mutableMapOf<String, Boolean>()

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

        val arpMap = readAllArpSources()
        Log.i(TAG, "Discovered ${activeHostsMap.size} active hosts, gathered ${arpMap.size} ARP entries")

        val allDiscoveredIps = (activeHostsMap.keys + arpMap.keys).toSet().filter { it != selfIp }
        val discoveredList = mutableListOf<DiscoveredDevice>()

        for (ip in allDiscoveredIps) {
            var rawMac = arpMap[ip] ?: ""

            if (rawMac.isBlank()) {
                rawMac = resolveMacFromUpnp(ip) ?: resolveMacFromNetbios(ip) ?: ""
            }

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

        discoveredList.sortedBy { ipToLong(it.ip) }
    }

    private fun probeAndDetectHost(ip: String): Boolean {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 60
                val data = byteArrayOf(0)
                val address = InetAddress.getByName(ip)
                val packet = DatagramPacket(data, data.size, address, 9)
                socket.send(packet)
            }
        } catch (ignored: Exception) {}

        try {
            val inet = InetAddress.getByName(ip)
            if (inet.isReachable(100)) {
                return true
            }
        } catch (ignored: Exception) {}

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

    private fun resolveMacFromUpnp(ip: String): String? {
        val targets = listOf(
            "49000/tr64desc.xml",
            "49000/igddesc.xml",
            "80/tr64desc.xml",
            "80/igddesc.xml",
            "8080/description.xml",
            "49152/description.xml",
            "49153/description.xml",
            "8080/rootDesc.xml"
        )

        for (target in targets) {
            try {
                val url = java.net.URL("http://$ip:$target")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 450
                conn.readTimeout = 500
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "WOLServer/2.0")
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }

                    val serialRegex = Regex("<(?:serialNumber|macAddress|mac)>([^<]+)</(?:serialNumber|macAddress|mac)>", RegexOption.IGNORE_CASE)
                    val serialMatch = serialRegex.find(body)?.groupValues?.get(1)?.trim()
                    if (serialMatch != null && wakeOnLan.isValidMacAddress(serialMatch)) {
                        return serialMatch
                    }

                    val udnRegex = Regex("uuid:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-([0-9a-fA-F]{12})", RegexOption.IGNORE_CASE)
                    val udnMatch = udnRegex.find(body)?.groupValues?.get(1)?.trim()
                    if (udnMatch != null && wakeOnLan.isValidMacAddress(udnMatch)) {
                        return udnMatch
                    }
                }
                conn.disconnect()
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun resolveMacFromNetbios(ip: String): String? {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 350
                val query = byteArrayOf(
                    0xa2.toByte(), 0x48.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x20, 0x43, 0x4b, 0x41, 0x41, 0x41,
                    0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                    0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                    0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x00, 0x00, 0x21,
                    0x00, 0x01
                )
                val address = InetAddress.getByName(ip)
                val packet = DatagramPacket(query, query.size, address, 137)
                socket.send(packet)

                val buf = ByteArray(1024)
                val recvPacket = DatagramPacket(buf, buf.size)
                socket.receive(recvPacket)

                if (recvPacket.length >= 62) {
                    val numNames = buf[56].toInt() and 0xFF
                    val macOffset = 57 + numNames * 18
                    if (recvPacket.length >= macOffset + 6) {
                        val macBytes = buf.copyOfRange(macOffset, macOffset + 6)
                        val macStr = macBytes.joinToString(":") { "%02X".format(it) }
                        if (wakeOnLan.isValidMacAddress(macStr) && macStr != "00:00:00:00:00:00") {
                            return macStr
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
        return null
    }

    private fun readAllArpSources(): Map<String, String> {
        val map = mutableMapOf<String, String>()

        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                reader.readLine()
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

        if (map.isEmpty()) {
            val cmdList = listOf(arrayOf("/system/bin/ip", "neigh"), arrayOf("ip", "neigh"))
            for (cmd in cmdList) {
                try {
                    val process = Runtime.getRuntime().exec(cmd)
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
                    if (map.isNotEmpty()) break
                } catch (ignored: Exception) {}
            }
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
