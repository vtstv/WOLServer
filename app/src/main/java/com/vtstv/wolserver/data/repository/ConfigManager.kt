/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vtstv.wolserver.data.model.WolBackup
import com.vtstv.wolserver.data.model.WolConfig
import com.vtstv.wolserver.data.model.WolDevice
import com.vtstv.wolserver.data.model.WolSchedule
import java.util.UUID

/**
 * Configuration repository for the Simple WOL Server application.
 * Handles persistent storage of multi-device configuration, auto-wake schedules,
 * and backup/restore JSON serialization using SharedPreferences.
 */
class ConfigManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "wol_config"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_WEB_PASSWORD = "web_password"
        private const val KEY_DEVICES = "devices_json"
        private const val KEY_SCHEDULES = "schedules_json"
        private const val KEY_MAC_ADDRESS = "mac_address"
        private const val KEY_BROADCAST_ADDRESS = "broadcast_address"
        private const val KEY_WOL_PORT = "wol_port"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_IP_ALLOWLIST = "ip_allowlist"
        private const val KEY_HTTPS_ENABLED = "https_enabled"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_REQUIRE_AUTH = "require_auth"
    }

    fun loadConfig(): WolConfig {
        val legacyMac = prefs.getString(KEY_MAC_ADDRESS, "") ?: ""
        val devices = loadDevices()

        // Auto-migration: if no devices in list but legacy MAC exists, create first device
        if (devices.isEmpty() && legacyMac.isNotBlank()) {
            val defaultDevice = WolDevice(
                id = UUID.randomUUID().toString(),
                name = "Primary PC",
                macAddress = legacyMac,
                broadcastAddress = prefs.getString(KEY_BROADCAST_ADDRESS, "255.255.255.255") ?: "255.255.255.255",
                port = prefs.getInt(KEY_WOL_PORT, 9),
                iconType = "desktop"
            )
            devices.add(defaultDevice)
            saveDevices(devices)
        }

        return WolConfig(
            authToken = prefs.getString(KEY_AUTH_TOKEN, "") ?: "",
            webPassword = prefs.getString(KEY_WEB_PASSWORD, "admin123") ?: "admin123",
            devices = devices,
            broadcastAddress = prefs.getString(KEY_BROADCAST_ADDRESS, "255.255.255.255") ?: "255.255.255.255",
            wolPort = prefs.getInt(KEY_WOL_PORT, 9),
            httpPort = prefs.getInt(KEY_HTTP_PORT, 8085),
            ipAllowlist = loadIpAllowlist(),
            httpsEnabled = prefs.getBoolean(KEY_HTTPS_ENABLED, false),
            autoStartEnabled = prefs.getBoolean(KEY_AUTO_START, true),
            requireAuthentication = prefs.getBoolean(KEY_REQUIRE_AUTH, true),
            targetMacAddress = legacyMac
        )
    }

    fun saveConfig(config: WolConfig) {
        prefs.edit().apply {
            putString(KEY_AUTH_TOKEN, config.authToken)
            putString(KEY_WEB_PASSWORD, config.webPassword)
            putString(KEY_DEVICES, gson.toJson(config.devices))
            putString(KEY_BROADCAST_ADDRESS, config.broadcastAddress)
            putInt(KEY_WOL_PORT, config.wolPort)
            putInt(KEY_HTTP_PORT, config.httpPort)
            putString(KEY_IP_ALLOWLIST, gson.toJson(config.ipAllowlist))
            putBoolean(KEY_HTTPS_ENABLED, config.httpsEnabled)
            putBoolean(KEY_AUTO_START, config.autoStartEnabled)
            putBoolean(KEY_REQUIRE_AUTH, config.requireAuthentication)
            val firstMac = config.devices.firstOrNull()?.macAddress ?: config.targetMacAddress
            putString(KEY_MAC_ADDRESS, firstMac)
            apply()
        }
    }

    // --- Device Management ---
    fun loadDevices(): MutableList<WolDevice> {
        val json = prefs.getString(KEY_DEVICES, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<WolDevice>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveDevices(devices: List<WolDevice>) {
        prefs.edit().putString(KEY_DEVICES, gson.toJson(devices)).apply()
    }

    fun addOrUpdateDevice(device: WolDevice) {
        val devices = loadDevices()
        val index = devices.indexOfFirst { it.id == device.id }
        if (index >= 0) {
            devices[index] = device
        } else {
            devices.add(device)
        }
        saveDevices(devices)
    }

    fun deleteDevice(deviceId: String): Boolean {
        val devices = loadDevices()
        val removed = devices.removeAll { it.id == deviceId }
        if (removed) {
            saveDevices(devices)
        }
        return removed
    }

    fun updateDeviceLastWoken(deviceId: String) {
        val devices = loadDevices()
        val device = devices.find { it.id == deviceId }
        if (device != null) {
            device.lastWokenTimestamp = System.currentTimeMillis()
            saveDevices(devices)
        }
    }

    // --- Schedule Management ---
    fun loadSchedules(): MutableList<WolSchedule> {
        val json = prefs.getString(KEY_SCHEDULES, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<WolSchedule>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveSchedules(schedules: List<WolSchedule>) {
        prefs.edit().putString(KEY_SCHEDULES, gson.toJson(schedules)).apply()
    }

    fun addOrUpdateSchedule(schedule: WolSchedule) {
        val schedules = loadSchedules()
        val index = schedules.indexOfFirst { it.id == schedule.id }
        if (index >= 0) {
            schedules[index] = schedule
        } else {
            schedules.add(schedule)
        }
        saveSchedules(schedules)
    }

    fun deleteSchedule(scheduleId: String): Boolean {
        val schedules = loadSchedules()
        val removed = schedules.removeAll { it.id == scheduleId }
        if (removed) {
            saveSchedules(schedules)
        }
        return removed
    }

    fun updateScheduleLastRun(scheduleId: String, timestamp: Long) {
        val schedules = loadSchedules()
        val schedule = schedules.find { it.id == scheduleId }
        if (schedule != null) {
            schedule.lastRunTimestamp = timestamp
            saveSchedules(schedules)
        }
    }

    // --- Backup & Restore ---
    fun createBackup(): WolBackup {
        return WolBackup(
            config = loadConfig(),
            devices = loadDevices(),
            schedules = loadSchedules()
        )
    }

    fun restoreBackup(backup: WolBackup): Boolean {
        return try {
            saveConfig(backup.config)
            saveDevices(backup.devices)
            saveSchedules(backup.schedules)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadIpAllowlist(): List<String> {
        val json = prefs.getString(KEY_IP_ALLOWLIST, "[]") ?: "[]"
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun generateRandomToken(): String {
        val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32)
            .map { charset.random() }
            .joinToString("")
    }
}
