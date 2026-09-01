package com.vtstv.wolserver.server.api

import com.google.gson.Gson
import com.vtstv.wolserver.core.engine.DevicePinger
import com.vtstv.wolserver.core.engine.WakeOnLan
import com.vtstv.wolserver.data.model.WolDevice
import com.vtstv.wolserver.data.repository.ConfigManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * REST API handler for Wake-on-LAN operations, Device CRUD, and live status pinging.
 */
class WakeApiHandler(
    private val configManager: ConfigManager,
    private val wakeOnLan: WakeOnLan,
    private val devicePinger: DevicePinger,
    private val gson: Gson
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun handleWake(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("success" to false, "message" to "Authentication required"))
            )
        }

        val devices = configManager.loadDevices()
        val deviceId = session.parms["id"] ?: session.parms["deviceId"]
        val customMac = session.parms["mac"]

        val targetDevices = when {
            !deviceId.isNullOrBlank() -> {
                val found = devices.filter { it.id == deviceId }
                if (found.isEmpty()) {
                    return NanoHTTPD.newFixedLengthResponse(
                        Response.Status.NOT_FOUND, "application/json",
                        gson.toJson(mapOf("success" to false, "message" to "Device with ID '$deviceId' not found"))
                    )
                }
                found
            }
            !customMac.isNullOrBlank() -> {
                if (!wakeOnLan.isValidMacAddress(customMac)) {
                    return NanoHTTPD.newFixedLengthResponse(
                        Response.Status.BAD_REQUEST, "application/json",
                        gson.toJson(mapOf("success" to false, "message" to "Invalid MAC address format: $customMac"))
                    )
                }
                listOf(WolDevice(name = "Direct Wake", macAddress = customMac))
            }
            devices.isNotEmpty() -> {
                devices
            }
            configManager.loadConfig().targetMacAddress.isNotBlank() -> {
                listOf(WolDevice(name = "Legacy Target", macAddress = configManager.loadConfig().targetMacAddress))
            }
            else -> {
                return NanoHTTPD.newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    gson.toJson(mapOf("success" to false, "message" to "No devices or MAC addresses configured"))
                )
            }
        }

        scope.launch {
            wakeOnLan.sendWakePackets(targetDevices)
            targetDevices.forEach { dev ->
                if (dev.id.isNotBlank()) configManager.updateDeviceLastWoken(dev.id)
            }
        }

        val summary = targetDevices.joinToString(", ") { "${it.name} (${it.macAddress})" }
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, "application/json",
            gson.toJson(mapOf("success" to true, "message" to "Magic packet sent to $summary", "count" to targetDevices.size))
        )
    }

    fun handleGetDevices(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required"))
            )
        }
        val devices = configManager.loadDevices()
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(devices))
    }

    fun handleGetDevicesStatus(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required"))
            )
        }

        val devices = configManager.loadDevices()
        var results = mapOf<String, DevicePinger.PingResult>()

        runBlocking {
            results = devicePinger.pingAll(devices)
        }

        val response = mapOf("success" to true, "status" to results)
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
    }

    fun handleSaveDevice(session: IHTTPSession, isAuth: Boolean): Response {
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
            val device = gson.fromJson(postData, WolDevice::class.java)

            if (!wakeOnLan.isValidMacAddress(device.macAddress)) {
                return NanoHTTPD.newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    gson.toJson(mapOf("success" to false, "message" to "Invalid MAC address: ${device.macAddress}"))
                )
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

            NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json",
                gson.toJson(mapOf("success" to true, "message" to "Device saved", "device" to device))
            )
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message))
            )
        }
    }

    fun handleDeleteDevice(session: IHTTPSession, isAuth: Boolean): Response {
        if (!isAuth) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required"))
            )
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
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to "Device ID required"))
            )
        }

        val removed = configManager.deleteDevice(deviceId)
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, "application/json",
            gson.toJson(mapOf("success" to removed, "message" to if (removed) "Device deleted" else "Device not found"))
        )
    }
}
