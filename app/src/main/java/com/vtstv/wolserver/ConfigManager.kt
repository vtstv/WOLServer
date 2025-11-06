package com.vtstv.wolserver

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.InetAddress

/**
 * Configuration manager for the Simple WOL Server application.
 * Handles persistent storage of configuration parameters using SharedPreferences.
 * 
 * Copyright (c) 2025 Murr
 * https://github.com/vtstv/wolserver
 */
data class WolConfig(
    var authToken: String = "default_token_change_me",
    var webPassword: String = "admin123",
    var targetMacAddress: String = "",
    var broadcastAddress: String = "255.255.255.255",
    var wolPort: Int = 9,
    var httpPort: Int = 8085,
    var ipAllowlist: List<String> = emptyList(),
    var httpsEnabled: Boolean = false,
    var autoStartEnabled: Boolean = true,
    var requireAuthentication: Boolean = true
) {
    fun isValidMacAddress(): Boolean {
        return targetMacAddress.matches(Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"))
    }
    
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

class ConfigManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val PREFS_NAME = "wol_config"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_WEB_PASSWORD = "web_password"
        private const val KEY_MAC_ADDRESS = "mac_address"
        private const val KEY_BROADCAST_ADDRESS = "broadcast_address"
        private const val KEY_WOL_PORT = "wol_port"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_IP_ALLOWLIST = "ip_allowlist"
        private const val KEY_HTTPS_ENABLED = "https_enabled"
        private const val KEY_AUTO_START = "auto_start"
    }
    
    fun loadConfig(): WolConfig {
        return WolConfig(
            authToken = prefs.getString(KEY_AUTH_TOKEN, "") ?: "",
            webPassword = prefs.getString(KEY_WEB_PASSWORD, "admin123") ?: "admin123",
            targetMacAddress = prefs.getString(KEY_MAC_ADDRESS, "") ?: "",
            broadcastAddress = prefs.getString(KEY_BROADCAST_ADDRESS, "255.255.255.255") ?: "255.255.255.255",
            wolPort = prefs.getInt(KEY_WOL_PORT, 9),
            httpPort = prefs.getInt(KEY_HTTP_PORT, 8085),
            ipAllowlist = loadIpAllowlist(),
            httpsEnabled = prefs.getBoolean(KEY_HTTPS_ENABLED, false),
            autoStartEnabled = prefs.getBoolean(KEY_AUTO_START, true)
        )
    }
    
    fun saveConfig(config: WolConfig) {
        prefs.edit().apply {
            putString(KEY_AUTH_TOKEN, config.authToken)
            putString(KEY_WEB_PASSWORD, config.webPassword)
            putString(KEY_MAC_ADDRESS, config.targetMacAddress)
            putString(KEY_BROADCAST_ADDRESS, config.broadcastAddress)
            putInt(KEY_WOL_PORT, config.wolPort)
            putInt(KEY_HTTP_PORT, config.httpPort)
            putString(KEY_IP_ALLOWLIST, gson.toJson(config.ipAllowlist))
            putBoolean(KEY_HTTPS_ENABLED, config.httpsEnabled)
            putBoolean(KEY_AUTO_START, config.autoStartEnabled)
            apply()
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