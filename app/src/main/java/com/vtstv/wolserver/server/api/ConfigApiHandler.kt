package com.vtstv.wolserver.server.api

import com.google.gson.Gson
import com.vtstv.wolserver.data.model.WolConfig
import com.vtstv.wolserver.data.repository.ConfigManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * REST API handler for Server Configuration, Health probe, Web Login, and Log viewing.
 */
class ConfigApiHandler(
    private val configManager: ConfigManager,
    private val gson: Gson
) {

    fun handleHealth(): Response {
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
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
    }

    fun handleLogin(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val loginRequest = gson.fromJson(postData, Map::class.java)
            val password = loginRequest["password"]?.toString() ?: ""
            val current = configManager.loadConfig()

            if (password == current.webPassword) {
                val response = mapOf(
                    "success" to true,
                    "message" to "Login successful",
                    "authToken" to current.authToken
                )
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
            } else {
                val response = mapOf("success" to false, "message" to "Invalid password")
                NanoHTTPD.newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", gson.toJson(response))
            }
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message))
            )
        }
    }

    fun handleGetConfig(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required"))
            )
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
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(safeConfig))
    }

    fun handleUpdateConfig(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required"))
            )
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

            NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json",
                gson.toJson(mapOf("success" to true, "message" to "Configuration saved successfully"))
            )
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message))
            )
        }
    }

    fun handleGetLogs(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required"))
            )
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

        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, "application/json",
            gson.toJson(mapOf("success" to true, "logs" to logs))
        )
    }

    fun handleClearLogs(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required"))
            )
        }

        try {
            Runtime.getRuntime().exec("logcat -c")
        } catch (e: Exception) {
            // Ignore
        }
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, "application/json",
            gson.toJson(mapOf("success" to true, "message" to "Logs cleared"))
        )
    }
}
