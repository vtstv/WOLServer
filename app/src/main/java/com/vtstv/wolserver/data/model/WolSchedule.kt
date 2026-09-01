/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver.data.model

import java.util.UUID

/**
 * Model representing an automated Wake-on-LAN schedule / timer.
 */
data class WolSchedule(
    var id: String = UUID.randomUUID().toString(),
    var deviceId: String = "all", // "all" or specific device ID
    var deviceName: String = "All Devices",
    var name: String = "Auto Wake",
    var daysOfWeek: MutableList<Int> = mutableListOf(1, 2, 3, 4, 5), // 1=Mon, 2=Tue, ..., 7=Sun
    var hour: Int = 8,     // 0..23
    var minute: Int = 30,  // 0..59
    var enabled: Boolean = true,
    var lastRunTimestamp: Long = 0
)
