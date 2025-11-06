package com.vtstv.wolserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast receiver that automatically starts the WOL service when the device boots.
 * This ensures the HTTP server is available immediately after Fire TV startup.
 * 
 * Copyright (c) 2025 Murr
 * https://github.com/vtstv/wolserver
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Boot receiver triggered: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                
                // Check if auto-start is enabled in configuration
                val configManager = ConfigManager(context)
                val config = configManager.loadConfig()
                
                if (config.autoStartEnabled) {
                    Log.i(TAG, "Auto-start enabled, starting WOL service")
                    WolService.ServiceManager.startService(context)
                } else {
                    Log.i(TAG, "Auto-start disabled in configuration")
                }
            }
        }
    }
}