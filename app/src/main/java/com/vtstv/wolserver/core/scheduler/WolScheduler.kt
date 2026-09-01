package com.vtstv.wolserver.core.scheduler

import android.util.Log
import com.vtstv.wolserver.core.engine.WakeOnLan
import com.vtstv.wolserver.data.model.WolSchedule
import com.vtstv.wolserver.data.repository.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Autonomous background scheduler for automated Wake-on-LAN triggers.
 * Periodically checks configured schedules against system time and executes
 * scheduled wake-up routines for PCs, servers, and backup units.
 */
class WolScheduler(
    private val configManager: ConfigManager,
    private val wakeOnLan: WakeOnLan
) {

    companion object {
        private const val TAG = "WolScheduler"

        fun shouldTrigger(schedule: WolSchedule, now: Calendar): Boolean {
            if (!schedule.enabled) return false

            val calDay = now.get(Calendar.DAY_OF_WEEK)
            val isoDay = if (calDay == Calendar.SUNDAY) 7 else calDay - 1

            if (!schedule.daysOfWeek.contains(isoDay)) return false

            val currentHour = now.get(Calendar.HOUR_OF_DAY)
            val currentMinute = now.get(Calendar.MINUTE)

            if (currentHour != schedule.hour || currentMinute != schedule.minute) return false

            if (now.timeInMillis - schedule.lastRunTimestamp < 65000L) return false

            return true
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    fun checkAndTriggerSchedules(now: Calendar = Calendar.getInstance()) {
        val schedules = configManager.loadSchedules()
        if (schedules.isEmpty()) return

        for (schedule in schedules) {
            if (shouldTrigger(schedule, now)) {
                Log.i(TAG, "Triggering schedule '${schedule.name}' for deviceId='${schedule.deviceId}' at ${schedule.hour}:${schedule.minute}")
                configManager.updateScheduleLastRun(schedule.id, now.timeInMillis)

                scope.launch {
                    val devices = configManager.loadDevices()
                    val targetDevices = if (schedule.deviceId == "all" || schedule.deviceId.isBlank()) {
                        devices
                    } else {
                        devices.filter { it.id == schedule.deviceId }
                    }

                    if (targetDevices.isNotEmpty()) {
                        wakeOnLan.sendWakePackets(targetDevices)
                        targetDevices.forEach { dev ->
                            configManager.updateDeviceLastWoken(dev.id)
                        }
                        Log.i(TAG, "Schedule '${schedule.name}' successfully sent magic packets to ${targetDevices.size} devices")
                    } else {
                        Log.w(TAG, "Schedule '${schedule.name}' target device not found: ${schedule.deviceId}")
                    }
                }
            }
        }
    }
}
