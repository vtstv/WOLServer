package com.vtstv.wolserver.data.model

/**
 * Result of ping and reachability probing for a WOL device.
 */
data class DevicePingStatus(
    val deviceId: String,
    val isOnline: Boolean,
    val latencyMs: Long,
    val method: String = "ICMP"
)

/**
 * Result of LAN subnet ARP and NetBIOS scanning for active hosts.
 */
data class DiscoveredDevice(
    val ip: String,
    var mac: String,
    var hostname: String,
    var vendor: String = "",
    var isOnline: Boolean = true
)
