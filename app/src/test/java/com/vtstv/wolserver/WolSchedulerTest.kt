/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver

import com.vtstv.wolserver.core.scheduler.WolScheduler
import com.vtstv.wolserver.data.model.WolSchedule
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for WolScheduler trigger rules, day-of-week calculation,
 * and 65-second debouncing cooldown.
 */
class WolSchedulerTest {

    @Test
    fun testShouldTrigger_exactMatch() {
        val schedule = WolSchedule(
            id = "sched-1",
            name = "Morning Wake",
            daysOfWeek = mutableListOf(1, 2, 3, 4, 5), // Mon-Fri
            hour = 8,
            minute = 30,
            enabled = true,
            lastRunTimestamp = 0L
        )

        // Mock calendar: Monday at 08:30
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
        }

        assertTrue(WolScheduler.shouldTrigger(schedule, cal))
    }

    @Test
    fun testShouldTrigger_disabledSchedule() {
        val schedule = WolSchedule(
            name = "Disabled",
            daysOfWeek = mutableListOf(1, 2, 3, 4, 5),
            hour = 8,
            minute = 30,
            enabled = false
        )

        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 30)
        }

        assertFalse(WolScheduler.shouldTrigger(schedule, cal))
    }

    @Test
    fun testShouldTrigger_wrongDay() {
        // Schedule only on Weekends (Sat=6, Sun=7)
        val schedule = WolSchedule(
            name = "Weekend Wake",
            daysOfWeek = mutableListOf(6, 7),
            hour = 10,
            minute = 0,
            enabled = true
        )

        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY) // Day 3
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
        }

        assertFalse(WolScheduler.shouldTrigger(schedule, cal))
    }

    @Test
    fun testShouldTrigger_debouncingWithinOneMinute() {
        val now = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 30)
        }

        // Ran 15 seconds ago
        val schedule = WolSchedule(
            name = "Morning Wake",
            daysOfWeek = mutableListOf(1),
            hour = 8,
            minute = 30,
            enabled = true,
            lastRunTimestamp = now.timeInMillis - 15000L
        )

        // Should NOT trigger again within cooldown
        assertFalse(WolScheduler.shouldTrigger(schedule, now))
    }
}
