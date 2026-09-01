package com.vtstv.wolserver.server.api

import com.google.gson.Gson
import com.vtstv.wolserver.core.engine.NetworkScanner
import com.vtstv.wolserver.data.model.DiscoveredDevice
import com.vtstv.wolserver.data.model.WolBackup
import com.vtstv.wolserver.data.model.WolSchedule
import com.vtstv.wolserver.data.repository.ConfigManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * REST API handler for Subnet Scan, Auto-Wake Schedules, and JSON Backup & Restore.
 */
class IntegrationApiHandler(
    private val configManager: ConfigManager,
    private val networkScanner: NetworkScanner,
    private val gson: Gson
) {

    fun handleScanNetwork(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required"))
            )
        }

        var discovered = emptyList<DiscoveredDevice>()
        runBlocking {
            discovered = networkScanner.scanLocalSubnet()
        }

        val response = mapOf("success" to true, "devices" to discovered, "count" to discovered.size)
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
    }

    fun handleGetSchedules(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required"))
            )
        }
        val schedules = configManager.loadSchedules()
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(schedules))
    }

    fun handleSaveSchedule(session: IHTTPSession, isAuth: Boolean): Response {
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
            val schedule = gson.fromJson(postData, WolSchedule::class.java)

            if (schedule.id.isBlank()) schedule.id = UUID.randomUUID().toString()
            if (schedule.name.isBlank()) schedule.name = "Auto Wake"
            if (schedule.hour !in 0..23) schedule.hour = 8
            if (schedule.minute !in 0..59) schedule.minute = 30
            if (schedule.daysOfWeek.isEmpty()) schedule.daysOfWeek = mutableListOf(1, 2, 3, 4, 5)

            if (schedule.deviceId == "all" || schedule.deviceId.isBlank()) {
                schedule.deviceName = "All Devices"
            } else {
                val dev = configManager.loadDevices().find { it.id == schedule.deviceId }
                schedule.deviceName = dev?.name ?: "Target Device"
            }

            configManager.addOrUpdateSchedule(schedule)
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json",
                gson.toJson(mapOf("success" to true, "message" to "Schedule saved", "schedule" to schedule))
            )
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message))
            )
        }
    }

    fun handleDeleteSchedule(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required"))
            )
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
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to "Schedule ID required"))
            )
        }

        val removed = configManager.deleteSchedule(scheduleId)
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, "application/json",
            gson.toJson(mapOf("success" to removed, "message" to if (removed) "Schedule deleted" else "Schedule not found"))
        )
    }

    fun handleGetBackup(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required"))
            )
        }

        val backup = configManager.createBackup()
        val json = gson.toJson(backup)
        val resp = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", json)
        resp.addHeader("Content-Disposition", "attachment; filename=\"wolserver-backup.json\"")
        return resp
    }

    fun handleRestoreBackup(session: IHTTPSession, isAuth: Boolean): Response {
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
            val backup = gson.fromJson(postData, WolBackup::class.java)

            if (backup.devices == null || backup.config == null) {
                return NanoHTTPD.newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    gson.toJson(mapOf("success" to false, "message" to "Invalid backup JSON structure"))
                )
            }

            val success = configManager.restoreBackup(backup)

            NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json",
                gson.toJson(mapOf("success" to success, "message" to if (success) "Configuration restored successfully" else "Restore failed"))
            )
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message))
            )
        }
    }
}
