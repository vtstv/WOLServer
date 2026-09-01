/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver.core.engine

import android.util.Log
import com.vtstv.wolserver.data.model.WolDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Wake-on-LAN engine for sending magic packets to wake up remote computers.
 *
 * Magic Packet specification:
 * - 6 bytes of 0xFF synchronization stream
 * - 16 repetitions of the target MAC address (6 bytes each)
 * Total: 102 bytes transmitted over UDP broadcast (ports 9 or 7).
 */
class WakeOnLan {

    companion object {
        private const val TAG = "WakeOnLan"
        private const val MAGIC_PACKET_SIZE = 102
        private const val MAC_ADDRESS_SIZE = 6
        private const val MAC_REPETITIONS = 16
    }

    suspend fun sendWakePacket(device: WolDevice, packetCount: Int = 2): Boolean {
        return sendWakePacket(
            macAddress = device.macAddress,
            broadcastAddress = device.broadcastAddress.ifBlank { "255.255.255.255" },
            port = if (device.port in 1..65535) device.port else 9,
            packetCount = packetCount
        )
    }

    suspend fun sendWakePacket(
        macAddress: String,
        broadcastAddress: String = "255.255.255.255",
        port: Int = 9,
        packetCount: Int = 2
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Sending WOL packet to $macAddress via $broadcastAddress:$port ($packetCount bursts)")
            val macBytes = parseMacAddress(macAddress)
                ?: throw IllegalArgumentException("Invalid MAC address format: $macAddress")

            val magicPacket = createMagicPacket(macBytes)
            val targetAddresses = mutableSetOf<InetAddress>()

            try {
                targetAddresses.add(InetAddress.getByName(broadcastAddress))
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve broadcast address $broadcastAddress: ${e.message}")
            }

            if (broadcastAddress == "255.255.255.255" || targetAddresses.isEmpty()) {
                targetAddresses.addAll(getInterfaceBroadcastAddresses())
            }
            if (targetAddresses.isEmpty()) {
                targetAddresses.add(InetAddress.getByName("255.255.255.255"))
            }

            DatagramSocket().use { socket ->
                socket.broadcast = true
                repeat(packetCount) { i ->
                    for (addr in targetAddresses) {
                        val packet = DatagramPacket(magicPacket, magicPacket.size, addr, port)
                        socket.send(packet)
                    }
                    if (i < packetCount - 1) {
                        Thread.sleep(40)
                    }
                }
            }
            Log.i(TAG, "WOL packet sent successfully to $macAddress across ${targetAddresses.size} broadcast targets")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WOL packet: ${e.message}", e)
            false
        }
    }

    suspend fun sendWakePackets(devices: List<WolDevice>): Map<String, Boolean> = withContext(Dispatchers.IO) {
        devices.map { device ->
            async {
                val success = sendWakePacket(device)
                device.id to success
            }
        }.awaitAll().toMap()
    }

    private fun getInterfaceBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (ia in intf.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast != null) {
                        list.add(bcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating broadcast interfaces: ${e.message}")
        }
        return list
    }

    fun parseMacAddress(macAddress: String): ByteArray? {
        return try {
            val cleanMac = macAddress.replace(":", "")
                .replace("-", "")
                .replace(".", "")
                .trim()

            if (cleanMac.length != 12) {
                Log.e(TAG, "MAC address must be 12 hex characters: $macAddress")
                return null
            }

            ByteArray(MAC_ADDRESS_SIZE) { i ->
                val hex = cleanMac.substring(i * 2, i * 2 + 2)
                hex.toInt(16).toByte()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse MAC address: $macAddress", e)
            null
        }
    }

    private fun createMagicPacket(macBytes: ByteArray): ByteArray {
        val packet = ByteArray(MAGIC_PACKET_SIZE)
        var index = 0

        repeat(MAC_ADDRESS_SIZE) {
            packet[index++] = 0xFF.toByte()
        }

        repeat(MAC_REPETITIONS) {
            macBytes.copyInto(packet, index)
            index += MAC_ADDRESS_SIZE
        }

        return packet
    }

    fun isValidMacAddress(macAddress: String): Boolean {
        val clean = macAddress.replace(":", "")
            .replace("-", "")
            .replace(".", "")
            .trim()
        return clean.length == 12 && clean.matches(Regex("^[0-9A-Fa-f]{12}$"))
    }

    fun formatMacAddress(macAddress: String): String {
        val cleanMac = macAddress.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
        return if (cleanMac.length == 12) {
            cleanMac.chunked(2).joinToString(":")
        } else {
            macAddress
        }
    }
}
