package com.vtstv.wolserver

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN implementation for sending magic packets to wake up remote computers.
 * 
 * The magic packet format:
 * - 6 bytes of 0xFF
 * - 16 repetitions of the target MAC address (6 bytes each)
 * Total: 102 bytes
 * 
 * Copyright (c) 2025 Murr
 * https://github.com/vtstv/wolserver
 */
class WakeOnLan {
    
    companion object {
        private const val TAG = "WakeOnLan"
        private const val MAGIC_PACKET_SIZE = 102
        private const val MAC_ADDRESS_SIZE = 6
        private const val MAC_REPETITIONS = 16
    }
    
    /**
     * Sends a Wake-on-LAN magic packet to the specified MAC address.
     * 
     * @param macAddress Target MAC address (format: AA:BB:CC:DD:EE:FF or AA-BB-CC-DD-EE-FF)
     * @param broadcastAddress Broadcast IP address (default: 255.255.255.255)
     * @param port UDP port (default: 9)
     * @return true if packet was sent successfully, false otherwise
     */
    suspend fun sendWakePacket(
        macAddress: String,
        broadcastAddress: String = "255.255.255.255",
        port: Int = 9
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Preparing to send WOL packet to $macAddress via $broadcastAddress:$port")
            
            // Parse MAC address
            val macBytes = parseMacAddress(macAddress)
                ?: throw IllegalArgumentException("Invalid MAC address format: $macAddress")
            
            // Create magic packet
            val magicPacket = createMagicPacket(macBytes)
            
            // Send UDP packet
            DatagramSocket().use { socket ->
                val address = InetAddress.getByName(broadcastAddress)
                val packet = DatagramPacket(magicPacket, magicPacket.size, address, port)
                
                socket.broadcast = true
                socket.send(packet)
                
                Log.i(TAG, "WOL packet sent successfully to $macAddress")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WOL packet: ${e.message}", e)
            false
        }
    }
    
    /**
     * Parses MAC address string into byte array.
     * Supports both colon (:) and dash (-) separators.
     */
    private fun parseMacAddress(macAddress: String): ByteArray? {
        return try {
            val cleanMac = macAddress.replace(":", "").replace("-", "")
            
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
    
    /**
     * Creates the magic packet byte array.
     * Format: 6 bytes of 0xFF + 16 repetitions of MAC address
     */
    private fun createMagicPacket(macBytes: ByteArray): ByteArray {
        val packet = ByteArray(MAGIC_PACKET_SIZE)
        var index = 0
        
        // First 6 bytes are 0xFF
        repeat(MAC_ADDRESS_SIZE) {
            packet[index++] = 0xFF.toByte()
        }
        
        // Repeat MAC address 16 times
        repeat(MAC_REPETITIONS) {
            macBytes.copyInto(packet, index)
            index += MAC_ADDRESS_SIZE
        }
        
        return packet
    }
    
    /**
     * Validates MAC address format.
     */
    fun isValidMacAddress(macAddress: String): Boolean {
        return macAddress.matches(Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"))
    }
    
    /**
     * Formats MAC address to standard colon notation.
     */
    fun formatMacAddress(macAddress: String): String {
        val cleanMac = macAddress.replace(":", "").replace("-", "").uppercase()
        return if (cleanMac.length == 12) {
            cleanMac.chunked(2).joinToString(":")
        } else {
            macAddress
        }
    }
}