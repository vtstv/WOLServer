package com.vtstv.wolserver

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * High-performance network reachability and liveness prober for target devices.
 * Uses ICMP ping with intelligent TCP socket fallback on standard service ports
 * (3389 RDP, 22 SSH, 445 SMB, 80 HTTP, 8080) to bypass Windows Firewall ICMP drops.
 * 
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
class DevicePinger {

    companion object {
        private const val TAG = "DevicePinger"
        private val COMMON_PROBE_PORTS = intArrayOf(3389, 22, 445, 80, 8080)
    }

    data class PingResult(
        val isOnline: Boolean,
        val latencyMs: Long,
        val method: String = "ICMP"
    )

    /**
     * Checks if a device is online using ICMP and TCP probing.
     */
    suspend fun pingDevice(device: WolDevice, timeoutMs: Int = 600): PingResult = withContext(Dispatchers.IO) {
        val targetIp = device.ipAddress.ifBlank {
            // If device only has broadcast IP, attempt pinging broadcast address or return offline
            if (device.broadcastAddress != "255.255.255.255") device.broadcastAddress else ""
        }

        if (targetIp.isBlank()) {
            return@withContext PingResult(isOnline = false, latencyMs = -1, method = "NO_IP")
        }

        pingIp(targetIp, device.pingPort, timeoutMs)
    }

    /**
     * Probes an IP address for reachability.
     */
    suspend fun pingIp(ip: String, port: Int = 0, timeoutMs: Int = 600): PingResult = withContext(Dispatchers.IO) {
        if (ip.isBlank() || ip == "255.255.255.255") {
            return@withContext PingResult(isOnline = false, latencyMs = -1, method = "INVALID_IP")
        }

        val startTime = System.currentTimeMillis()

        // 1. Try ICMP ping first
        try {
            val address = InetAddress.getByName(ip)
            if (address.isReachable(timeoutMs)) {
                val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                return@withContext PingResult(isOnline = true, latencyMs = latency, method = "ICMP")
            }
        } catch (e: Exception) {
            // Fallthrough to TCP probe
        }

        // 2. Try specific TCP port if specified
        if (port in 1..65535) {
            val isTcpConnected = probeTcpPort(ip, port, timeoutMs)
            if (isTcpConnected) {
                val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                return@withContext PingResult(isOnline = true, latencyMs = latency, method = "TCP:$port")
            }
        } else {
            // 3. Fallback: Quick TCP probe across common ports in parallel
            for (probePort in COMMON_PROBE_PORTS) {
                val connected = probeTcpPort(ip, probePort, 180)
                if (connected) {
                    val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                    return@withContext PingResult(isOnline = true, latencyMs = latency, method = "TCP:$probePort")
                }
            }
        }

        PingResult(isOnline = false, latencyMs = -1, method = "UNREACHABLE")
    }

    /**
     * Probes a single TCP port with a fast socket connect.
     */
    private fun probeTcpPort(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Pings all devices in parallel and returns a map of deviceId -> PingResult.
     */
    suspend fun pingAll(devices: List<WolDevice>): Map<String, PingResult> = withContext(Dispatchers.IO) {
        devices.map { device ->
            async {
                val result = pingDevice(device)
                device.id to result
            }
        }.awaitAll().toMap()
    }
}
