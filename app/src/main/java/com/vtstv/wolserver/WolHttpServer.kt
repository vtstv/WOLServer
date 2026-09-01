package com.vtstv.wolserver

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.util.*

/**
 * Embedded HTTP server for Simple WOL Server 2.0 Pro.
 * Hosts the Glassmorphism Web Dashboard with Mobile Optimization,
 * Live Ping & Liveness Checks, LAN Network Scanner, Auto-Wake Schedules,
 * Home Assistant / Apple Shortcuts Integration Generator, Backup & Restore JSON,
 * and 3-Language (English / Deutsch / Русский) support.
 * 
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
class WolHttpServer(
    private val context: Context,
    private val port: Int,
    private var config: WolConfig,
    private val configManager: ConfigManager,
    private val wakeOnLan: WakeOnLan,
    private val devicePinger: DevicePinger,
    private val networkScanner: NetworkScanner,
    private val scheduler: WolScheduler,
    private val serverIpAddress: String
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "WolHttpServer"
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val currentConfig: WolConfig
        get() = configManager.loadConfig()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val clientIp = session.remoteIpAddress

        Log.i(TAG, "Request: $method $uri from $clientIp")

        // Handle CORS preflight
        if (method == Method.OPTIONS) {
            return createCorsResponse(Response.Status.OK, "text/plain", "OK")
        }

        // IP allowlist check
        if (currentConfig.ipAllowlist.isNotEmpty() && !isIpAllowed(clientIp)) {
            Log.w(TAG, "IP $clientIp rejected by allowlist")
            return createCorsResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access denied")
        }

        val response = when {
            (uri == "/icon.png" || uri == "/favicon.ico") && method == Method.GET -> handleIcon()
            uri == "/" && method == Method.GET -> handleDashboardPage(session)
            uri == "/login" && method == Method.POST -> handleLogin(session)
            uri == "/health" && method == Method.GET -> handleHealth()
            uri == "/wake" && (method == Method.POST || method == Method.GET) -> handleWake(session)
            
            // Devices CRUD & Live Status
            uri == "/api/devices" && method == Method.GET -> handleGetDevices(session)
            uri == "/api/devices" && method == Method.POST -> handleSaveDevice(session)
            uri == "/api/devices" && (method == Method.DELETE || method == Method.POST) -> handleDeleteDevice(session)
            uri == "/api/devices/status" && method == Method.GET -> handleGetDevicesStatus(session)
            
            // Network Scanner
            uri == "/api/scan" && method == Method.GET -> handleScanNetwork(session)
            
            // Auto-Wake Schedules
            uri == "/api/schedules" && method == Method.GET -> handleGetSchedules(session)
            uri == "/api/schedules" && method == Method.POST -> handleSaveSchedule(session)
            uri == "/api/schedules" && (method == Method.DELETE || method == Method.POST) -> handleDeleteSchedule(session)
            
            // Backup & Restore
            uri == "/api/backup" && method == Method.GET -> handleGetBackup(session)
            uri == "/api/restore" && method == Method.POST -> handleRestoreBackup(session)
            
            // Logs
            uri == "/api/logs" && method == Method.GET -> handleGetLogs(session)
            uri == "/api/logs" && method == Method.DELETE -> handleClearLogs(session)
            
            // Server Config
            uri == "/config" && method == Method.GET -> handleGetConfig(session)
            uri == "/config" && method == Method.POST -> handleUpdateConfig(session)
            
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        return addCorsHeaders(response)
    }

    private fun handleIcon(): Response {
        return try {
            val inputStream = context.resources.openRawResource(R.drawable.wol_firetv)
            val bytes = inputStream.readBytes()
            inputStream.close()
            val resp = newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
            resp.addHeader("Cache-Control", "public, max-age=86400")
            resp
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Icon not found")
        }
    }

    private fun createCorsResponse(status: Response.Status, mimeType: String, txt: String): Response {
        val response = newFixedLengthResponse(status, mimeType, txt)
        return addCorsHeaders(response)
    }

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept, Origin, X-Requested-With")
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    private fun isIpAllowed(ip: String): Boolean {
        if (currentConfig.ipAllowlist.isEmpty()) return true
        val normalized = ip.trim()
        if (currentConfig.ipAllowlist.contains(normalized)) return true
        if (normalized == "127.0.0.1" || normalized == "::1" || normalized == "localhost") return true
        return false
    }

    private fun isAuthenticated(session: IHTTPSession): Boolean {
        if (!currentConfig.requireAuthentication) return true

        val authHeader = session.headers["authorization"] ?: session.headers["Authorization"]
        if (!authHeader.isNullOrBlank()) {
            if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                val token = authHeader.substring(7).trim()
                if (token == currentConfig.authToken) return true
            }
        }

        val tokenParam = session.parms["token"]
        if (!tokenParam.isNullOrBlank() && tokenParam == currentConfig.authToken) {
            return true
        }

        return false
    }

    private fun handleHealth(): Response {
        val devices = configManager.loadDevices()
        val schedules = configManager.loadSchedules()
        val response = mapOf(
            "status" to "OK",
            "server" to "Simple WOL Server 2.0 Pro",
            "version" to "2.0.0",
            "devicesCount" to devices.size,
            "schedulesCount" to schedules.size,
            "timestamp" to System.currentTimeMillis()
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
    }

    private fun handleLogin(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val loginRequest = gson.fromJson(postData, Map::class.java)
            val password = loginRequest["password"]?.toString() ?: ""

            if (password == currentConfig.webPassword) {
                val response = mapOf(
                    "success" to true,
                    "message" to "Login successful",
                    "authToken" to currentConfig.authToken
                )
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
            } else {
                val response = mapOf("success" to false, "message" to "Invalid password")
                newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", gson.toJson(response))
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message)))
        }
    }

    private fun handleGetDevices(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }
        val devices = configManager.loadDevices()
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(devices))
    }

    private fun handleGetDevicesStatus(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        val devices = configManager.loadDevices()
        var results = mapOf<String, DevicePinger.PingResult>()
        
        // Execute parallel ping probe
        kotlinx.coroutines.runBlocking {
            results = devicePinger.pingAll(devices)
        }

        val response = mapOf("success" to true, "status" to results)
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
    }

    private fun handleScanNetwork(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        var discovered = emptyList<NetworkScanner.DiscoveredDevice>()
        kotlinx.coroutines.runBlocking {
            discovered = networkScanner.scanLocalSubnet()
        }

        val response = mapOf("success" to true, "devices" to discovered, "count" to discovered.size)
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
    }

    private fun handleSaveDevice(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: "{}"
            val device = gson.fromJson(postData, WolDevice::class.java)

            if (!wakeOnLan.isValidMacAddress(device.macAddress)) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    gson.toJson(mapOf("success" to false, "message" to "Invalid MAC address: ${device.macAddress}")))
            }

            if (device.id.isBlank()) {
                device.id = UUID.randomUUID().toString()
            }
            if (device.name.isBlank()) {
                device.name = "Device ${device.macAddress.takeLast(5)}"
            }
            if (device.broadcastAddress.isBlank()) {
                device.broadcastAddress = "255.255.255.255"
            }
            if (device.port !in 1..65535) {
                device.port = 9
            }

            device.macAddress = wakeOnLan.formatMacAddress(device.macAddress)
            configManager.addOrUpdateDevice(device)

            newFixedLengthResponse(Response.Status.OK, "application/json",
                gson.toJson(mapOf("success" to true, "message" to "Device saved", "device" to device)))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message)))
        }
    }

    private fun handleDeleteDevice(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        var deviceId = session.parms["id"]
        if (deviceId.isNullOrBlank()) {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: "{}"
            val body = gson.fromJson(postData, Map::class.java)
            deviceId = body["id"]?.toString()
        }

        if (deviceId.isNullOrBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to "Device ID required")))
        }

        val removed = configManager.deleteDevice(deviceId)
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            gson.toJson(mapOf("success" to removed, "message" to if (removed) "Device deleted" else "Device not found")))
    }

    // --- Schedules REST Endpoints ---
    private fun handleGetSchedules(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }
        val schedules = configManager.loadSchedules()
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(schedules))
    }

    private fun handleSaveSchedule(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: "{}"
            val schedule = gson.fromJson(postData, WolSchedule::class.java)

            if (schedule.id.isBlank()) schedule.id = UUID.randomUUID().toString()
            if (schedule.name.isBlank()) schedule.name = "Auto Wake"
            if (schedule.hour !in 0..23) schedule.hour = 8
            if (schedule.minute !in 0..59) schedule.minute = 30
            if (schedule.daysOfWeek.isEmpty()) schedule.daysOfWeek = mutableListOf(1, 2, 3, 4, 5)

            // Resolve target name
            if (schedule.deviceId == "all" || schedule.deviceId.isBlank()) {
                schedule.deviceName = "All Devices"
            } else {
                val dev = configManager.loadDevices().find { it.id == schedule.deviceId }
                schedule.deviceName = dev?.name ?: "Target Device"
            }

            configManager.addOrUpdateSchedule(schedule)
            newFixedLengthResponse(Response.Status.OK, "application/json",
                gson.toJson(mapOf("success" to true, "message" to "Schedule saved", "schedule" to schedule)))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message)))
        }
    }

    private fun handleDeleteSchedule(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        var scheduleId = session.parms["id"]
        if (scheduleId.isNullOrBlank()) {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: "{}"
            val body = gson.fromJson(postData, Map::class.java)
            scheduleId = body["id"]?.toString()
        }

        if (scheduleId.isNullOrBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to "Schedule ID required")))
        }

        val removed = configManager.deleteSchedule(scheduleId)
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            gson.toJson(mapOf("success" to removed, "message" to if (removed) "Schedule deleted" else "Schedule not found")))
    }

    // --- Backup & Restore REST Endpoints ---
    private fun handleGetBackup(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        val backup = configManager.createBackup()
        val json = gson.toJson(backup)
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        resp.addHeader("Content-Disposition", "attachment; filename=\"wolserver-backup.json\"")
        return resp
    }

    private fun handleRestoreBackup(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: "{}"
            val backup = gson.fromJson(postData, WolBackup::class.java)

            if (backup.devices == null || backup.config == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    gson.toJson(mapOf("success" to false, "message" to "Invalid backup JSON structure")))
            }

            val success = configManager.restoreBackup(backup)
            this.config = configManager.loadConfig()

            newFixedLengthResponse(Response.Status.OK, "application/json",
                gson.toJson(mapOf("success" to success, "message" to if (success) "Configuration restored successfully" else "Restore failed")))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message)))
        }
    }

    private fun handleWake(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("success" to false, "message" to "Authentication required")))
        }

        val devices = configManager.loadDevices()
        val deviceId = session.parms["id"] ?: session.parms["deviceId"]
        val customMac = session.parms["mac"]

        val targetDevices = when {
            !deviceId.isNullOrBlank() -> {
                val found = devices.filter { it.id == deviceId }
                if (found.isEmpty()) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                        gson.toJson(mapOf("success" to false, "message" to "Device with ID '$deviceId' not found")))
                }
                found
            }
            !customMac.isNullOrBlank() -> {
                if (!wakeOnLan.isValidMacAddress(customMac)) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                        gson.toJson(mapOf("success" to false, "message" to "Invalid MAC address format: $customMac")))
                }
                listOf(WolDevice(name = "Direct Wake", macAddress = customMac))
            }
            devices.isNotEmpty() -> {
                devices
            }
            currentConfig.targetMacAddress.isNotBlank() -> {
                listOf(WolDevice(name = "Legacy Target", macAddress = currentConfig.targetMacAddress))
            }
            else -> {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    gson.toJson(mapOf("success" to false, "message" to "No devices or MAC addresses configured")))
            }
        }

        scope.launch {
            wakeOnLan.sendWakePackets(targetDevices)
            targetDevices.forEach { dev ->
                if (dev.id.isNotBlank()) configManager.updateDeviceLastWoken(dev.id)
            }
        }

        val summary = targetDevices.joinToString(", ") { "${it.name} (${it.macAddress})" }
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            gson.toJson(mapOf("success" to true, "message" to "Magic packet sent to $summary", "count" to targetDevices.size)))
    }

    private fun handleGetLogs(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        val logs = try {
            val command = arrayOf(
                "logcat",
                "-d",
                "-v", "time",
                "-s", "WolService:*,WolHttpServer:*,WakeOnLan:*,DevicePinger:*,NetworkScanner:*,WolScheduler:*,MainActivity:*,BootReceiver:*,System.err:*"
            )
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            var count = 0
            while (reader.readLine().also { line = it } != null && count < 600) {
                sb.append(line).append("\n")
                count++
            }
            reader.close()
            sb.toString().ifBlank { "No recent WOL logs recorded." }
        } catch (e: Exception) {
            "Error retrieving system logs: ${e.message}"
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json",
            gson.toJson(mapOf("success" to true, "logs" to logs)))
    }

    private fun handleClearLogs(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        try {
            Runtime.getRuntime().exec("logcat -c")
        } catch (e: Exception) {
            // Ignore
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            gson.toJson(mapOf("success" to true, "message" to "Logs cleared")))
    }

    private fun handleGetConfig(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        val current = configManager.loadConfig()
        val safeConfig = mapOf(
            "httpPort" to current.httpPort,
            "ipAllowlist" to current.ipAllowlist,
            "requireAuthentication" to current.requireAuthentication,
            "autoStartEnabled" to current.autoStartEnabled,
            "broadcastAddress" to current.broadcastAddress,
            "authToken" to current.authToken,
            "webPassword" to current.webPassword
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(safeConfig))
    }

    private fun handleUpdateConfig(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: "{}"
            val incoming = gson.fromJson(postData, WolConfig::class.java)

            val current = configManager.loadConfig()
            if (incoming.authToken.isNotBlank()) current.authToken = incoming.authToken
            if (incoming.webPassword.isNotBlank()) current.webPassword = incoming.webPassword
            if (incoming.httpPort in 1..65535) current.httpPort = incoming.httpPort
            current.requireAuthentication = incoming.requireAuthentication
            current.autoStartEnabled = incoming.autoStartEnabled
            if (incoming.broadcastAddress.isNotBlank()) current.broadcastAddress = incoming.broadcastAddress
            if (incoming.ipAllowlist != null) current.ipAllowlist = incoming.ipAllowlist

            configManager.saveConfig(current)
            this.config = current

            newFixedLengthResponse(Response.Status.OK, "application/json",
                gson.toJson(mapOf("success" to true, "message" to "Configuration saved successfully")))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message)))
        }
    }

    private fun handleDashboardPage(session: IHTTPSession): Response {
        val hostHeader = session.headers["host"] ?: "$serverIpAddress:$port"

        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Simple WOL Server 2.0 Pro</title>
    <link rel="icon" type="image/png" href="/icon.png">
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&family=JetBrains+Mono:wght@500;600&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-base: #0A0E17;
            --bg-card: rgba(21, 27, 43, 0.75);
            --bg-card-hover: rgba(28, 36, 56, 0.9);
            --border-glass: rgba(255, 255, 255, 0.08);
            --border-glow: rgba(0, 229, 255, 0.4);
            --accent-cyan: #00E5FF;
            --accent-orange: #FF9900;
            --accent-green: #00E676;
            --accent-red: #FF5252;
            --text-primary: #FFFFFF;
            --text-secondary: #94A3B8;
            --text-muted: #64748B;
        }

        * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Plus Jakarta Sans', sans-serif; -webkit-tap-highlight-color: transparent; }

        body {
            background-color: var(--bg-base);
            background-image: 
                radial-gradient(at 0% 0%, rgba(0, 229, 255, 0.12) 0px, transparent 50%),
                radial-gradient(at 100% 100%, rgba(255, 153, 0, 0.08) 0px, transparent 50%);
            background-attachment: fixed;
            color: var(--text-primary);
            min-height: 100vh;
            padding: 24px 16px;
        }

        .container { max-width: 1200px; margin: 0 auto; width: 100%; }

        /* Navbar */
        .navbar {
            display: flex;
            align-items: center;
            justify-content: space-between;
            flex-wrap: wrap;
            gap: 16px;
            padding: 16px 20px;
            background: var(--bg-card);
            backdrop-filter: blur(16px);
            border: 1px solid var(--border-glass);
            border-radius: 20px;
            margin-bottom: 24px;
            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
        }

        .brand { display: flex; align-items: center; gap: 12px; }
        .brand-img {
            width: 42px;
            height: 42px;
            border-radius: 12px;
            box-shadow: 0 0 16px rgba(0, 229, 255, 0.4);
            object-fit: contain;
            flex-shrink: 0;
        }
        .brand-text h1 { font-size: 18px; font-weight: 800; letter-spacing: -0.5px; }
        .brand-text p { font-size: 11px; color: var(--accent-cyan); font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; }

        .nav-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }

        .server-badge {
            display: flex;
            align-items: center;
            gap: 8px;
            background: rgba(0, 230, 118, 0.1);
            border: 1px solid rgba(0, 230, 118, 0.3);
            color: var(--accent-green);
            padding: 6px 12px;
            border-radius: 30px;
            font-size: 12px;
            font-weight: 600;
            font-family: 'JetBrains Mono', monospace;
        }
        .pulse-dot {
            width: 8px; height: 8px;
            background: var(--accent-green);
            border-radius: 50%;
            box-shadow: 0 0 10px var(--accent-green);
            animation: pulse 1.5s infinite;
        }
        @keyframes pulse {
            0% { opacity: 0.4; transform: scale(0.9); }
            50% { opacity: 1; transform: scale(1.1); }
            100% { opacity: 0.4; transform: scale(0.9); }
        }

        /* Buttons */
        .btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            padding: 10px 16px;
            min-height: 42px;
            border-radius: 12px;
            font-size: 14px;
            font-weight: 600;
            cursor: pointer;
            border: none;
            transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
            text-decoration: none;
            white-space: nowrap;
        }
        .btn-primary { background: var(--accent-cyan); color: #000; font-weight: 700; box-shadow: 0 0 20px rgba(0, 229, 255, 0.3); }
        .btn-primary:hover { background: #33ebff; transform: translateY(-2px); box-shadow: 0 4px 24px rgba(0, 229, 255, 0.5); }
        .btn-secondary { background: rgba(255, 255, 255, 0.06); color: #fff; border: 1px solid var(--border-glass); }
        .btn-secondary:hover { background: rgba(255, 255, 255, 0.12); border-color: var(--border-glow); }
        .btn-wake {
            background: linear-gradient(135deg, #FF9900 0%, #FF5500 100%);
            color: #fff;
            font-weight: 700;
            box-shadow: 0 0 20px rgba(255, 153, 0, 0.35);
        }
        .btn-wake:hover {
            background: linear-gradient(135deg, #FFAA22 0%, #FF6611 100%);
            transform: translateY(-2px);
            box-shadow: 0 4px 25px rgba(255, 153, 0, 0.6);
        }

        /* Hero */
        .quick-bar {
            display: flex;
            justify-content: space-between;
            align-items: center;
            flex-wrap: wrap;
            gap: 16px;
            background: var(--bg-card);
            backdrop-filter: blur(16px);
            border: 1px solid var(--border-glass);
            border-radius: 18px;
            padding: 20px 24px;
            margin-bottom: 28px;
        }
        .quick-info h2 { font-size: 19px; font-weight: 700; margin-bottom: 4px; }
        .quick-info p { font-size: 13px; color: var(--text-secondary); }
        .quick-actions { display: flex; flex-wrap: wrap; gap: 10px; }

        /* Grid */
        .section-title {
            font-size: 17px;
            font-weight: 700;
            letter-spacing: -0.3px;
            margin-bottom: 16px;
            display: flex;
            align-items: center;
            justify-content: space-between;
        }

        .device-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
            gap: 18px;
            margin-bottom: 36px;
        }

        /* Device Card */
        .device-card {
            background: var(--bg-card);
            backdrop-filter: blur(12px);
            border: 1px solid var(--border-glass);
            border-radius: 18px;
            padding: 20px;
            position: relative;
            transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
            overflow: hidden;
        }
        .device-card::before {
            content: '';
            position: absolute;
            top: 0; left: 0; right: 0; height: 3px;
            background: linear-gradient(90deg, var(--accent-cyan), var(--accent-orange));
            opacity: 0;
            transition: opacity 0.25s;
        }
        .device-card:hover {
            background: var(--bg-card-hover);
            border-color: var(--border-glow);
            transform: translateY(-4px);
            box-shadow: 0 14px 30px rgba(0, 0, 0, 0.5), 0 0 20px rgba(0, 229, 255, 0.15);
        }
        .device-card:hover::before { opacity: 1; }

        .card-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
        .device-icon-box {
            width: 44px; height: 44px;
            border-radius: 12px;
            background: rgba(0, 229, 255, 0.1);
            border: 1px solid rgba(0, 229, 255, 0.25);
            display: flex; align-items: center; justify-content: center;
            font-size: 22px;
        }
        .card-actions { display: flex; gap: 6px; }
        .icon-btn {
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid var(--border-glass);
            color: var(--text-secondary);
            border-radius: 8px;
            width: 36px; height: 36px;
            display: flex; align-items: center; justify-content: center;
            cursor: pointer;
            transition: all 0.2s;
        }
        .icon-btn:hover { color: #fff; background: rgba(255, 255, 255, 0.15); border-color: var(--border-glow); }

        /* Ping Status Badge */
        .status-pill {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 3px 8px;
            border-radius: 20px;
            font-size: 11px;
            font-weight: 600;
            margin-bottom: 6px;
        }
        .status-pill.online {
            background: rgba(0, 230, 118, 0.15);
            border: 1px solid rgba(0, 230, 118, 0.4);
            color: var(--accent-green);
        }
        .status-pill.offline {
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid var(--border-glass);
            color: var(--text-muted);
        }
        .status-dot {
            width: 6px; height: 6px; border-radius: 50%;
        }
        .status-pill.online .status-dot { background: var(--accent-green); box-shadow: 0 0 8px var(--accent-green); }
        .status-pill.offline .status-dot { background: var(--text-muted); }

        .device-name { font-size: 17px; font-weight: 700; margin-bottom: 4px; word-break: break-word; }
        .device-meta { display: flex; flex-direction: column; gap: 5px; margin-bottom: 16px; }
        .meta-row { display: flex; justify-content: space-between; font-size: 12.5px; }
        .meta-label { color: var(--text-muted); }
        .meta-val { font-family: 'JetBrains Mono', monospace; color: var(--text-secondary); font-weight: 500; font-size: 12px; }

        .card-footer { display: flex; gap: 10px; }
        .btn-card-wake { width: 100%; justify-content: center; padding: 12px; font-size: 15px; }

        /* Add Device Card */
        .add-card {
            border: 2px dashed var(--border-glass);
            background: rgba(22, 28, 45, 0.35);
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            gap: 12px;
            min-height: 200px;
            cursor: pointer;
            border-radius: 18px;
            transition: all 0.2s;
            color: var(--text-secondary);
            padding: 20px;
        }
        .add-card:hover {
            border-color: var(--accent-cyan);
            color: var(--accent-cyan);
            background: rgba(0, 229, 255, 0.05);
            transform: translateY(-4px);
        }
        .add-icon { font-size: 32px; }

        /* Settings Card & Form Layout */
        .settings-card {
            background: var(--bg-card);
            backdrop-filter: blur(16px);
            border: 1px solid var(--border-glass);
            border-radius: 18px;
            padding: 24px;
            margin-bottom: 36px;
        }
        .form-grid {
            display: grid;
            grid-template-columns: repeat(2, 1fr);
            gap: 18px;
            margin-bottom: 20px;
        }
        .form-group { display: flex; flex-direction: column; gap: 8px; }
        .form-group-full { grid-column: 1 / -1; }
        .form-group label { font-size: 13px; font-weight: 600; color: var(--text-secondary); }
        .form-control {
            background: rgba(10, 14, 23, 0.85);
            border: 1px solid var(--border-glass);
            color: #fff;
            padding: 12px 14px;
            border-radius: 10px;
            font-size: 14px;
            outline: none;
            transition: border-color 0.2s, box-shadow 0.2s;
            width: 100%;
        }
        .form-control:focus { border-color: var(--accent-cyan); box-shadow: 0 0 10px rgba(0, 229, 255, 0.2); }

        /* Token Input Group */
        .token-card {
            background: rgba(10, 14, 23, 0.85);
            border: 1px solid var(--border-glass);
            border-radius: 14px;
            padding: 14px 16px;
            display: flex;
            flex-direction: column;
            gap: 10px;
            transition: border-color 0.2s, box-shadow 0.2s;
        }
        .token-card:focus-within {
            border-color: var(--accent-cyan);
            box-shadow: 0 0 16px rgba(0, 229, 255, 0.2);
        }
        .token-input-row {
            display: flex;
            align-items: center;
            gap: 10px;
            flex-wrap: wrap;
        }
        .token-display {
            flex: 1;
            min-width: 240px;
            background: rgba(0, 0, 0, 0.35);
            border: 1px solid rgba(255, 255, 255, 0.05);
            border-radius: 8px;
            padding: 10px 14px;
            font-family: 'JetBrains Mono', monospace;
            font-size: 13.5px;
            letter-spacing: 0.5px;
            color: var(--accent-cyan);
            outline: none;
        }
        .token-actions {
            display: flex;
            gap: 8px;
            flex-shrink: 0;
        }
        .btn-token-action {
            background: rgba(255, 255, 255, 0.08);
            border: 1px solid var(--border-glass);
            color: #fff;
            padding: 9px 14px;
            font-size: 13px;
            font-weight: 600;
            border-radius: 8px;
            cursor: pointer;
            transition: all 0.2s;
            display: inline-flex;
            align-items: center;
            gap: 6px;
        }
        .btn-token-action:hover {
            background: rgba(0, 229, 255, 0.15);
            border-color: var(--accent-cyan);
            color: #fff;
        }
        .field-hint {
            font-size: 12px;
            color: var(--text-muted);
            line-height: 1.4;
        }

        .checkbox-group {
            display: flex; align-items: center; gap: 10px;
            background: rgba(10, 14, 23, 0.6);
            padding: 14px;
            border-radius: 10px;
            border: 1px solid var(--border-glass);
        }
        .checkbox-group input { width: 18px; height: 18px; accent-color: var(--accent-cyan); cursor: pointer; flex-shrink: 0; }
        .checkbox-group label { font-size: 13px; cursor: pointer; color: #fff; }

        /* Modals */
        .modal-overlay {
            position: fixed; inset: 0;
            background: rgba(0, 0, 0, 0.75);
            backdrop-filter: blur(8px);
            display: flex; align-items: center; justify-content: center;
            z-index: 1000;
            opacity: 0; pointer-events: none;
            transition: all 0.25s ease;
            padding: 16px;
        }
        .modal-overlay.active { opacity: 1; pointer-events: auto; }
        .modal {
            background: #151B2B;
            border: 1px solid var(--border-glass);
            border-radius: 20px;
            width: 100%; max-width: 500px;
            max-height: 90vh;
            overflow-y: auto;
            padding: 24px;
            transform: scale(0.95);
            transition: all 0.25s ease;
            box-shadow: 0 20px 50px rgba(0,0,0,0.6);
        }
        .modal-large { max-width: 820px; }
        .modal-overlay.active .modal { transform: scale(1); }
        .modal-header { margin-bottom: 18px; display: flex; align-items: center; justify-content: space-between; }
        .modal-header h3 { font-size: 19px; font-weight: 700; color: #fff; }
        .modal-footer { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 10px; margin-top: 20px; }

        /* Scan Results Table */
        .scan-table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 13px; }
        .scan-table th { text-align: left; padding: 10px; color: var(--text-muted); border-bottom: 1px solid var(--border-glass); }
        .scan-table td { padding: 12px 10px; border-bottom: 1px solid rgba(255, 255, 255, 0.04); vertical-align: middle; }
        .scan-table tr:hover { background: rgba(255, 255, 255, 0.03); }

        /* Integration Code Box */
        .code-tabs { display: flex; gap: 6px; margin-bottom: 12px; border-bottom: 1px solid var(--border-glass); padding-bottom: 8px; overflow-x: auto; }
        .code-tab-btn {
            background: rgba(255, 255, 255, 0.05); border: 1px solid transparent; color: var(--text-secondary);
            padding: 6px 12px; border-radius: 8px; font-size: 12px; font-weight: 600; cursor: pointer;
        }
        .code-tab-btn.active { background: rgba(0, 229, 255, 0.15); border-color: var(--accent-cyan); color: var(--accent-cyan); }
        .code-snippet-box {
            background: #080C14; border: 1px solid var(--border-glass); border-radius: 12px; padding: 14px;
            font-family: 'JetBrains Mono', monospace; font-size: 12px; color: #38BDF8; max-height: 280px; overflow-y: auto; white-space: pre-wrap; word-break: break-all;
        }

        /* Schedule Item Card */
        .schedule-card {
            background: rgba(10, 14, 23, 0.7); border: 1px solid var(--border-glass); border-radius: 14px; padding: 14px 16px;
            display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; gap: 12px;
        }

        /* Logs Console */
        .logs-console {
            background: #080C14;
            border: 1px solid var(--border-glass);
            border-radius: 12px;
            padding: 14px;
            font-family: 'JetBrains Mono', monospace;
            font-size: 12px;
            color: #E2E8F0;
            max-height: 380px;
            overflow-y: auto;
            white-space: pre-wrap;
            word-break: break-all;
            line-height: 1.5;
            user-select: text;
        }

        /* Toast */
        .toast-container { position: fixed; bottom: 20px; right: 20px; z-index: 2000; display: flex; flex-direction: column; gap: 8px; max-width: 90vw; }
        .toast {
            background: #1E293B;
            border: 1px solid var(--border-glass);
            color: #fff;
            padding: 12px 18px;
            border-radius: 12px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.5);
            animation: slideIn 0.3s forwards;
            font-size: 13px;
            font-weight: 500;
            display: flex; align-items: center; gap: 10px;
        }
        .toast.success { border-left: 4px solid var(--accent-green); }
        .toast.error { border-left: 4px solid var(--accent-red); }

        @keyframes slideIn {
            from { transform: translateX(100%); opacity: 0; }
            to { transform: translateX(0); opacity: 1; }
        }

        @keyframes floatAnim {
            0% { transform: translateY(0px) rotate(0deg); }
            100% { transform: translateY(-8px) rotate(3deg); }
        }

        @keyframes pulseGlow {
            0% { transform: scale(0.9); opacity: 0.5; }
            100% { transform: scale(1.2); opacity: 0.9; }
        }

        .login-box {
            max-width: 400px;
            width: 100%;
            margin: 60px auto;
            background: var(--bg-card);
            backdrop-filter: blur(16px);
            border: 1px solid var(--border-glass);
            border-radius: 20px;
            padding: 32px 24px;
            text-align: center;
        }
        .login-logo {
            width: 68px;
            height: 68px;
            border-radius: 18px;
            box-shadow: 0 0 24px rgba(0, 229, 255, 0.5);
            margin-bottom: 16px;
            object-fit: contain;
        }
        .about-logo {
            width: 64px;
            height: 64px;
            border-radius: 16px;
            box-shadow: 0 0 24px rgba(0, 229, 255, 0.6);
            object-fit: contain;
        }

        .hidden { display: none !important; }

        /* Mobile Responsiveness (< 768px) */
        @media (max-width: 768px) {
            body { padding: 16px 12px; }
            .navbar { padding: 14px 16px; flex-direction: column; align-items: stretch; gap: 12px; }
            .brand { justify-content: space-between; }
            .nav-actions { justify-content: space-between; width: 100%; }
            .nav-actions .btn { flex: 1; min-width: auto; padding: 8px 10px; font-size: 12.5px; }
            .quick-bar { flex-direction: column; align-items: stretch; padding: 18px; }
            .quick-actions { flex-direction: column; width: 100%; }
            .quick-actions .btn { width: 100%; }
            .device-grid { grid-template-columns: 1fr; }
            .form-grid { grid-template-columns: 1fr; }
            .token-input-row { flex-direction: column; align-items: stretch; }
            .token-display { width: 100%; }
            .token-actions { width: 100%; }
            .token-actions .btn-token-action { flex: 1; justify-content: center; }
            .modal { padding: 20px 16px; }
            .toast-container { left: 16px; right: 16px; bottom: 16px; }
            .toast { width: 100%; }
        }
    </style>
</head>
<body>
    <div class="container">

        <!-- Login Overlay -->
        <div id="loginScreen" class="login-box">
            <img src="/icon.png" class="login-logo" alt="Simple WOL Server">
            <h2 style="margin-bottom: 8px;" data-i18n="brandTitle">Simple WOL Server</h2>
            <p style="color: var(--text-secondary); font-size: 13px; margin-bottom: 24px;" data-i18n="enterPassword">Enter web password to access control panel</p>
            <div class="form-group" style="text-align: left; margin-bottom: 20px;">
                <label data-i18n="password">Password</label>
                <input type="password" id="loginPassword" class="form-control" placeholder="••••••••">
            </div>
            <button onclick="login()" class="btn btn-primary" style="width: 100%; justify-content: center;" data-i18n="unlockDashboard">Unlock Dashboard</button>
        </div>

        <!-- Main Dashboard -->
        <div id="dashboardScreen" class="hidden">
            <!-- Navbar -->
            <nav class="navbar">
                <div class="brand">
                    <div style="display: flex; align-items: center; gap: 12px;">
                        <img src="/icon.png" class="brand-img" alt="Simple WOL Server">
                        <div class="brand-text">
                            <h1 data-i18n="brandTitle">Simple WOL Server</h1>
                            <p data-i18n="brandSub">Fire TV / Android TV Edition</p>
                        </div>
                    </div>
                    <div class="server-badge">
                        <span class="pulse-dot"></span>
                        <span id="badgeAddress">$hostHeader</span>
                    </div>
                </div>
                <div class="nav-actions">
                    <button onclick="openScanModal()" class="btn btn-secondary" data-i18n="scanNetwork">🔍 Scan Network</button>
                    <button onclick="openSchedulesModal()" class="btn btn-secondary" data-i18n="schedules">⏰ Schedules</button>
                    <button onclick="openLogsModal()" class="btn btn-secondary" data-i18n="logs">📋 Logs</button>
                    <button onclick="toggleSettings()" class="btn btn-secondary" data-i18n="settings">⚙️ Settings</button>
                    <button onclick="openAboutModal()" class="btn btn-secondary" data-i18n="about">ℹ️ About</button>
                    <button onclick="logout()" class="btn btn-secondary" title="Logout">🚪</button>
                </div>
            </nav>

            <!-- Quick Action Hero -->
            <div class="quick-bar">
                <div class="quick-info">
                    <h2 data-i18n="heroTitle">Multi-Device Wake Control</h2>
                    <p data-i18n="heroSub">Send magic packets individually or wake all devices at once</p>
                </div>
                <div class="quick-actions">
                    <button onclick="wakeAll()" class="btn btn-wake" data-i18n="wakeAll">⚡ Wake All Devices</button>
                    <button onclick="openAddModal()" class="btn btn-primary" data-i18n="addDevice">➕ Add Device</button>
                </div>
            </div>

            <!-- Devices Grid -->
            <div class="section-title">
                <span><span data-i18n="configuredDevices">Configured Devices</span> (<span id="deviceCount">0</span>)</span>
                <div style="display: flex; gap: 8px;">
                    <button onclick="checkDevicesLiveness()" class="btn btn-secondary" style="padding: 6px 12px; font-size: 12px; min-height: 32px;" data-i18n="checkStatus">🟢 Ping Status</button>
                    <button onclick="loadDevices()" class="btn btn-secondary" style="padding: 6px 12px; font-size: 12px; min-height: 32px;" data-i18n="refresh">🔄 Refresh</button>
                </div>
            </div>

            <div id="devicesContainer" class="device-grid">
                <!-- Device cards rendered via JS -->
            </div>

        </div>
    </div>

    <!-- Server Settings Modal -->
    <div id="settingsModal" class="modal-overlay">
        <div class="modal modal-large">
            <div class="modal-header">
                <h3 data-i18n="serverSettingsTitle">⚙️ Server &amp; Security Settings</h3>
                <button onclick="closeSettingsModal()" class="icon-btn" style="border: none;">✖</button>
            </div>

            <div class="form-grid">
                <!-- Language Selection in Settings -->
                <div class="form-group">
                    <label data-i18n="languageSetting">🌐 Interface Language / Sprache / Язык</label>
                    <select id="cfgLanguage" onchange="setLanguage(this.value)" class="form-control" style="cursor: pointer;">
                        <option value="en">English</option>
                        <option value="de">Deutsch</option>
                        <option value="ru">Русский</option>
                    </select>
                </div>

                <!-- Web Password -->
                <div class="form-group">
                    <label data-i18n="webPassword">Web Dashboard Password</label>
                    <input type="password" id="cfgWebPassword" class="form-control">
                </div>

                <!-- API Authentication Token (Full-Width High-UX Card) -->
                <div class="form-group form-group-full">
                    <label data-i18n="apiToken">API Authentication Token (Bearer)</label>
                    <div class="token-card">
                        <div class="token-input-row">
                            <input type="text" id="cfgAuthToken" class="token-display" placeholder="Click generate for new token">
                            <div class="token-actions">
                                <button type="button" onclick="copyToken()" class="btn-token-action" data-i18n="copyToken">📋 Copy</button>
                                <button type="button" onclick="generateToken()" class="btn-token-action" data-i18n="generateToken">🎲 Generate</button>
                            </div>
                        </div>
                        <span class="field-hint" data-i18n="tokenHint">Bearer token for Home Assistant, Apple Shortcuts, and external REST API integrations (/wake, /api/devices).</span>
                    </div>
                </div>

                <!-- HTTP Port -->
                <div class="form-group">
                    <label data-i18n="httpPort">HTTP Server Port</label>
                    <input type="number" id="cfgHttpPort" class="form-control" value="8085">
                </div>

                <!-- Default Broadcast IP -->
                <div class="form-group">
                    <label data-i18n="broadcastIp">Default Broadcast IP</label>
                    <input type="text" id="cfgBroadcast" class="form-control" value="255.255.255.255">
                </div>

                <!-- IP Allowlist -->
                <div class="form-group form-group-full">
                    <label data-i18n="ipAllowlist">IP Allowlist (Comma separated, empty for all)</label>
                    <input type="text" id="cfgAllowlist" class="form-control" placeholder="192.168.1.100, 192.168.1.50">
                </div>

                <!-- Checkboxes -->
                <div class="form-group form-group-full">
                    <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 12px;">
                        <div class="checkbox-group">
                            <input type="checkbox" id="cfgRequireAuth">
                            <label for="cfgRequireAuth" data-i18n="requireAuth">Require Authentication Token for /wake endpoint</label>
                        </div>
                        <div class="checkbox-group">
                            <input type="checkbox" id="cfgAutoStart">
                            <label for="cfgAutoStart" data-i18n="autoStart">Auto-start Server on Device Boot</label>
                        </div>
                    </div>
                </div>

                <!-- Backup & Restore Section -->
                <div class="form-group form-group-full" style="border-top: 1px solid var(--border-glass); padding-top: 16px; margin-top: 8px;">
                    <label data-i18n="backupTitle">💾 Backup &amp; Restore Configuration</label>
                    <div style="display: flex; flex-wrap: wrap; gap: 10px; margin-top: 6px;">
                        <button type="button" onclick="downloadBackup()" class="btn btn-secondary" data-i18n="downloadBackup">💾 Download JSON Backup</button>
                        <button type="button" onclick="triggerRestoreUpload()" class="btn btn-secondary" data-i18n="restoreBackup">📥 Restore from JSON File</button>
                        <input type="file" id="restoreFileInput" accept=".json" style="display: none;" onchange="handleFileRestore(this)">
                    </div>
                </div>
            </div>

            <div class="modal-footer">
                <button onclick="closeSettingsModal()" class="btn btn-secondary" data-i18n="cancel">Cancel</button>
                <button onclick="saveServerConfig()" class="btn btn-primary" data-i18n="saveSettings">Save &amp; Apply</button>
            </div>
        </div>
    </div>

    <!-- Add/Edit Device Modal -->
    <div id="deviceModal" class="modal-overlay">
        <div class="modal">
            <div class="modal-header">
                <h3 id="modalTitle" data-i18n="addDeviceTitle">+ Add WoL Target Device</h3>
                <button onclick="closeModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <input type="hidden" id="devId">
            <div class="form-group" style="margin-bottom: 14px;">
                <label data-i18n="deviceName">Device Name</label>
                <input type="text" id="devName" class="form-control" placeholder="e.g. Gaming Rig">
            </div>
            <div class="form-group" style="margin-bottom: 14px;">
                <label data-i18n="macAddress">MAC Address</label>
                <input type="text" id="devMac" class="form-control" placeholder="AA:BB:CC:DD:EE:FF" oninput="formatMacInput(this)" style="font-family: 'JetBrains Mono', monospace;">
            </div>
            <div class="form-group" style="margin-bottom: 14px;">
                <label data-i18n="targetIp">Target IP Address (Optional, for Live Ping)</label>
                <input type="text" id="devIp" class="form-control" placeholder="192.168.0.100" style="font-family: 'JetBrains Mono', monospace;">
            </div>
            <div class="form-group" style="margin-bottom: 14px;">
                <label data-i18n="deviceType">Device Type / Icon</label>
                <select id="devIcon" class="form-control">
                    <option value="desktop" data-i18n="typeDesktop">🖥️ Desktop PC</option>
                    <option value="server" data-i18n="typeServer">🗄️ Home Server / NAS</option>
                    <option value="laptop" data-i18n="typeLaptop">💻 Laptop</option>
                    <option value="console" data-i18n="typeConsole">🎮 Game Console</option>
                    <option value="tv" data-i18n="typeTv">📺 Smart TV</option>
                </select>
            </div>
            <div class="form-grid" style="grid-template-columns: 2fr 1fr; margin-bottom: 0;">
                <div class="form-group">
                    <label data-i18n="broadcastIp">Broadcast Address</label>
                    <input type="text" id="devBroadcast" class="form-control" value="255.255.255.255">
                </div>
                <div class="form-group">
                    <label data-i18n="port">Port</label>
                    <input type="number" id="devPort" class="form-control" value="9">
                </div>
            </div>
            <div class="modal-footer">
                <button onclick="closeModal()" class="btn btn-secondary" data-i18n="cancel">Cancel</button>
                <button onclick="saveDeviceModal()" class="btn btn-primary" data-i18n="saveDevice">Save Device</button>
            </div>
        </div>
    </div>

    <!-- Network Scanner Modal -->
    <div id="scanModal" class="modal-overlay">
        <div class="modal modal-large">
            <div class="modal-header">
                <h3 data-i18n="scanTitle">🔍 LAN Network Device Scanner</h3>
                <button onclick="closeScanModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; flex-wrap: wrap; gap: 8px;">
                <span style="font-size: 13px; color: var(--text-secondary);" data-i18n="scanSubtitle">Scan local subnet to automatically discover PCs, servers, and MAC addresses.</span>
                <button onclick="startNetworkScan()" id="btnStartScan" class="btn btn-primary" style="padding: 6px 14px; font-size: 13px; min-height: 34px;" data-i18n="scanNow">🔍 Scan Subnet</button>
            </div>
            <div id="scanResultsContainer">
                <p style="color: var(--text-muted); font-size: 13px; text-align: center; padding: 24px;" data-i18n="scanPrompt">Click 'Scan Subnet' to discover devices on your local network.</p>
            </div>
            <div class="modal-footer">
                <button onclick="closeScanModal()" class="btn btn-secondary" data-i18n="close">Close</button>
            </div>
        </div>
    </div>

    <!-- Schedules Modal -->
    <div id="schedulesModal" class="modal-overlay">
        <div class="modal modal-large">
            <div class="modal-header">
                <h3 data-i18n="schedulesTitle">⏰ Auto-Wake Schedules &amp; Timers</h3>
                <button onclick="closeSchedulesModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 8px;">
                <span style="font-size: 13px; color: var(--text-secondary);" data-i18n="schedulesSubtitle">Automatically wake PCs and servers on designated days and times.</span>
                <button onclick="openAddScheduleModal()" class="btn btn-primary" style="padding: 6px 14px; font-size: 13px; min-height: 34px;" data-i18n="addSchedule">➕ Add Schedule</button>
            </div>
            <div id="schedulesContainer">
                <p style="color: var(--text-muted); font-size: 13px; text-align: center; padding: 20px;">Loading schedules...</p>
            </div>
            <div class="modal-footer">
                <button onclick="closeSchedulesModal()" class="btn btn-secondary" data-i18n="close">Close</button>
            </div>
        </div>
    </div>

    <!-- Add/Edit Schedule Modal -->
    <div id="editScheduleModal" class="modal-overlay" style="z-index: 1100;">
        <div class="modal">
            <div class="modal-header">
                <h3 id="schedModalTitle" data-i18n="addScheduleTitle">+ Add Auto-Wake Schedule</h3>
                <button onclick="closeEditScheduleModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <input type="hidden" id="schedId">
            <div class="form-group" style="margin-bottom: 12px;">
                <label data-i18n="scheduleName">Schedule Name</label>
                <input type="text" id="schedName" class="form-control" placeholder="e.g. Work Morning Wake">
            </div>
            <div class="form-group" style="margin-bottom: 12px;">
                <label data-i18n="targetDevice">Target Device</label>
                <select id="schedDevice" class="form-control"></select>
            </div>
            <div class="form-grid" style="grid-template-columns: 1fr 1fr; margin-bottom: 12px;">
                <div class="form-group">
                    <label data-i18n="timeHour">Hour (0-23)</label>
                    <input type="number" id="schedHour" class="form-control" min="0" max="23" value="8">
                </div>
                <div class="form-group">
                    <label data-i18n="timeMinute">Minute (0-59)</label>
                    <input type="number" id="schedMinute" class="form-control" min="0" max="59" value="30">
                </div>
            </div>
            <div class="form-group" style="margin-bottom: 14px;">
                <label data-i18n="activeDays">Active Days</label>
                <div style="display: flex; gap: 6px; flex-wrap: wrap;" id="schedDaysContainer">
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="1" checked> Mon
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="2" checked> Tue
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="3" checked> Wed
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="4" checked> Thu
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="5" checked> Fri
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="6"> Sat
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="7"> Sun
                    </label>
                </div>
            </div>
            <div class="modal-footer">
                <button onclick="closeEditScheduleModal()" class="btn btn-secondary" data-i18n="cancel">Cancel</button>
                <button onclick="saveScheduleModal()" class="btn btn-primary" data-i18n="saveSchedule">Save Schedule</button>
            </div>
        </div>
    </div>

    <!-- Integrations Generator Modal -->
    <div id="integrationModal" class="modal-overlay">
        <div class="modal modal-large">
            <div class="modal-header">
                <h3 id="integrationTitle" data-i18n="integrationTitle">🔗 Home Assistant &amp; API Integration</h3>
                <button onclick="closeIntegrationModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <div class="code-tabs">
                <button onclick="switchTab('ha')" id="tabHa" class="code-tab-btn active">Home Assistant (YAML)</button>
                <button onclick="switchTab('shortcuts')" id="tabShortcuts" class="code-tab-btn">Apple Shortcuts (Webhook)</button>
                <button onclick="switchTab('curl')" id="tabCurl" class="code-tab-btn">cURL / Shell</button>
                <button onclick="switchTab('python')" id="tabPython" class="code-tab-btn">Python</button>
            </div>
            <div id="snippetContainer" class="code-snippet-box"></div>
            <div class="modal-footer" style="justify-content: space-between;">
                <span style="font-size: 12px; color: var(--text-muted);" data-i18n="integrationHint">Copy snippet into your home automation platform.</span>
                <div style="display: flex; gap: 8px;">
                    <button onclick="copySnippet()" class="btn btn-primary" style="padding: 8px 16px;" data-i18n="copySnippet">📋 Copy Snippet</button>
                    <button onclick="closeIntegrationModal()" class="btn btn-secondary" data-i18n="close">Close</button>
                </div>
            </div>
        </div>
    </div>

    <!-- Logs Viewer Modal -->
    <div id="logsModal" class="modal-overlay">
        <div class="modal modal-large">
            <div class="modal-header">
                <h3 data-i18n="logsTitle">📋 Server &amp; Network Logs</h3>
                <button onclick="closeLogsModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; flex-wrap: wrap; gap: 8px;">
                <span style="font-size: 12px; color: var(--text-secondary);" data-i18n="logsSubtitle">Live logcat entries for WOL service, scheduler, scanner, and HTTP daemon</span>
                <div style="display: flex; gap: 6px;">
                    <button onclick="fetchLogs()" class="btn btn-secondary" style="padding: 6px 12px; font-size: 12px; min-height: 32px;" data-i18n="refresh">🔄 Refresh</button>
                    <button onclick="copyLogs()" class="btn btn-secondary" style="padding: 6px 12px; font-size: 12px; min-height: 32px;" data-i18n="copyLogs">📋 Copy</button>
                    <button onclick="clearServerLogs()" class="btn btn-secondary" style="padding: 6px 12px; font-size: 12px; min-height: 32px; color: var(--accent-red);" data-i18n="clearLogs">🗑️ Clear</button>
                </div>
            </div>
            <div id="logsConsole" class="logs-console">Loading system logs...</div>
            <div class="modal-footer">
                <button onclick="closeLogsModal()" class="btn btn-primary" data-i18n="close">Close</button>
            </div>
        </div>
    </div>

    <!-- About Modal -->
    <div id="aboutModal" class="modal-overlay">
        <div class="modal" style="text-align: center; max-width: 440px;">
            <div style="position: relative; width: 80px; height: 80px; margin: 0 auto 16px auto;">
                <div style="position: absolute; inset: 0; background: radial-gradient(circle, rgba(0,229,255,0.4) 0%, transparent 70%); border-radius: 50%; animation: pulseGlow 2s infinite alternate;"></div>
                <img src="/icon.png" class="about-logo" style="position: relative; margin: 8px auto; animation: floatAnim 2.5s ease-in-out infinite alternate;" alt="Simple WOL Server">
            </div>
            <h2 style="font-size: 22px; font-weight: 800; margin-bottom: 4px;" data-i18n="aboutTitle">Simple WOL Server</h2>
            <p style="color: var(--accent-cyan); font-size: 13px; font-weight: 600; margin-bottom: 14px;" data-i18n="aboutVersion">Version 2.0.0 (Fire TV Edition)</p>
            <p style="color: var(--text-secondary); font-size: 13px; line-height: 1.5; margin-bottom: 20px;" data-i18n="aboutDesc">
                Lightweight Wake-on-LAN daemon &amp; multi-device management hub designed for Amazon Fire TV and Android TV.
            </p>
            <div style="background: rgba(10, 14, 23, 0.7); border: 1px solid var(--border-glass); border-radius: 14px; padding: 16px; margin-bottom: 20px;">
                <div style="font-size: 12px; color: var(--text-muted); margin-bottom: 4px;" data-i18n="createdBy">Created with ❤️ by</div>
                <div style="font-size: 18px; font-weight: 800; color: var(--accent-orange); margin-bottom: 8px;">Murr</div>
                <div style="margin-bottom: 6px;">
                    <a href="https://github.com/vtstv" target="_blank" style="color: var(--accent-cyan); text-decoration: none; font-size: 13px; font-family: monospace;">🌐 github.com/vtstv</a>
                </div>
                <div style="margin-bottom: 8px;">
                    <a href="https://github.com/vtstv/wolserver" target="_blank" style="color: var(--text-secondary); text-decoration: none; font-size: 12px; font-family: monospace;">📦 github.com/vtstv/wolserver</a>
                </div>
                <div style="font-size: 11px; color: var(--text-muted);" data-i18n="allRightsReserved">Copyright © 2025-2026 Murr. All rights reserved.</div>
            </div>
            <button onclick="closeAboutModal()" class="btn btn-primary" style="width: 140px; justify-content: center;" data-i18n="close">Close</button>
        </div>
    </div>

    <!-- Toast container -->
    <div id="toastContainer" class="toast-container"></div>

    <script>
        const i18n = {
            en: {
                brandTitle: "Simple WOL Server",
                brandSub: "Fire TV / Android TV Edition",
                unlockDashboard: "Unlock Dashboard",
                enterPassword: "Enter web password to access control panel",
                password: "Password",
                heroTitle: "Multi-Device Wake Control",
                heroSub: "Send magic packets individually or wake all devices at once",
                wakeAll: "⚡ Wake All Devices",
                addDevice: "➕ Add Device",
                scanNetwork: "🔍 Scan Network",
                schedules: "⏰ Schedules",
                configuredDevices: "Configured Devices",
                refresh: "🔄 Refresh",
                checkStatus: "🟢 Ping Status",
                settings: "⚙️ Settings",
                logs: "📋 Logs",
                about: "ℹ️ About",
                wake: "⚡ Wake",
                edit: "Edit",
                delete: "Delete",
                integrate: "Integrate",
                online: "Online",
                offline: "Offline",
                addNewDevice: "Add New Device",
                configureWoL: "Configure WoL Target",
                addDeviceTitle: "+ Add WoL Target Device",
                editDeviceTitle: "Edit WoL Target Device",
                deviceName: "Device Name",
                macAddress: "Target MAC Address",
                targetIp: "Target IP Address (Optional, for Live Ping)",
                deviceType: "Device Type / Icon",
                broadcastIp: "Broadcast IP",
                port: "Port",
                cancel: "Cancel",
                saveDevice: "Save Device",
                typeDesktop: "🖥️ Desktop PC",
                typeServer: "🗄️ Home Server / NAS",
                typeLaptop: "💻 Laptop",
                typeConsole: "🎮 Game Console",
                typeTv: "📺 Smart TV",
                serverSettingsTitle: "⚙️ Server & Security Settings",
                languageSetting: "🌐 Interface Language",
                webPassword: "Web Dashboard Password",
                apiToken: "API Authentication Token (Bearer)",
                tokenHint: "Bearer token for Home Assistant, Apple Shortcuts, and external REST API integrations (/wake, /api/devices).",
                copyToken: "📋 Copy",
                generateToken: "🎲 Generate",
                httpPort: "HTTP Server Port",
                ipAllowlist: "IP Allowlist (Comma separated, empty for all)",
                requireAuth: "Require Authentication Token for /wake endpoint",
                autoStart: "Auto-start Server on Device Boot",
                backupTitle: "💾 Backup & Restore Configuration",
                downloadBackup: "💾 Download JSON Backup",
                restoreBackup: "📥 Restore from JSON File",
                saveSettings: "Save & Apply",
                scanTitle: "🔍 LAN Network Device Scanner",
                scanSubtitle: "Scan local subnet to automatically discover PCs, servers, and MAC addresses.",
                scanNow: "🔍 Scan Subnet",
                scanPrompt: "Click 'Scan Subnet' to discover devices on your local network.",
                scanning: "Scanning local subnet for active devices...",
                schedulesTitle: "⏰ Auto-Wake Schedules & Timers",
                schedulesSubtitle: "Automatically wake PCs and servers on designated days and times.",
                addSchedule: "➕ Add Schedule",
                addScheduleTitle: "+ Add Auto-Wake Schedule",
                scheduleName: "Schedule Name",
                targetDevice: "Target Device",
                timeHour: "Hour (0-23)",
                timeMinute: "Minute (0-59)",
                activeDays: "Active Days",
                saveSchedule: "Save Schedule",
                integrationTitle: "🔗 Home Assistant & API Integration",
                integrationHint: "Copy snippet into your home automation platform.",
                copySnippet: "📋 Copy Snippet",
                logsTitle: "📋 Server & Network Logs",
                logsSubtitle: "Live logcat entries for WOL service, scheduler, scanner, and HTTP daemon",
                copyLogs: "📋 Copy Logs",
                clearLogs: "🗑️ Clear Logs",
                aboutTitle: "Simple WOL Server",
                aboutVersion: "Version 2.0.0 (Fire TV Edition)",
                aboutDesc: "Lightweight Wake-on-LAN daemon & multi-device management hub designed for Amazon Fire TV and Android TV.",
                createdBy: "Created with ❤️ by",
                allRightsReserved: "Copyright © 2025-2026 Murr. All rights reserved.",
                close: "Close",
                toastWakeSuccess: "Magic packet sent to {name}!",
                toastWakeAllSuccess: "Magic packets sent to all {count} devices!",
                toastSaveSuccess: "Device '{name}' saved successfully!",
                toastDeleteSuccess: "Device '{name}' deleted.",
                toastScheduleSaved: "Schedule '{name}' saved successfully!",
                toastScheduleDeleted: "Schedule deleted.",
                toastSettingsSuccess: "Server configuration updated successfully!",
                toastLoginSuccess: "Logged in successfully",
                toastLoginFailed: "Invalid password",
                toastTokenCopied: "API token copied to clipboard!",
                toastLogsCopied: "Logs copied to clipboard!",
                toastSnippetCopied: "Integration snippet copied to clipboard!",
                toastBackupDownloaded: "Configuration backup downloaded!",
                toastRestoreSuccess: "Configuration restored successfully!",
                confirmDelete: "Are you sure you want to remove '{name}'?"
            },
            de: {
                brandTitle: "Simple WOL Server",
                brandSub: "Fire TV / Android TV Edition",
                unlockDashboard: "Dashboard Entsperren",
                enterPassword: "Web-Passwort eingeben, um das Dashboard zu öffnen",
                password: "Passwort",
                heroTitle: "Multi-Geräte WoL Steuerung",
                heroSub: "Magic Packets einzeln senden oder alle Geräte gleichzeitig wecken",
                wakeAll: "⚡ Alle Geräte Wecken",
                addDevice: "➕ Gerät Hinzufügen",
                scanNetwork: "🔍 Netzwerk Scannen",
                schedules: "⏰ Zeitpläne",
                configuredDevices: "Konfigurierte Geräte",
                refresh: "🔄 Aktualisieren",
                checkStatus: "🟢 Ping-Status",
                settings: "⚙️ Einstellungen",
                logs: "📋 Protokolle",
                about: "ℹ️ Über",
                wake: "⚡ Wecken",
                edit: "Bearbeiten",
                delete: "Löschen",
                integrate: "Integrieren",
                online: "Online",
                offline: "Offline",
                addNewDevice: "Neues Gerät Hinzufügen",
                configureWoL: "WoL-Ziel Konfigurieren",
                addDeviceTitle: "+ WoL-Zielgerät Hinzufügen",
                editDeviceTitle: "WoL-Zielgerät Bearbeiten",
                deviceName: "Gerätename",
                macAddress: "Ziel-MAC-Adresse",
                targetIp: "Ziel-IP-Adresse (Optional, für Live-Ping)",
                deviceType: "Gerätetyp / Symbol",
                broadcastIp: "Broadcast-IP",
                port: "Port",
                cancel: "Abbrechen",
                saveDevice: "Speichern",
                typeDesktop: "🖥️ Desktop-PC",
                typeServer: "🗄️ Heimserver / NAS",
                typeLaptop: "💻 Laptop",
                typeConsole: "🎮 Spielkonsole",
                typeTv: "📺 Smart-TV",
                serverSettingsTitle: "⚙️ Server- & Sicherheitseinstellungen",
                languageSetting: "🌐 Sprache der Benutzeroberfläche",
                webPassword: "Web-Dashboard Passwort",
                apiToken: "API-Authentifizierungstoken (Bearer)",
                tokenHint: "Bearer-Token für Home Assistant, Apple Kurzbefehle und REST-API-Integrationen (/wake, /api/devices).",
                copyToken: "📋 Kopieren",
                generateToken: "🎲 Neu generieren",
                httpPort: "Integrierter HTTP-Server Port",
                ipAllowlist: "IP-Erlaubnisliste (Kommagetrennt, leer für alle)",
                requireAuth: "Token für /wake Endpunkt erforderlich",
                autoStart: "Server beim Gerätestart ausführen",
                backupTitle: "💾 Sicherung & Wiederherstellung",
                downloadBackup: "💾 JSON-Backup Herunterladen",
                restoreBackup: "📥 Aus JSON-Datei Wiederherstellen",
                saveSettings: "Speichern & Anwenden",
                scanTitle: "🔍 LAN-Netzwerk Scanner",
                scanSubtitle: "Lokales Subnetz scannen, um PCs, Server und MAC-Adressen zu finden.",
                scanNow: "🔍 Subnetz Scannen",
                scanPrompt: "Klicken Sie auf 'Subnetz Scannen', um Geräte im LAN zu entdecken.",
                scanning: "Subnetz wird nach aktiven Geräten durchsucht...",
                schedulesTitle: "⏰ Automatische WoL-Zeitpläne",
                schedulesSubtitle: "Geräte automatisch zu festgelegten Tagen und Uhrzeiten wecken.",
                addSchedule: "➕ Zeitplan Hinzufügen",
                addScheduleTitle: "+ WoL-Zeitplan Hinzufügen",
                scheduleName: "Zeitplan-Name",
                targetDevice: "Zielgerät",
                timeHour: "Stunde (0-23)",
                timeMinute: "Minute (0-59)",
                activeDays: "Aktive Tage",
                saveSchedule: "Zeitplan Speichern",
                integrationTitle: "🔗 Home Assistant & API-Integration",
                integrationHint: "Code-Snippet in Ihre Smart-Home-Zentrale einfügen.",
                copySnippet: "📋 Snippet Kopieren",
                logsTitle: "📋 Server- & Netzwerkprotokolle",
                logsSubtitle: "Live-Logcat-Einträge für WOL-Dienst, Scheduler, Scanner und HTTP-Daemon",
                copyLogs: "📋 Kopieren",
                clearLogs: "🗑️ Löschen",
                aboutTitle: "Simple WOL Server",
                aboutVersion: "Version 2.0.0 (Fire TV Edition)",
                aboutDesc: "Leistungsstarker Wake-on-LAN-Daemon & Multi-Geräte-Hub für Amazon Fire TV und Android TV.",
                createdBy: "Erstellt mit ❤️ von",
                allRightsReserved: "Copyright © 2025-2026 Murr. Alle Rechte vorbehalten.",
                close: "Schließen",
                toastWakeSuccess: "Magic Packet an {name} gesendet!",
                toastWakeAllSuccess: "Magic Packets an alle {count} Geräte gesendet!",
                toastSaveSuccess: "Gerät '{name}' erfolgreich gespeichert!",
                toastDeleteSuccess: "Gerät '{name}' gelöscht.",
                toastScheduleSaved: "Zeitplan '{name}' gespeichert!",
                toastScheduleDeleted: "Zeitplan gelöscht.",
                toastSettingsSuccess: "Server-Konfiguration erfolgreich aktualisiert!",
                toastLoginSuccess: "Erfolgreich angemeldet",
                toastLoginFailed: "Ungültiges Passwort",
                toastTokenCopied: "API-Token in die Zwischenablage kopiert!",
                toastLogsCopied: "Protokolle in die Zwischenablage kopiert!",
                toastSnippetCopied: "Code-Snippet in die Zwischenablage kopiert!",
                toastBackupDownloaded: "Backup-Datei heruntergeladen!",
                toastRestoreSuccess: "Konfiguration erfolgreich wiederhergestellt!",
                confirmDelete: "Möchten Sie '{name}' wirklich entfernen?"
            },
            ru: {
                brandTitle: "Simple WOL Server",
                brandSub: "Fire TV / Android TV Edition",
                unlockDashboard: "Войти в панель",
                enterPassword: "Введите пароль для доступа к веб-панели управления",
                password: "Пароль",
                heroTitle: "Управление устройствами WoL",
                heroSub: "Отправляйте Magic Packet на отдельные устройства или разбудите все сразу",
                wakeAll: "⚡ Разбудить все",
                addDevice: "➕ Добавить",
                scanNetwork: "🔍 Поиск в сети",
                schedules: "⏰ Расписание",
                configuredDevices: "Настроенные устройства",
                refresh: "🔄 Обновить",
                checkStatus: "🟢 Пинг / Статус",
                settings: "⚙️ Настройки",
                logs: "📋 Логи",
                about: "ℹ️ О программе",
                wake: "⚡ Разбудить",
                edit: "Редактировать",
                delete: "Удалить",
                integrate: "Интеграция",
                online: "В сети",
                offline: "Не в сети",
                addNewDevice: "Добавить устройство",
                configureWoL: "Настроить цель WoL",
                addDeviceTitle: "+ Добавить устройство WoL",
                editDeviceTitle: "Редактировать устройство WoL",
                deviceName: "Имя устройства",
                macAddress: "Целевой MAC-адрес",
                targetIp: "IP-адрес цели (для проверки онлайн-статуса)",
                deviceType: "Тип устройства / Иконка",
                broadcastIp: "Широковещательный IP",
                port: "Порт",
                cancel: "Отмена",
                saveDevice: "Сохранить",
                typeDesktop: "🖥️ Настольный ПК",
                typeServer: "🗄️ Сервер / NAS",
                typeLaptop: "💻 Ноутбук",
                typeConsole: "🎮 Консоль",
                typeTv: "📺 Smart TV",
                serverSettingsTitle: "⚙️ Настройки сервера и защиты",
                languageSetting: "🌐 Язык интерфейса",
                webPassword: "Пароль веб-панели",
                apiToken: "Токен авторизации API (Bearer)",
                tokenHint: "Bearer токен для интеграции с Home Assistant, Apple Shortcuts и REST API (/wake, /api/devices).",
                copyToken: "📋 Скопировать",
                generateToken: "🎲 Создать",
                httpPort: "Порт HTTP сервера",
                ipAllowlist: "Белый список IP (через запятую, пусто = все)",
                requireAuth: "Требовать токен для /wake",
                autoStart: "Автозапуск при включении устройства",
                backupTitle: "💾 Резервное копирование и восстановление",
                downloadBackup: "💾 Скачать JSON бэкап",
                restoreBackup: "📥 Восстановить из файла JSON",
                saveSettings: "Сохранить и применить",
                scanTitle: "🔍 Автопоиск устройств в локальной сети",
                scanSubtitle: "Сканирование подсети для автоматического обнаружения ПК, серверов и MAC-адресов.",
                scanNow: "🔍 Сканировать подсеть",
                scanPrompt: "Нажмите 'Сканировать подсеть', чтобы найти активные устройства в сети.",
                scanning: "Сканирование локальной сети...",
                schedulesTitle: "⏰ Расписание и автозапуск WoL",
                schedulesSubtitle: "Автоматическое пробуждение ПК и серверов по дням недели и времени.",
                addSchedule: "➕ Добавить расписание",
                addScheduleTitle: "+ Добавить автопробуждение",
                scheduleName: "Название расписания",
                targetDevice: "Целевое устройство",
                timeHour: "Час (0-23)",
                timeMinute: "Минута (0-59)",
                activeDays: "Дни недели",
                saveSchedule: "Сохранить расписание",
                integrationTitle: "🔗 Интеграция с Home Assistant и API",
                integrationHint: "Скопируйте готовый блок в систему умного дома.",
                copySnippet: "📋 Скопировать код",
                logsTitle: "📋 Системные логи сервера",
                logsSubtitle: "Записи logcat для службы WOL, планировщика, сканера и веб-сервера",
                copyLogs: "📋 Скопировать",
                clearLogs: "🗑️ Очистить",
                aboutTitle: "Simple WOL Server",
                aboutVersion: "Версия 2.0.0 (Fire TV Edition)",
                aboutDesc: "Легковесный Wake-on-LAN демон и центр управления устройствами для Amazon Fire TV и Android TV.",
                createdBy: "Создано с ❤️ автором",
                allRightsReserved: "Copyright © 2025-2026 Murr. Все права защищены.",
                close: "Закрыть",
                toastWakeSuccess: "Magic Packet отправлен на {name}!",
                toastWakeAllSuccess: "Magic Packet отправлен на все {count} устройств!",
                toastSaveSuccess: "Устройство '{name}' сохранено!",
                toastDeleteSuccess: "Устройство '{name}' удалено.",
                toastScheduleSaved: "Расписание '{name}' сохранено!",
                toastScheduleDeleted: "Расписание удалено.",
                toastSettingsSuccess: "Настройки сервера успешно применены!",
                toastLoginSuccess: "Вход выполнен успешно",
                toastLoginFailed: "Неверный пароль",
                toastTokenCopied: "API токен скопирован в буфер обмена!",
                toastLogsCopied: "Логи скопированы в буфер обмена!",
                toastSnippetCopied: "Код интеграции скопирован!",
                toastBackupDownloaded: "Резервная копия сохранена!",
                toastRestoreSuccess: "Конфигурация успешно восстановлена!",
                confirmDelete: "Вы уверены, что хотите удалить '{name}'?"
            }
        };

        function getDefaultLanguage() {
            const navLang = (navigator.language || navigator.userLanguage || 'en').toLowerCase();
            if (navLang.startsWith('ru')) return 'ru';
            if (navLang.startsWith('de')) return 'de';
            return 'en';
        }

        let currentLang = localStorage.getItem('wol_lang') || getDefaultLanguage();

        function setLanguage(lang) {
            if (!i18n[lang]) lang = 'en';
            currentLang = lang;
            localStorage.setItem('wol_lang', lang);
            const selectEl = document.getElementById('cfgLanguage');
            if (selectEl) selectEl.value = lang;

            document.querySelectorAll('[data-i18n]').forEach(el => {
                const key = el.getAttribute('data-i18n');
                if (i18n[lang] && i18n[lang][key]) {
                    el.innerText = i18n[lang][key];
                }
            });

            if (devicesList && devicesList.length >= 0) {
                renderDevices(devicesList);
            }
        }

        let authToken = localStorage.getItem('wol_token') || '';
        let devicesList = [];
        let devicesStatusMap = {};
        let schedulesList = [];
        let selectedDeviceForIntegration = null;
        let currentTab = 'ha';
        let statusPollInterval = null;

        window.onload = function() {
            setLanguage(currentLang);
            if (authToken) {
                checkAuth();
            }
        };

        function t(key, params = {}) {
            let str = (i18n[currentLang] && i18n[currentLang][key]) || (i18n['en'][key]) || key;
            for (const p in params) {
                str = str.replace(new RegExp('\\{' + p + '\\}', 'g'), params[p]);
            }
            return str;
        }

        function showToast(msg, type = 'success') {
            const container = document.getElementById('toastContainer');
            const toast = document.createElement('div');
            toast.className = 'toast ' + type;
            toast.innerHTML = (type === 'success' ? '⚡ ' : '⚠️ ') + msg;
            container.appendChild(toast);
            setTimeout(() => toast.remove(), 4000);
        }

        function checkAuth() {
            fetch('/api/devices', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(res => {
                    if (res.ok) {
                        document.getElementById('loginScreen').classList.add('hidden');
                        document.getElementById('dashboardScreen').classList.remove('hidden');
                        loadDevices();
                        loadConfigData();
                        startStatusPolling();
                    } else {
                        authToken = '';
                        localStorage.removeItem('wol_token');
                    }
                })
                .catch(() => {});
        }

        function login() {
            const password = document.getElementById('loginPassword').value;
            fetch('/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password })
            })
            .then(r => r.json())
            .then(data => {
                if (data.success) {
                    authToken = data.authToken;
                    localStorage.setItem('wol_token', authToken);
                    document.getElementById('loginScreen').classList.add('hidden');
                    document.getElementById('dashboardScreen').classList.remove('hidden');
                    loadDevices();
                    loadConfigData();
                    startStatusPolling();
                    showToast(t('toastLoginSuccess'));
                } else {
                    showToast(t('toastLoginFailed'), 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function logout() {
            if (statusPollInterval) clearInterval(statusPollInterval);
            authToken = '';
            localStorage.removeItem('wol_token');
            document.getElementById('dashboardScreen').classList.add('hidden');
            document.getElementById('loginScreen').classList.remove('hidden');
        }

        function loadDevices() {
            fetch('/api/devices', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(r => r.json())
                .then(devices => {
                    devicesList = devices;
                    renderDevices(devices);
                    checkDevicesLiveness();
                })
                .catch(e => showToast('Failed to load devices: ' + e.message, 'error'));
        }

        function startStatusPolling() {
            if (statusPollInterval) clearInterval(statusPollInterval);
            statusPollInterval = setInterval(() => {
                checkDevicesLiveness();
            }, 10000);
        }

        function checkDevicesLiveness() {
            fetch('/api/devices/status', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(r => r.json())
                .then(res => {
                    if (res.success && res.status) {
                        devicesStatusMap = res.status;
                        updateStatusBadges();
                    }
                })
                .catch(() => {});
        }

        function updateStatusBadges() {
            devicesList.forEach(dev => {
                const el = document.getElementById('status-pill-' + dev.id);
                if (!el) return;
                const status = devicesStatusMap[dev.id];
                if (status && status.isOnline) {
                    el.className = 'status-pill online';
                    el.innerHTML = `<span class="status-dot"></span>${'$'}{t('online')} (${'$'}{status.latencyMs}ms)`;
                } else {
                    el.className = 'status-pill offline';
                    el.innerHTML = `<span class="status-dot"></span>${'$'}{t('offline')}`;
                }
            });
        }

        const iconMap = { desktop: '🖥️', server: '🗄️', laptop: '💻', console: '🎮', tv: '📺' };

        function renderDevices(devices) {
            document.getElementById('deviceCount').innerText = devices.length;
            const container = document.getElementById('devicesContainer');
            container.innerHTML = '';

            devices.forEach(dev => {
                const icon = iconMap[dev.iconType] || '🖥️';
                const status = devicesStatusMap[dev.id];
                const isOnline = status && status.isOnline;
                const statusHtml = isOnline
                    ? `<span id="status-pill-${'$'}{dev.id}" class="status-pill online"><span class="status-dot"></span>${'$'}{t('online')} (${'$'}{status.latencyMs}ms)</span>`
                    : `<span id="status-pill-${'$'}{dev.id}" class="status-pill offline"><span class="status-dot"></span>${'$'}{t('offline')}</span>`;

                const card = document.createElement('div');
                card.className = 'device-card';
                card.innerHTML = `
                    <div class="card-header">
                        <div class="device-icon-box">${'$'}{icon}</div>
                        <div class="card-actions">
                            <button onclick="openIntegrationModal('${'$'}{dev.id}')" class="icon-btn" title="${'$'}{t('integrate')}">🔗</button>
                            <button onclick="openEditModal('${'$'}{dev.id}')" class="icon-btn" title="${'$'}{t('edit')}">✏️</button>
                            <button onclick="deleteDevice('${'$'}{dev.id}', '${'$'}{dev.name}')" class="icon-btn" title="${'$'}{t('delete')}" style="color: var(--accent-red);">🗑️</button>
                        </div>
                    </div>
                    <div>${'$'}{statusHtml}</div>
                    <div class="device-name">${'$'}{dev.name}</div>
                    <div class="device-meta">
                        <div class="meta-row"><span class="meta-label">MAC:</span><span class="meta-val">${'$'}{dev.macAddress}</span></div>
                        <div class="meta-row"><span class="meta-label">IP:</span><span class="meta-val">${'$'}{dev.ipAddress || 'Broadcast'}</span></div>
                        <div class="meta-row"><span class="meta-label">Broadcast:</span><span class="meta-val">${'$'}{dev.broadcastAddress}:${'$'}{dev.port}</span></div>
                    </div>
                    <div class="card-footer">
                        <button onclick="wakeDevice('${'$'}{dev.id}', '${'$'}{dev.name}')" class="btn btn-wake btn-card-wake">${'$'}{t('wake')}</button>
                    </div>
                `;
                container.appendChild(card);
            });

            // Add card at end
            const addCard = document.createElement('div');
            addCard.className = 'add-card';
            addCard.onclick = openAddModal;
            addCard.innerHTML = `<span class="add-icon">➕</span><span style="font-weight: 600;">${'$'}{t('addNewDevice')}</span>`;
            container.appendChild(addCard);
        }

        function wakeDevice(id, name) {
            fetch('/wake?id=' + id, {
                method: 'POST',
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    showToast(t('toastWakeSuccess', { name: name }));
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function wakeAll() {
            fetch('/wake', {
                method: 'POST',
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    showToast(t('toastWakeAllSuccess', { count: res.count || devicesList.length }));
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        // --- Device Modals ---
        function openAddModal() {
            document.getElementById('modalTitle').innerText = t('addDeviceTitle');
            document.getElementById('devId').value = '';
            document.getElementById('devName').value = '';
            document.getElementById('devMac').value = '';
            document.getElementById('devIp').value = '';
            document.getElementById('devBroadcast').value = '255.255.255.255';
            document.getElementById('devPort').value = '9';
            document.getElementById('devIcon').value = 'desktop';
            document.getElementById('deviceModal').classList.add('active');
        }

        function openEditModal(id) {
            const dev = devicesList.find(d => d.id === id);
            if (!dev) return;
            document.getElementById('modalTitle').innerText = t('editDeviceTitle');
            document.getElementById('devId').value = dev.id;
            document.getElementById('devName').value = dev.name;
            document.getElementById('devMac').value = dev.macAddress;
            document.getElementById('devIp').value = dev.ipAddress || '';
            document.getElementById('devBroadcast').value = dev.broadcastAddress;
            document.getElementById('devPort').value = dev.port;
            document.getElementById('devIcon').value = dev.iconType || 'desktop';
            document.getElementById('deviceModal').classList.add('active');
        }

        function closeModal() {
            document.getElementById('deviceModal').classList.remove('active');
        }

        function saveDeviceModal() {
            const id = document.getElementById('devId').value;
            const name = document.getElementById('devName').value.trim() || 'Computer';
            const macAddress = document.getElementById('devMac').value.trim();
            const ipAddress = document.getElementById('devIp').value.trim();
            const broadcastAddress = document.getElementById('devBroadcast').value.trim() || '255.255.255.255';
            const port = parseInt(document.getElementById('devPort').value) || 9;
            const iconType = document.getElementById('devIcon').value;

            if (!macAddress || macAddress.length < 12) {
                showToast('Please enter a valid MAC address', 'error');
                return;
            }

            const payload = { id, name, macAddress, ipAddress, broadcastAddress, port, iconType };

            fetch('/api/devices', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken },
                body: JSON.stringify(payload)
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    closeModal();
                    loadDevices();
                    showToast(t('toastSaveSuccess', { name: name }));
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function deleteDevice(id, name) {
            if (!confirm(t('confirmDelete', { name: name }))) return;
            fetch('/api/devices?id=' + id, {
                method: 'DELETE',
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    loadDevices();
                    showToast(t('toastDeleteSuccess', { name: name }));
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        // --- Network Scanner ---
        function openScanModal() {
            document.getElementById('scanModal').classList.add('active');
        }

        function closeScanModal() {
            document.getElementById('scanModal').classList.remove('active');
        }

        function startNetworkScan() {
            const container = document.getElementById('scanResultsContainer');
            const btn = document.getElementById('btnStartScan');
            btn.disabled = true;
            btn.innerText = 'Scanning...';
            container.innerHTML = `<div style="text-align: center; padding: 28px; color: var(--accent-cyan); font-weight: 600;">⚡ ${'$'}{t('scanning')}</div>`;

            fetch('/api/scan', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(r => r.json())
                .then(res => {
                    btn.disabled = false;
                    btn.innerText = t('scanNow');
                    if (res.success && res.devices && res.devices.length > 0) {
                        renderScanResults(res.devices);
                    } else {
                        container.innerHTML = `<p style="color: var(--text-muted); font-size: 13px; text-align: center; padding: 24px;">No active devices found in local ARP table. Ensure devices are powered on.</p>`;
                    }
                })
                .catch(e => {
                    btn.disabled = false;
                    btn.innerText = t('scanNow');
                    container.innerHTML = `<p style="color: var(--accent-red); font-size: 13px; text-align: center; padding: 24px;">Scan error: ${'$'}{e.message}</p>`;
                });
        }

        function renderScanResults(devices) {
            const container = document.getElementById('scanResultsContainer');
            let html = `
                <table class="scan-table">
                    <thead>
                        <tr>
                            <th>Host / Device</th>
                            <th>IP Address</th>
                            <th>MAC Address</th>
                            <th>Action</th>
                        </tr>
                    </thead>
                    <tbody>
            `;

            devices.forEach(d => {
                const icon = iconMap[d.vendor] || '🖥️';
                html += `
                    <tr>
                        <td><strong>${'$'}{icon} ${'$'}{d.hostname}</strong></td>
                        <td style="font-family: 'JetBrains Mono', monospace;">${'$'}{d.ip}</td>
                        <td style="font-family: 'JetBrains Mono', monospace; color: var(--accent-cyan);">${'$'}{d.mac}</td>
                        <td>
                            <button onclick="addScannedDevice('${'$'}{d.hostname}', '${'$'}{d.mac}', '${'$'}{d.ip}', '${'$'}{d.vendor}')" class="btn btn-primary" style="padding: 4px 10px; font-size: 12px; min-height: 28px;">➕ Add</button>
                        </td>
                    </tr>
                `;
            });

            html += `</tbody></table>`;
            container.innerHTML = html;
        }

        function addScannedDevice(name, mac, ip, vendor) {
            document.getElementById('devId').value = '';
            document.getElementById('devName').value = name;
            document.getElementById('devMac').value = mac;
            document.getElementById('devIp').value = ip;
            document.getElementById('devBroadcast').value = '255.255.255.255';
            document.getElementById('devPort').value = '9';
            document.getElementById('devIcon').value = vendor || 'desktop';
            closeScanModal();
            document.getElementById('deviceModal').classList.add('active');
        }

        // --- Schedules Modal ---
        function openSchedulesModal() {
            document.getElementById('schedulesModal').classList.add('active');
            loadSchedulesData();
        }

        function closeSchedulesModal() {
            document.getElementById('schedulesModal').classList.remove('active');
        }

        function loadSchedulesData() {
            const container = document.getElementById('schedulesContainer');
            fetch('/api/schedules', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(r => r.json())
                .then(schedules => {
                    schedulesList = schedules;
                    renderSchedules(schedules);
                })
                .catch(e => {
                    container.innerHTML = `<p style="color: var(--accent-red); font-size: 13px;">Error: ${'$'}{e.message}</p>`;
                });
        }

        const dayNames = { 1: 'Mon', 2: 'Tue', 3: 'Wed', 4: 'Thu', 5: 'Fri', 6: 'Sat', 7: 'Sun' };

        function renderSchedules(schedules) {
            const container = document.getElementById('schedulesContainer');
            if (schedules.length === 0) {
                container.innerHTML = `<p style="color: var(--text-muted); font-size: 13px; text-align: center; padding: 24px;">No auto-wake schedules configured yet.</p>`;
                return;
            }

            container.innerHTML = '';
            schedules.forEach(s => {
                const card = document.createElement('div');
                card.className = 'schedule-card';
                const timeStr = String(s.hour).padStart(2, '0') + ':' + String(s.minute).padStart(2, '0');
                const daysStr = (s.daysOfWeek || []).map(d => dayNames[d] || d).join(', ');

                card.innerHTML = `
                    <div style="flex: 1;">
                        <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">
                            <span style="font-size: 17px; font-weight: 700; font-family: 'JetBrains Mono', monospace; color: var(--accent-cyan);">${'$'}{timeStr}</span>
                            <span style="font-size: 14px; font-weight: 600; color: #fff;">${'$'}{s.name}</span>
                        </div>
                        <div style="font-size: 12px; color: var(--text-secondary);">
                            Target: <strong style="color: #fff;">${'$'}{s.deviceName}</strong> | Days: <strong style="color: var(--accent-orange);">${'$'}{daysStr}</strong>
                        </div>
                    </div>
                    <div style="display: flex; align-items: center; gap: 8px;">
                        <input type="checkbox" ${'$'}{s.enabled ? 'checked' : ''} onchange="toggleScheduleActive('${'$'}{s.id}', this.checked)" style="width: 20px; height: 20px; accent-color: var(--accent-green); cursor: pointer;">
                        <button onclick="deleteSchedule('${'$'}{s.id}')" class="icon-btn" title="Delete" style="color: var(--accent-red);">🗑️</button>
                    </div>
                `;
                container.appendChild(card);
            });
        }

        function openAddScheduleModal() {
            document.getElementById('schedId').value = '';
            document.getElementById('schedName').value = 'Morning Auto-Wake';
            document.getElementById('schedHour').value = '8';
            document.getElementById('schedMinute').value = '30';

            const select = document.getElementById('schedDevice');
            select.innerHTML = '<option value="all">⚡ All Configured Devices</option>';
            devicesList.forEach(dev => {
                select.innerHTML += `<option value="${'$'}{dev.id}">${'$'}{dev.name} (${'$'}{dev.macAddress})</option>`;
            });

            document.getElementById('editScheduleModal').classList.add('active');
        }

        function closeEditScheduleModal() {
            document.getElementById('editScheduleModal').classList.remove('active');
        }

        function saveScheduleModal() {
            const id = document.getElementById('schedId').value;
            const name = document.getElementById('schedName').value.trim() || 'Auto Wake';
            const deviceId = document.getElementById('schedDevice').value;
            const hour = parseInt(document.getElementById('schedHour').value) || 0;
            const minute = parseInt(document.getElementById('schedMinute').value) || 0;

            const days = [];
            document.querySelectorAll('#schedDaysContainer input:checked').forEach(cb => {
                days.push(parseInt(cb.value));
            });

            const payload = { id, name, deviceId, hour, minute, daysOfWeek: days, enabled: true };

            fetch('/api/schedules', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken },
                body: JSON.stringify(payload)
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    closeEditScheduleModal();
                    loadSchedulesData();
                    showToast(t('toastScheduleSaved', { name: name }));
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function toggleScheduleActive(id, enabled) {
            const sched = schedulesList.find(s => s.id === id);
            if (!sched) return;
            sched.enabled = enabled;
            fetch('/api/schedules', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken },
                body: JSON.stringify(sched)
            });
        }

        function deleteSchedule(id) {
            fetch('/api/schedules?id=' + id, {
                method: 'DELETE',
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                loadSchedulesData();
                showToast(t('toastScheduleDeleted'));
            })
            .catch(e => showToast(e.message, 'error'));
        }

        // --- Integration Generator ---
        function openIntegrationModal(deviceId) {
            selectedDeviceForIntegration = devicesList.find(d => d.id === deviceId);
            if (!selectedDeviceForIntegration) return;
            document.getElementById('integrationTitle').innerText = '🔗 Integration: ' + selectedDeviceForIntegration.name;
            switchTab('ha');
            document.getElementById('integrationModal').classList.add('active');
        }

        function closeIntegrationModal() {
            document.getElementById('integrationModal').classList.remove('active');
        }

        function switchTab(tab) {
            currentTab = tab;
            document.querySelectorAll('.code-tab-btn').forEach(btn => btn.classList.remove('active'));
            if (tab === 'ha') document.getElementById('tabHa').classList.add('active');
            if (tab === 'shortcuts') document.getElementById('tabShortcuts').classList.add('active');
            if (tab === 'curl') document.getElementById('tabCurl').classList.add('active');
            if (tab === 'python') document.getElementById('tabPython').classList.add('active');

            renderSnippet();
        }

        function renderSnippet() {
            if (!selectedDeviceForIntegration) return;
            const dev = selectedDeviceForIntegration;
            const host = window.location.host;
            const token = authToken || 'YOUR_AUTH_TOKEN';
            const box = document.getElementById('snippetContainer');

            if (currentTab === 'ha') {
                box.innerText = 
`# Home Assistant configuration.yaml
rest_command:
  wake_${'$'}{dev.name.toLowerCase().replace(/\\s+/g, '_')}:
    url: "http://${'$'}{host}/wake?id=${'$'}{dev.id}"
    method: "POST"
    headers:
      Authorization: "Bearer ${'$'}{token}"

# Or as a Switch entity:
switch:
  - platform: template
    switches:
      ${'$'}{dev.name.toLowerCase().replace(/\\s+/g, '_')}_power:
        friendly_name: "${'$'}{dev.name}"
        turn_on:
          service: rest_command.wake_${'$'}{dev.name.toLowerCase().replace(/\\s+/g, '_')}
        turn_off:
          # Optional Sleep-on-LAN webhook
`;
            } else if (currentTab === 'shortcuts') {
                box.innerText = 
`# Apple Shortcuts / iOS & Android Webhook URL
# Add a "Get Contents of URL" action in Shortcuts:

Method: POST
URL: http://${'$'}{host}/wake?id=${'$'}{dev.id}&token=${'$'}{token}
`;
            } else if (currentTab === 'curl') {
                box.innerText = 
`# cURL Terminal Command:
curl -X POST "http://${'$'}{host}/wake?id=${'$'}{dev.id}" \\
     -H "Authorization: Bearer ${'$'}{token}"
`;
            } else if (currentTab === 'python') {
                box.innerText = 
`import requests

url = "http://${'$'}{host}/wake?id=${'$'}{dev.id}"
headers = {"Authorization": "Bearer ${'$'}{token}"}

response = requests.post(url, headers=headers)
print("WoL Status:", response.json())
`;
            }
        }

        function copySnippet() {
            const text = document.getElementById('snippetContainer').innerText;
            copyTextToClipboard(text, t('toastSnippetCopied'));
        }

        // --- Backup & Restore ---
        function downloadBackup() {
            window.location.href = '/api/backup?token=' + authToken;
            showToast(t('toastBackupDownloaded'));
        }

        function triggerRestoreUpload() {
            document.getElementById('restoreFileInput').click();
        }

        function handleFileRestore(input) {
            const file = input.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = function(e) {
                try {
                    const json = JSON.parse(e.target.result);
                    fetch('/api/restore', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken },
                        body: JSON.stringify(json)
                    })
                    .then(r => r.json())
                    .then(res => {
                        if (res.success) {
                            showToast(t('toastRestoreSuccess'));
                            loadDevices();
                            loadConfigData();
                        } else {
                            showToast(res.message, 'error');
                        }
                    })
                    .catch(err => showToast(err.message, 'error'));
                } catch (err) {
                    showToast('Invalid JSON file format', 'error');
                }
            };
            reader.readAsText(file);
        }

        // --- Logs Modal ---
        function openLogsModal() {
            document.getElementById('logsModal').classList.add('active');
            fetchLogs();
        }

        function closeLogsModal() {
            document.getElementById('logsModal').classList.remove('active');
        }

        function fetchLogs() {
            const consoleBox = document.getElementById('logsConsole');
            consoleBox.innerText = 'Loading system logs...';
            fetch('/api/logs', {
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    consoleBox.innerText = res.logs || 'No logs available.';
                    consoleBox.scrollTop = consoleBox.scrollHeight;
                } else {
                    consoleBox.innerText = 'Error loading logs: ' + (res.error || res.message);
                }
            })
            .catch(e => {
                consoleBox.innerText = 'Error fetching logs: ' + e.message;
            });
        }

        function clearServerLogs() {
            fetch('/api/logs', {
                method: 'DELETE',
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                document.getElementById('logsConsole').innerText = 'Logs cleared.';
                showToast('Logs cleared');
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function copyLogs() {
            const text = document.getElementById('logsConsole').innerText;
            copyTextToClipboard(text, t('toastLogsCopied'));
        }

        function copyToken() {
            const token = document.getElementById('cfgAuthToken').value;
            if (!token) return;
            copyTextToClipboard(token, t('toastTokenCopied'));
        }

        function copyTextToClipboard(text, successMessage) {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(() => {
                    showToast(successMessage);
                }).catch(() => fallbackCopy(text, successMessage));
            } else {
                fallbackCopy(text, successMessage);
            }
        }

        function fallbackCopy(text, successMessage) {
            const ta = document.createElement('textarea');
            ta.value = text;
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
            showToast(successMessage);
        }

        function formatMacInput(elem) {
            let val = elem.value.replace(/[^0-9A-Fa-f]/g, '').toUpperCase();
            if (val.length > 12) val = val.substring(0, 12);
            let formatted = val.match(/.{1,2}/g)?.join(':') || val;
            elem.value = formatted;
        }

        function toggleSettings() {
            openSettingsModal();
        }

        function openSettingsModal() {
            loadConfigData();
            document.getElementById('settingsModal').classList.add('active');
        }

        function closeSettingsModal() {
            document.getElementById('settingsModal').classList.remove('active');
        }

        function openAboutModal() {
            document.getElementById('aboutModal').classList.add('active');
        }

        function closeAboutModal() {
            document.getElementById('aboutModal').classList.remove('active');
        }

        function loadConfigData() {
            fetch('/config', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(r => r.json())
                .then(cfg => {
                    document.getElementById('cfgWebPassword').value = cfg.webPassword || '';
                    document.getElementById('cfgAuthToken').value = cfg.authToken || '';
                    document.getElementById('cfgBroadcast').value = cfg.broadcastAddress || '255.255.255.255';
                    document.getElementById('cfgHttpPort').value = cfg.httpPort || 8085;
                    document.getElementById('cfgAllowlist').value = (cfg.ipAllowlist || []).join(', ');
                    document.getElementById('cfgRequireAuth').checked = cfg.requireAuthentication !== false;
                    document.getElementById('cfgAutoStart').checked = cfg.autoStartEnabled !== false;
                    const langEl = document.getElementById('cfgLanguage');
                    if (langEl) langEl.value = currentLang;
                })
                .catch(() => {});
        }

        function generateToken() {
            const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
            let t = '';
            for (let i = 0; i < 32; i++) t += chars.charAt(Math.floor(Math.random() * chars.length));
            document.getElementById('cfgAuthToken').value = t;
        }

        function saveServerConfig() {
            const payload = {
                webPassword: document.getElementById('cfgWebPassword').value,
                authToken: document.getElementById('cfgAuthToken').value,
                broadcastAddress: document.getElementById('cfgBroadcast').value,
                httpPort: parseInt(document.getElementById('cfgHttpPort').value) || 8085,
                ipAllowlist: document.getElementById('cfgAllowlist').value.split(',').map(s => s.trim()).filter(s => s.length > 0),
                requireAuthentication: document.getElementById('cfgRequireAuth').checked,
                autoStartEnabled: document.getElementById('cfgAutoStart').checked
            };

            fetch('/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken },
                body: JSON.stringify(payload)
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    showToast(t('toastSettingsSuccess'));
                    closeSettingsModal();
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }
    </script>
</body>
</html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    override fun start() {
        try {
            super.start()
            Log.i(TAG, "HTTP server started on port $port")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }
    }

    override fun stop() {
        super.stop()
        Log.i(TAG, "HTTP server stopped")
    }
}
