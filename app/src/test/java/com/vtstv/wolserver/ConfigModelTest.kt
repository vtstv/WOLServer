/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver

import com.google.gson.Gson
import com.vtstv.wolserver.data.model.WolBackup
import com.vtstv.wolserver.data.model.WolConfig
import com.vtstv.wolserver.data.model.WolDevice
import com.vtstv.wolserver.data.model.WolSchedule
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for data models, validation methods, and JSON serialization.
 */
class ConfigModelTest {

    private val gson = Gson()

    @Test
    fun testWolDevice_validation() {
        val validDevice = WolDevice(
            name = "Gaming Rig",
            macAddress = "18:31:BF:6E:D5:BB",
            broadcastAddress = "192.168.0.255",
            port = 9
        )
        assertTrue(validDevice.isValidMacAddress())
        assertTrue(validDevice.isValidPort())

        val invalidDevice = WolDevice(
            name = "Bad Device",
            macAddress = "invalid_mac",
            port = 999999
        )
        assertFalse(invalidDevice.isValidMacAddress())
        assertFalse(invalidDevice.isValidPort())
    }

    @Test
    fun testWolSchedule_serialization() {
        val schedule = WolSchedule(
            id = "sched-123",
            deviceId = "dev-456",
            deviceName = "NAS Server",
            name = "Nightly Backup",
            daysOfWeek = mutableListOf(1, 3, 5),
            hour = 3,
            minute = 0,
            enabled = true
        )

        val json = gson.toJson(schedule)
        assertNotNull(json)
        assertTrue(json.contains("Nightly Backup"))

        val deserialized = gson.fromJson(json, WolSchedule::class.java)
        assertEquals("sched-123", deserialized.id)
        assertEquals("NAS Server", deserialized.deviceName)
        assertEquals(3, deserialized.hour)
        assertEquals(listOf(1, 3, 5), deserialized.daysOfWeek)
    }

    @Test
    fun testWolBackup_fullSerialization() {
        val config = WolConfig(
            authToken = "secret_token_123",
            httpPort = 8085
        )
        val devices = listOf(
            WolDevice(id = "1", name = "PC", macAddress = "AA:BB:CC:DD:EE:FF")
        )
        val schedules = listOf(
            WolSchedule(id = "s1", name = "Morning", hour = 8, minute = 30)
        )

        val backup = WolBackup(
            version = "2.0.0",
            config = config,
            devices = devices,
            schedules = schedules
        )

        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, WolBackup::class.java)

        assertEquals("2.0.0", restored.version)
        assertEquals(1, restored.devices.size)
        assertEquals("PC", restored.devices[0].name)
        assertEquals(1, restored.schedules.size)
        assertEquals("Morning", restored.schedules[0].name)
        assertEquals("secret_token_123", restored.config.authToken)
    }
}
