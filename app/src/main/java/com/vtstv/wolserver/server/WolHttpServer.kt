/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.vtstv.wolserver.core.engine.DevicePinger
import com.vtstv.wolserver.core.engine.NetworkScanner
import com.vtstv.wolserver.core.engine.WakeOnLan
import com.vtstv.wolserver.core.scheduler.WolScheduler
import com.vtstv.wolserver.data.model.WolConfig
import com.vtstv.wolserver.data.repository.ConfigManager
import com.vtstv.wolserver.server.api.ConfigApiHandler
import com.vtstv.wolserver.server.api.IntegrationApiHandler
import com.vtstv.wolserver.server.api.WakeApiHandler
import com.vtstv.wolserver.server.web.WebDashboardHtml
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Embedded HTTP server and REST router for Simple WOL Server 2.0 Pro.
 * Delegates request processing to focused API handlers and Web UI templates.
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
    private val wakeApiHandler = WakeApiHandler(configManager, wakeOnLan, devicePinger, gson)
    private val configApiHandler = ConfigApiHandler(configManager, gson)
    private val integrationApiHandler = IntegrationApiHandler(configManager, networkScanner, gson)

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

        val isAuth = isAuthenticated(session)

        val response = when {
            (uri == "/icon.png" || uri == "/favicon.ico") && method == Method.GET -> handleIcon()
            uri == "/" && method == Method.GET -> handleDashboardPage(session)
            uri == "/login" && method == Method.POST -> configApiHandler.handleLogin(session)
            uri == "/health" && method == Method.GET -> configApiHandler.handleHealth()
            uri == "/wake" && (method == Method.POST || method == Method.GET) -> wakeApiHandler.handleWake(session, isAuth)

            // Devices CRUD & Live Status
            uri == "/api/devices" && method == Method.GET -> wakeApiHandler.handleGetDevices(session, isAuth)
            uri == "/api/devices" && method == Method.POST -> wakeApiHandler.handleSaveDevice(session, isAuth)
            uri == "/api/devices" && (method == Method.DELETE || method == Method.POST) -> wakeApiHandler.handleDeleteDevice(session, isAuth)
            uri == "/api/devices/status" && method == Method.GET -> wakeApiHandler.handleGetDevicesStatus(session, isAuth)

            // Network Scanner
            uri == "/api/scan" && method == Method.GET -> integrationApiHandler.handleScanNetwork(session, isAuth)

            // Auto-Wake Schedules
            uri == "/api/schedules" && method == Method.GET -> integrationApiHandler.handleGetSchedules(session, isAuth)
            uri == "/api/schedules" && method == Method.POST -> integrationApiHandler.handleSaveSchedule(session, isAuth)
            uri == "/api/schedules" && (method == Method.DELETE || method == Method.POST) -> integrationApiHandler.handleDeleteSchedule(session, isAuth)

            // Backup & Restore
            uri == "/api/backup" && method == Method.GET -> integrationApiHandler.handleGetBackup(session, isAuth)
            uri == "/api/restore" && method == Method.POST -> integrationApiHandler.handleRestoreBackup(session, isAuth)

            // Logs
            uri == "/api/logs" && method == Method.GET -> configApiHandler.handleGetLogs(session, isAuth)
            uri == "/api/logs" && method == Method.DELETE -> configApiHandler.handleClearLogs(session, isAuth)

            // Server Config
            uri == "/config" && method == Method.GET -> configApiHandler.handleGetConfig(session, isAuth)
            uri == "/config" && method == Method.POST -> configApiHandler.handleUpdateConfig(session, isAuth)

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        return addCorsHeaders(response)
    }

    private fun handleIcon(): Response {
        return try {
            val inputStream = context.assets.open("icon.png")
            val bytes = inputStream.readBytes()
            inputStream.close()
            newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Icon not found")
        }
    }

    private fun handleDashboardPage(session: IHTTPSession): Response {
        val hostHeader = session.headers["host"] ?: "$serverIpAddress:$port"
        val html = WebDashboardHtml.render(hostHeader, serverIpAddress, port)
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun createCorsResponse(status: Response.Status, mimeType: String, txt: String): Response {
        val response = newFixedLengthResponse(status, mimeType, txt)
        return addCorsHeaders(response)
    }

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
        return response
    }

    private fun isIpAllowed(ip: String): Boolean {
        val allowlist = currentConfig.ipAllowlist
        if (allowlist.isEmpty()) return true
        if (ip == "127.0.0.1" || ip == "localhost" || ip == "::1") return true
        return allowlist.any { allowed ->
            ip == allowed || ip.startsWith(allowed.removeSuffix("*"))
        }
    }

    private fun isAuthenticated(session: IHTTPSession): Boolean {
        if (!currentConfig.requireAuthentication) {
            return true
        }

        val authHeader = session.headers["authorization"]
        if (!authHeader.isNullOrBlank()) {
            val parts = authHeader.split(" ")
            if (parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true)) {
                return parts[1] == currentConfig.authToken
            }
        }

        val tokenParam = session.parms["token"]
        if (!tokenParam.isNullOrBlank() && tokenParam == currentConfig.authToken) {
            return true
        }

        return false
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
