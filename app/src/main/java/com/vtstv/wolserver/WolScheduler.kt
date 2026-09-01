package com.vtstv.wolserver

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Autonomous background scheduler for automated Wake-on-LAN triggers.
 * Periodically checks configured schedules against system time and executes
 * scheduled wake-up routines for PCs, servers, and backup units.
 * 
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
class WolScheduler(
    private val configManager: ConfigManager,
    private val wakeOnLan: WakeOnLan
) {

    companion object {
        private const val TAG = "WolScheduler"

        /**
         * Determines whether a schedule should run at the given moment.
         * Days of week: 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri, 6=Sat, 7=Sun.
         */
        fun shouldTrigger(schedule: WolSchedule, now: Calendar): Boolean {
            if (!schedule.enabled) return false

            // Convert Calendar.DAY_OF_WEEK (Sun=1, Mon=2..Sat=7) to ISO standard (Mon=1..Sun=7)
            val calDay = now.get(Calendar.DAY_OF_WEEK)
            val isoDay = if (calDay == Calendar.SUNDAY) 7 else calDay - 1

            if (!schedule.daysOfWeek.contains(isoDay)) return false

            val currentHour = now.get(Calendar.HOUR_OF_DAY)
            val currentMinute = now.get(Calendar.MINUTE)

            if (currentHour != schedule.hour || currentMinute != schedule.minute) return false

            // Prevent multiple executions within the same minute window (65 seconds cooldown)
            if (now.timeInMillis - schedule.lastRunTimestamp < 65000L) return false

            return true
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Checks all configured schedules and triggers any that are due.
     */
    fun checkAndTriggerSchedules(now: Calendar = Calendar.getInstance()) {
        val schedules = configManager.loadSchedules()
        if (schedules.isEmpty()) return

        for (schedule in schedules) {
            if (shouldTrigger(schedule, now)) {
                Log.i(TAG, "Triggering schedule '${schedule.name}' for deviceId='${schedule.deviceId}' at ${schedule.hour}:${schedule.minute}")
                
                // Mark last run immediately to prevent duplicate runs within the same minute
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
