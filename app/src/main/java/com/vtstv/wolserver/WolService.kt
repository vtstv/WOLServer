package com.vtstv.wolserver

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Background service that runs the HTTP server for Wake-on-LAN functionality.
 * Runs as a foreground service to ensure it stays active even when the app is not in foreground.
 * 
 * Copyright (c) 2025 Murr
 * https://github.com/vtstv/wolserver
 */
class WolService : Service() {

    companion object {
        private const val TAG = "WolService"
        private const val NOTIFICATION_CHANNEL_ID = "wol_service_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START_SERVICE = "com.vtstv.wolserver.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.vtstv.wolserver.STOP_SERVICE"
        const val ACTION_RESTART_SERVICE = "com.vtstv.wolserver.RESTART_SERVICE"
    }

    private var httpServer: WolHttpServer? = null
    private var config: WolConfig? = null
    private lateinit var configManager: ConfigManager
    private lateinit var wakeOnLan: WakeOnLan
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WolService created")
        
        configManager = ConfigManager(this)
        wakeOnLan = WakeOnLan()
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "WolService onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_SERVICE -> startHttpServer()
            ACTION_STOP_SERVICE -> stopService()
            ACTION_RESTART_SERVICE -> restartHttpServer()
            else -> startHttpServer()
        }
        
        return START_STICKY // Restart service if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "WolService destroyed")
        stopHttpServer()
    }

    private fun startHttpServer() {
        serviceScope.launch {
            try {
                config = configManager.loadConfig()
                
                if (config?.authToken?.isEmpty() == true) {
                    // Generate a random token if none exists
                    config?.authToken = configManager.generateRandomToken()
                    configManager.saveConfig(config!!)
                    Log.i(TAG, "Generated new auth token")
                }
                
                stopHttpServer() // Stop existing server if running
                
                val serverIpAddress = getLocalIpAddress()
                
                httpServer = WolHttpServer(
                    port = config?.httpPort ?: 8085,
                    config = config!!,
                    configManager = configManager,
                    wakeOnLan = wakeOnLan,
                    serverIpAddress = serverIpAddress
                )
                
                httpServer?.start()
                
                startForeground(NOTIFICATION_ID, createNotification())
                
                Log.i(TAG, "HTTP server started on port ${config?.httpPort}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server", e)
                stopSelf()
            }
        }
    }

    private fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
        Log.i(TAG, "HTTP server stopped")
    }

    private fun restartHttpServer() {
        Log.i(TAG, "Restarting HTTP server")
        stopHttpServer()
        startHttpServer()
    }

    private fun stopService() {
        Log.i(TAG, "Stopping WolService")
        stopHttpServer()
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "WOL Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Wake-on-LAN background service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, WolService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val serverIpAddress = getLocalIpAddress()

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Simple WOL Server")
            .setContentText("Running $serverIpAddress:${config?.httpPort ?: 8085}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
    
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        if (sAddr != null && sAddr.indexOf(':') < 0) { // IPv4
                            return sAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP address", e)
        }
        return "127.0.0.1"
    }
    
    /**
     * Updates the notification when configuration changes
     */
    fun updateNotification() {
        // Check for notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Notification permission not granted")
                return
            }
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }
    
    /**
     * Companion object methods for external control
     */
    object ServiceManager {
        
        fun startService(context: Context) {
            val intent = Intent(context, WolService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, WolService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
        
        fun restartService(context: Context) {
            val intent = Intent(context, WolService::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            context.startService(intent)
        }
        
        fun isServiceRunning(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            return activityManager.getRunningServices(Int.MAX_VALUE).any { serviceInfo ->
                serviceInfo.service.className == WolService::class.java.name
            }
        }
    }
}