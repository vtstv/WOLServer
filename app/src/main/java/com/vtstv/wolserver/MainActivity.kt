package com.vtstv.wolserver

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main configuration activity for the Simple WOL Server application.
 * Provides a simple UI for configuring Wake-on-LAN settings and managing the background service.
 * 
 * Copyright (c) 2025 Murr
 * https://github.com/vtstv/wolserver
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var configManager: ConfigManager
    private lateinit var wakeOnLan: WakeOnLan
    private var currentConfig = WolConfig()
    
    // UI Components
    private lateinit var editWebPassword: EditText
    private lateinit var editAuthToken: EditText
    private lateinit var editMacAddress: EditText
    private lateinit var editBroadcastAddress: EditText
    private lateinit var editWolPort: EditText
    private lateinit var editHttpPort: EditText
    private lateinit var editIpAllowlist: EditText
    private lateinit var switchHttps: Switch
    private lateinit var switchAutoStart: Switch
    private lateinit var switchRequireAuth: Switch
    private lateinit var buttonSave: Button
    private lateinit var buttonTestWake: Button
    private lateinit var buttonServiceControl: Button
    private lateinit var buttonViewLogs: Button
    private lateinit var buttonGenerateToken: Button
    private lateinit var buttonShowToken: Button
    private lateinit var textServiceStatus: TextView
    private lateinit var textServerAddress: TextView
    
    private var tokenVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        configManager = ConfigManager(this)
        wakeOnLan = WakeOnLan()
        
        initializeViews()
        setupListeners()
        loadConfiguration()
        updateServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun initializeViews() {
        editWebPassword = findViewById(R.id.editWebPassword)
        editAuthToken = findViewById(R.id.editAuthToken)
        editMacAddress = findViewById(R.id.editMacAddress)
        editBroadcastAddress = findViewById(R.id.editBroadcastAddress)
        editWolPort = findViewById(R.id.editWolPort)
        editHttpPort = findViewById(R.id.editHttpPort)
        editIpAllowlist = findViewById(R.id.editIpAllowlist)
        switchHttps = findViewById(R.id.switchHttps)
        switchAutoStart = findViewById(R.id.switchAutoStart)
        switchRequireAuth = findViewById(R.id.switchRequireAuth)
        buttonSave = findViewById(R.id.buttonSave)
        buttonTestWake = findViewById(R.id.buttonTestWake)
        buttonServiceControl = findViewById(R.id.buttonServiceControl)
        buttonViewLogs = findViewById(R.id.buttonViewLogs)
        buttonGenerateToken = findViewById(R.id.buttonGenerateToken)
        buttonShowToken = findViewById(R.id.buttonShowToken)
        textServiceStatus = findViewById(R.id.textServiceStatus)
        textServerAddress = findViewById(R.id.textServerAddress)
    }

    private fun setupListeners() {
        buttonSave.setOnClickListener { saveConfiguration() }
        buttonTestWake.setOnClickListener { testWakeOnLan() }
        buttonServiceControl.setOnClickListener { toggleService() }
        buttonViewLogs.setOnClickListener { openLogViewer() }
        buttonGenerateToken.setOnClickListener { generateNewToken() }
        buttonShowToken.setOnClickListener { toggleTokenVisibility() }
        
        // Auto-format MAC address
        editMacAddress.addTextChangedListener { text ->
            val formatted = formatMacAddressInput(text.toString())
            if (formatted != text.toString()) {
                editMacAddress.setText(formatted)
                editMacAddress.setSelection(formatted.length)
            }
        }
        
        // Validate fields
        editMacAddress.addTextChangedListener { validateMacAddress() }
        editHttpPort.addTextChangedListener { validatePort(editHttpPort) }
        editWolPort.addTextChangedListener { validatePort(editWolPort) }
    }

    private fun loadConfiguration() {
        currentConfig = configManager.loadConfig()
        
        editWebPassword.setText(currentConfig.webPassword)
        editAuthToken.setText(currentConfig.authToken)
        editMacAddress.setText(currentConfig.targetMacAddress)
        editBroadcastAddress.setText(currentConfig.broadcastAddress)
        editWolPort.setText(currentConfig.wolPort.toString())
        editHttpPort.setText(currentConfig.httpPort.toString())
        editIpAllowlist.setText(currentConfig.ipAllowlist.joinToString(", "))
        switchHttps.isChecked = currentConfig.httpsEnabled
        switchAutoStart.isChecked = currentConfig.autoStartEnabled
        switchRequireAuth.isChecked = currentConfig.requireAuthentication
    }

    private fun saveConfiguration() {
        try {
            // Validate inputs
            val macAddress = editMacAddress.text.toString().trim()
            if (macAddress.isNotEmpty() && !wakeOnLan.isValidMacAddress(macAddress)) {
                showError("Invalid MAC address format")
                return
            }

            val httpPort = editHttpPort.text.toString().toIntOrNull()
            if (httpPort == null || !currentConfig.isValidPort(httpPort)) {
                showError("HTTP port must be between 1 and 65535")
                return
            }

            val wolPort = editWolPort.text.toString().toIntOrNull()
            if (wolPort == null || !currentConfig.isValidPort(wolPort)) {
                showError("WOL port must be between 1 and 65535")
                return
            }

            // Update configuration
            currentConfig.apply {
                webPassword = editWebPassword.text.toString().trim()
                authToken = editAuthToken.text.toString().trim()
                targetMacAddress = macAddress
                broadcastAddress = editBroadcastAddress.text.toString().trim()
                this.wolPort = wolPort
                this.httpPort = httpPort
                ipAllowlist = parseIpAllowlist(editIpAllowlist.text.toString())
                httpsEnabled = switchHttps.isChecked
                autoStartEnabled = switchAutoStart.isChecked
                requireAuthentication = switchRequireAuth.isChecked
            }

            // Save to storage
            configManager.saveConfig(currentConfig)
            
            // Restart service with new configuration if it's running
            if (WolService.ServiceManager.isServiceRunning(this)) {
                WolService.ServiceManager.restartService(this)
            }
            
            showSuccess("Configuration saved successfully")
            
        } catch (e: Exception) {
            showError("Failed to save configuration: ${e.message}")
        }
    }

    private fun testWakeOnLan() {
        val macAddress = editMacAddress.text.toString().trim()
        if (macAddress.isEmpty()) {
            showError("Please enter a MAC address first")
            return
        }

        if (!wakeOnLan.isValidMacAddress(macAddress)) {
            showError("Invalid MAC address format")
            return
        }

        val broadcastAddress = editBroadcastAddress.text.toString().trim()
        val wolPort = editWolPort.text.toString().toIntOrNull() ?: 9

        CoroutineScope(Dispatchers.Main).launch {
            try {
                buttonTestWake.isEnabled = false
                buttonTestWake.text = "Sending..."
                
                val success = withContext(Dispatchers.IO) {
                    wakeOnLan.sendWakePacket(macAddress, broadcastAddress, wolPort)
                }
                
                if (success) {
                    showSuccess("Wake packet sent successfully!")
                } else {
                    showError("Failed to send wake packet")
                }
            } catch (e: Exception) {
                showError("Error sending wake packet: ${e.message}")
            } finally {
                buttonTestWake.isEnabled = true
                buttonTestWake.text = "Test Wake"
            }
        }
    }

    private fun toggleService() {
        if (WolService.ServiceManager.isServiceRunning(this)) {
            WolService.ServiceManager.stopService(this)
        } else {
            WolService.ServiceManager.startService(this)
        }
        
        // Update status after a short delay
        buttonServiceControl.postDelayed({ updateServiceStatus() }, 1000)
    }

    private fun updateServiceStatus() {
        val isRunning = WolService.ServiceManager.isServiceRunning(this)
        val serverAddress = getLocalIpAddress()
        
        if (isRunning) {
            textServiceStatus.text = "🟢 Service Running"
            textServerAddress.text = "Server: $serverAddress:${currentConfig.httpPort}"
            buttonServiceControl.text = "Stop Service"
        } else {
            textServiceStatus.text = "🔴 Service Stopped"
            textServerAddress.text = "Server: Not running"
            buttonServiceControl.text = "Start Service"
        }
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
            android.util.Log.e(TAG, "Failed to get local IP address", e)
        }
        return "127.0.0.1"
    }

    private fun toggleTokenVisibility() {
        if (tokenVisible) {
            editAuthToken.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            buttonShowToken.text = "👁️ Show"
            tokenVisible = false
        } else {
            editAuthToken.inputType = android.text.InputType.TYPE_CLASS_TEXT
            buttonShowToken.text = "🙈 Hide"
            tokenVisible = true
        }
        // Move cursor to end
        editAuthToken.setSelection(editAuthToken.text.length)
    }

    private fun openLogViewer() {
        val intent = Intent(this, LogViewerActivity::class.java)
        startActivity(intent)
    }

    private fun generateNewToken() {
        AlertDialog.Builder(this, R.style.FireTVAlertDialogTheme)
            .setTitle("Generate New Token")
            .setMessage("This will generate a new authentication token. The old token will no longer work.")
            .setPositiveButton("Generate") { _, _ ->
                val newToken = configManager.generateRandomToken()
                editAuthToken.setText(newToken)
                showSuccess("New token generated")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatMacAddressInput(input: String): String {
        val clean = input.replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.length <= 12) {
            return clean.chunked(2).joinToString(":")
        }
        return input
    }

    private fun validateMacAddress() {
        val mac = editMacAddress.text.toString()
        if (mac.isNotEmpty() && !wakeOnLan.isValidMacAddress(mac)) {
            editMacAddress.error = "Invalid MAC address format"
        } else {
            editMacAddress.error = null
        }
    }

    private fun validatePort(editText: EditText) {
        val port = editText.text.toString().toIntOrNull()
        if (port != null && !currentConfig.isValidPort(port)) {
            editText.error = "Port must be between 1 and 65535"
        } else {
            editText.error = null
        }
    }

    private fun parseIpAllowlist(input: String): List<String> {
        return input.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}