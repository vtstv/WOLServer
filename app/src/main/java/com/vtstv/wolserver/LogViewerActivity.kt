package com.vtstv.wolserver

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Simple log viewer activity that displays recent logcat entries for the WOL application.
 * Useful for debugging and monitoring the service status.
 * 
 * Copyright (c) 2025 Murr
 * https://github.com/vtstv/wolserver
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var textLogs: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var buttonRefresh: Button
    private lateinit var buttonClear: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)
        
        initializeViews()
        setupListeners()
        loadLogs()
    }

    private fun initializeViews() {
        textLogs = findViewById(R.id.textLogs)
        scrollView = findViewById(R.id.scrollView)
        buttonRefresh = findViewById(R.id.buttonRefresh)
        buttonClear = findViewById(R.id.buttonClear)
        
        // Set monospace font for better log reading
        textLogs.typeface = android.graphics.Typeface.MONOSPACE
    }

    private fun setupListeners() {
        buttonRefresh.setOnClickListener { loadLogs() }
        buttonClear.setOnClickListener { clearLogs() }
    }

    private fun loadLogs() {
        CoroutineScope(Dispatchers.Main).launch {
            buttonRefresh.isEnabled = false
            buttonRefresh.text = "Loading..."
            
            try {
                val logs = withContext(Dispatchers.IO) {
                    getLogcatOutput()
                }
                
                textLogs.text = logs
                
                // Scroll to bottom
                scrollView.post {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                }
                
            } catch (e: Exception) {
                textLogs.text = "Error loading logs: ${e.message}"
            } finally {
                buttonRefresh.isEnabled = true
                buttonRefresh.text = "Refresh"
            }
        }
    }

    private fun clearLogs() {
        textLogs.text = "Logs cleared. Press Refresh to reload."
        
        // Clear logcat buffer (requires root, but we'll try anyway)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Runtime.getRuntime().exec("logcat -c")
            } catch (e: Exception) {
                // Ignore - clearing logcat requires root
            }
        }
    }

    private fun getLogcatOutput(): String {
        return try {
            // Get recent logs for our app
            val command = arrayOf(
                "logcat",
                "-d",  // dump and exit
                "-v", "time",  // include timestamps
                "-s", "WolService:*,WolHttpServer:*,WakeOnLan:*,MainActivity:*,BootReceiver:*,System.err:*"
            )
            
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            val logs = StringBuilder()
            var line: String?
            var lineCount = 0
            val maxLines = 500  // Limit to prevent memory issues
            
            while (reader.readLine().also { line = it } != null && lineCount < maxLines) {
                logs.appendLine(line)
                lineCount++
            }
            
            reader.close()
            process.destroy()
            
            if (logs.isEmpty()) {
                "No logs found. Make sure the service is running and generating log entries."
            } else {
                "=== Simple WOL Server Logs (Last $lineCount entries) ===\n\n$logs"
            }
            
        } catch (e: Exception) {
            "Error reading logs: ${e.message}\n\n" +
            "Alternative: Use 'adb logcat' from a computer to view detailed logs:\n" +
            "adb logcat -s WolService:* WolHttpServer:* WakeOnLan:* MainActivity:* BootReceiver:*"
        }
    }
}