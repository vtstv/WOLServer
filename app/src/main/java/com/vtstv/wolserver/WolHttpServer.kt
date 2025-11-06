package com.vtstv.wolserver

import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.util.*

/**
 * Embedded HTTP server for the Simple WOL Server application.
 * Provides REST endpoints for Wake-on-LAN functionality and configuration management.
 * 
 * Copyright (c) 2025 Murr
 * https://github.com/vtstv/wolserver
 */
class WolHttpServer(
    private val port: Int,
    private var config: WolConfig,
    private val configManager: ConfigManager,
    private val wakeOnLan: WakeOnLan,
    private val serverIpAddress: String
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "WolHttpServer"
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val clientIp = session.remoteIpAddress

        Log.i(TAG, "Request: $method $uri from $clientIp")

        // Handle CORS preflight requests
        if (method == Method.OPTIONS) {
            return createCorsResponse(Response.Status.OK, "text/plain", "OK")
        }

        // IP allowlist check
        if (config.ipAllowlist.isNotEmpty() && !isIpAllowed(clientIp)) {
            Log.w(TAG, "IP $clientIp not in allowlist")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access denied")
        }

        val response = when {
            uri == "/" && method == Method.GET -> handleStatusPage()
            uri == "/login" && method == Method.POST -> handleLogin(session)
            uri == "/health" && method == Method.GET -> handleHealth()
            uri == "/wake" && (method == Method.POST || method == Method.GET) -> handleWake(session)
            uri == "/config" && method == Method.GET -> handleGetConfig(session)
            uri == "/config" && method == Method.POST -> handleUpdateConfig(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        // Add CORS headers to all responses
        return addCorsHeaders(response)
    }

    private fun createCorsResponse(status: Response.Status, mimeType: String, txt: String): Response {
        val response = newFixedLengthResponse(status, mimeType, txt)
        return addCorsHeaders(response)
    }

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        response.addHeader("Access-Control-Max-Age", "86400") // 24 hours
        return response
    }

    private fun handleStatusPage(): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Simple WOL Server - Control Panel</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { font-family: Arial, sans-serif; margin: 20px; background-color: #1a1a1a; color: #fff; }
                    .container { max-width: 1000px; margin: 0 auto; }
                    .header { text-align: center; margin-bottom: 30px; }
                    .status { background-color: #2d2d2d; padding: 20px; border-radius: 8px; margin-bottom: 20px; }
                    .config-section { background-color: #2d2d2d; padding: 20px; border-radius: 8px; margin-bottom: 20px; }
                    .button { background-color: #007acc; color: white; padding: 10px 20px; border: none; border-radius: 4px; cursor: pointer; margin: 5px; }
                    .button:hover { background-color: #005a9e; }
                    .button.success { background-color: #28a745; }
                    .button.danger { background-color: #dc3545; }
                    input, textarea, select { background-color: #3d3d3d; color: #fff; border: 1px solid #555; padding: 8px; border-radius: 4px; width: 300px; margin: 5px; }
                    label { display: inline-block; width: 150px; margin-bottom: 10px; color: #ccc; }
                    .row { display: flex; margin-bottom: 10px; align-items: center; }
                    .result { margin-top: 10px; padding: 10px; border-radius: 4px; }
                    .hidden { display: none; }
                    .login-form { max-width: 400px; margin: 100px auto; background-color: #2d2d2d; padding: 30px; border-radius: 8px; }
                    .footer { text-align: center; margin-top: 30px; color: #666; font-size: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <!-- Login Form -->
                    <div id="loginForm" class="login-form">
                        <h2>� Simple WOL Server Login</h2>
                        <div class="row">
                            <label>Password:</label>
                            <input type="password" id="loginPassword" placeholder="Enter password" style="width: 200px;">
                        </div>
                        <button onclick="login()" class="button">Login</button>
                        <div id="loginResult"></div>
                    </div>

                    <!-- Main Interface -->
                    <div id="mainInterface" class="hidden">
                        <div class="header">
                            <h1>🔥 Simple WOL Server Control Panel</h1>
                            <p>Server running on <strong>$serverIpAddress:${config.httpPort}</strong></p>
                        </div>
                        
                        <div class="status">
                            <h2>📊 Status & Quick Actions</h2>
                            <p><strong>Target MAC:</strong> ${config.targetMacAddress.ifEmpty { "Not configured" }}</p>
                            <p><strong>Broadcast:</strong> ${config.broadcastAddress}:${config.wolPort}</p>
                            <p><strong>Last Updated:</strong> ${java.util.Date()}</p>
                            
                            <button onclick="sendWake()" class="button success">💤 Wake Target Computer</button>
                            <button onclick="refreshStatus()" class="button">🔄 Refresh</button>
                            <div id="wakeResult"></div>
                        </div>

                        <div class="config-section">
                            <h2>⚙️ Configuration</h2>
                            
                            <div class="row">
                                <label>Web Password:</label>
                                <input type="password" id="webPassword" value="${config.webPassword}">
                            </div>
                            
                            <div class="row">
                                <label>API Token:</label>
                                <input type="password" id="authToken" value="${config.authToken}">
                                <button onclick="toggleTokenVisibility()" class="button">👁️ Show</button>
                                <button onclick="generateToken()" class="button">🎲 Generate</button>
                            </div>
                            
                            <div class="row">
                                <label>Target MAC:</label>
                                <input type="text" id="macAddress" value="${config.targetMacAddress}" placeholder="AA:BB:CC:DD:EE:FF">
                            </div>
                            
                            <div class="row">
                                <label>Broadcast Address:</label>
                                <input type="text" id="broadcastAddress" value="${config.broadcastAddress}">
                            </div>
                            
                            <div class="row">
                                <label>WOL Port:</label>
                                <input type="number" id="wolPort" value="${config.wolPort}" style="width: 100px;">
                                <label style="margin-left: 20px;">HTTP Port:</label>
                                <input type="number" id="httpPort" value="${config.httpPort}" style="width: 100px;">
                            </div>
                            
                            <div class="row">
                                <label>IP Allowlist:</label>
                                <input type="text" id="ipAllowlist" value="${config.ipAllowlist.joinToString(", ")}" placeholder="192.168.1.0/24, 10.0.0.0/8">
                            </div>
                            
                            <div class="row">
                                <label>Auto-start on boot:</label>
                                <input type="checkbox" id="autoStart" ${if (config.autoStartEnabled) "checked" else ""}>
                                <label style="margin-left: 20px;">HTTPS Enabled:</label>
                                <input type="checkbox" id="httpsEnabled" ${if (config.httpsEnabled) "checked" else ""}>
                            </div>
                            
                            <div class="row">
                                <label>🔐 Require API Authentication:</label>
                                <input type="checkbox" id="requireAuth" ${if (config.requireAuthentication) "checked" else ""}>
                                <span style="font-size: 12px; color: #ccc; margin-left: 10px;">
                                    (When disabled, /wake endpoint works without tokens)
                                </span>
                            </div>
                            
                            <button onclick="saveConfig()" class="button success">💾 Save Configuration</button>
                            <div id="configResult"></div>
                        </div>
                        
                        <div class="footer">
                            <p>Simple WOL Server v1.0.5 | Copyright © 2025 Murr | <a href="https://github.com/vtstv/wolserver" style="color: #007acc;">GitHub</a></p>
                        </div>
                    </div>
                </div>

                <script>
                    let isLoggedIn = false;
                    let tokenVisible = false;
                    let authToken = '';  // Store auth token after login

                    function login() {
                        const password = document.getElementById('loginPassword').value;
                        
                        fetch('/login', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ password: password })
                        })
                        .then(response => response.json())
                        .then(data => {
                            if (data.success) {
                                isLoggedIn = true;
                                authToken = data.authToken || '';  // Store the auth token
                                document.getElementById('loginForm').classList.add('hidden');
                                document.getElementById('mainInterface').classList.remove('hidden');
                            } else {
                                document.getElementById('loginResult').innerHTML = '<p style="color: red;">Invalid password</p>';
                            }
                        })
                        .catch(error => {
                            document.getElementById('loginResult').innerHTML = '<p style="color: red;">Error: ' + error.message + '</p>';
                        });
                    }

                    function sendWake() {
                        const headers = { 'Content-Type': 'application/json' };
                        
                        // Add authentication header if we have a token from login
                        if (authToken) {
                            headers['Authorization'] = 'Bearer ' + authToken;
                        }

                        fetch('/wake', {
                            method: 'POST',
                            headers: headers,
                            body: JSON.stringify({})
                        })
                        .then(response => response.json())
                        .then(data => {
                            const resultDiv = document.getElementById('wakeResult');
                            resultDiv.innerHTML = '<p class="result" style="background-color: ' + (data.success ? '#155724; border: 1px solid #c3e6cb' : '#721c24; border: 1px solid #f5c6cb') + '">' + data.message + '</p>';
                        })
                        .catch(error => {
                            document.getElementById('wakeResult').innerHTML = '<p class="result" style="background-color: #721c24; border: 1px solid #f5c6cb">Error: ' + error.message + '</p>';
                        });
                    }

                    function toggleTokenVisibility() {
                        const tokenField = document.getElementById('authToken');
                        const button = event.target;
                        
                        if (tokenVisible) {
                            tokenField.type = 'password';
                            button.textContent = '👁️ Show';
                            tokenVisible = false;
                        } else {
                            tokenField.type = 'text';
                            button.textContent = '🙈 Hide';
                            tokenVisible = true;
                        }
                    }

                    function generateToken() {
                        const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
                        let token = '';
                        for (let i = 0; i < 32; i++) {
                            token += chars.charAt(Math.floor(Math.random() * chars.length));
                        }
                        document.getElementById('authToken').value = token;
                    }

                    function saveConfig() {
                        const config = {
                            webPassword: document.getElementById('webPassword').value,
                            authToken: document.getElementById('authToken').value,
                            targetMacAddress: document.getElementById('macAddress').value,
                            broadcastAddress: document.getElementById('broadcastAddress').value,
                            wolPort: parseInt(document.getElementById('wolPort').value),
                            httpPort: parseInt(document.getElementById('httpPort').value),
                            ipAllowlist: document.getElementById('ipAllowlist').value.split(',').map(s => s.trim()).filter(s => s.length > 0),
                            autoStartEnabled: document.getElementById('autoStart').checked,
                            httpsEnabled: document.getElementById('httpsEnabled').checked,
                            requireAuthentication: document.getElementById('requireAuth').checked
                        };

                        const headers = { 'Content-Type': 'application/json' };
                        
                        // Add authentication header if we have a token from login
                        if (authToken) {
                            headers['Authorization'] = 'Bearer ' + authToken;
                        }

                        fetch('/config', {
                            method: 'POST',
                            headers: headers,
                            body: JSON.stringify(config)
                        })
                        .then(response => response.json())
                        .then(data => {
                            const resultDiv = document.getElementById('configResult');
                            resultDiv.innerHTML = '<p class="result" style="background-color: ' + (data.success ? '#155724; border: 1px solid #c3e6cb' : '#721c24; border: 1px solid #f5c6cb') + '">' + data.message + '</p>';
                        })
                        .catch(error => {
                            document.getElementById('configResult').innerHTML = '<p class="result" style="background-color: #721c24; border: 1px solid #f5c6cb">Error: ' + error.message + '</p>';
                        });
                    }

                    function refreshStatus() {
                        location.reload();
                    }

                    // Handle Enter key in login
                    document.getElementById('loginPassword').addEventListener('keypress', function(e) {
                        if (e.key === 'Enter') {
                            login();
                        }
                    });
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleLogin(session: IHTTPSession): Response {
        try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            
            val loginRequest = gson.fromJson(postData, Map::class.java)
            val password = loginRequest["password"]?.toString() ?: ""
            
            val success = password == config.webPassword
            
            val response = if (success) {
                mapOf(
                    "success" to true,
                    "message" to "Login successful",
                    "authToken" to config.authToken  // Provide API token for subsequent requests
                )
            } else {
                mapOf(
                    "success" to false,
                    "message" to "Invalid password"
                )
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle login", e)
            val response = mapOf(
                "success" to false,
                "message" to "Login failed: ${e.message}"
            )
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", gson.toJson(response))
        }
    }

    private fun handleHealth(): Response {
        val response = mapOf(
            "status" to "OK",
            "timestamp" to System.currentTimeMillis(),
            "server" to "Simple WOL Server",
            "version" to "1.0"
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
    }

    private fun handleWake(session: IHTTPSession): Response {
        // Check authentication
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", 
                gson.toJson(mapOf("success" to false, "message" to "Authentication required")))
        }

        if (config.targetMacAddress.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "message" to "MAC address not configured")))
        }

        scope.launch {
            val success = wakeOnLan.sendWakePacket(
                config.targetMacAddress,
                config.broadcastAddress,
                config.wolPort
            )
            Log.i(TAG, "Wake packet sent: $success")
        }

        val response = mapOf(
            "success" to true,
            "message" to "Wake packet sent to ${config.targetMacAddress}",
            "timestamp" to System.currentTimeMillis()
        )

        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
    }

    private fun handleGetConfig(session: IHTTPSession): Response {
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        val safeConfig = mapOf(
            "webPassword" to config.webPassword,
            "targetMacAddress" to config.targetMacAddress,
            "broadcastAddress" to config.broadcastAddress,
            "wolPort" to config.wolPort,
            "httpPort" to config.httpPort,
            "ipAllowlist" to config.ipAllowlist,
            "httpsEnabled" to config.httpsEnabled,
            "autoStartEnabled" to config.autoStartEnabled,
            "requireAuthentication" to config.requireAuthentication
        )

        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(safeConfig))
    }

    private fun handleUpdateConfig(session: IHTTPSession): Response {
        // Check authentication
        if (!isAuthenticated(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                gson.toJson(mapOf("error" to "Authentication required")))
        }

        return try {
            // First, try to parse the body
            val files = HashMap<String, String>()
            
            try {
                session.parseBody(files)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing request body", e)
                val response = mapOf(
                    "success" to false,
                    "message" to "Invalid request format: ${e.message}"
                )
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", gson.toJson(response))
            }
            
            // Get the post data - for JSON requests, it should be in "postData"
            val postData = files["postData"] ?: run {
                Log.e(TAG, "No postData found in files: ${files.keys}")
                val response = mapOf(
                    "success" to false,
                    "message" to "No configuration data received"
                )
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", gson.toJson(response))
            }
            
            Log.d(TAG, "Received config update: $postData")

            val updateRequest = try {
                gson.fromJson(postData, Map::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing JSON", e)
                val response = mapOf(
                    "success" to false,
                    "message" to "Invalid JSON format: ${e.message}"
                )
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", gson.toJson(response))
            }
            
            // Update configuration
            updateRequest["webPassword"]?.let { config.webPassword = it.toString() }
            updateRequest["authToken"]?.let { config.authToken = it.toString() }
            updateRequest["targetMacAddress"]?.let { config.targetMacAddress = it.toString() }
            updateRequest["broadcastAddress"]?.let { config.broadcastAddress = it.toString() }
            updateRequest["wolPort"]?.let { config.wolPort = (it as Double).toInt() }
            updateRequest["httpPort"]?.let { config.httpPort = (it as Double).toInt() }
            updateRequest["httpsEnabled"]?.let { config.httpsEnabled = it as Boolean }
            updateRequest["autoStartEnabled"]?.let { config.autoStartEnabled = it as Boolean }
            updateRequest["requireAuthentication"]?.let { config.requireAuthentication = it as Boolean }
            updateRequest["ipAllowlist"]?.let { 
                @Suppress("UNCHECKED_CAST")
                config.ipAllowlist = it as List<String>
            }

            // Save configuration
            configManager.saveConfig(config)

            val response = mapOf(
                "success" to true,
                "message" to "Configuration updated successfully"
            )

            newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in config update", e)
            val response = mapOf(
                "success" to false,
                "message" to "Unexpected error: ${e.message}"
            )
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", gson.toJson(response))
        }
    }

    private fun isAuthenticated(session: IHTTPSession): Boolean {
        // If authentication is disabled, allow access
        if (!config.requireAuthentication) return true
        
        // If authentication is required but no token is configured, deny access
        if (config.authToken.isEmpty()) return false

        // Check Authorization header
        val authHeader = session.headers["authorization"]
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7)
            return token == config.authToken
        }

        // Check query parameter
        val parms = session.parms
        val token = parms["token"]
        return token == config.authToken
    }

    private fun isIpAllowed(clientIp: String): Boolean {
        if (config.ipAllowlist.isEmpty()) return true

        return try {
            val client = InetAddress.getByName(clientIp)
            config.ipAllowlist.any { cidr ->
                isIpInCidr(client, cidr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking IP allowlist", e)
            false
        }
    }

    private fun isIpInCidr(ip: InetAddress, cidr: String): Boolean {
        return try {
            if (!cidr.contains("/")) {
                // Single IP address
                return ip.hostAddress == cidr
            }

            val parts = cidr.split("/")
            val networkAddr = InetAddress.getByName(parts[0])
            val prefixLength = parts[1].toInt()

            val networkBytes = networkAddr.address
            val ipBytes = ip.address

            if (networkBytes.size != ipBytes.size) return false

            val bytesToCheck = prefixLength / 8
            val bitsToCheck = prefixLength % 8

            // Check complete bytes
            for (i in 0 until bytesToCheck) {
                if (networkBytes[i] != ipBytes[i]) return false
            }

            // Check remaining bits
            if (bitsToCheck > 0 && bytesToCheck < networkBytes.size) {
                val mask = (0xFF shl (8 - bitsToCheck)) and 0xFF
                val networkByte = networkBytes[bytesToCheck].toInt() and mask
                val ipByte = ipBytes[bytesToCheck].toInt() and mask
                if (networkByte != ipByte) return false
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking CIDR: $cidr", e)
            false
        }
    }

    override fun start() {
        try {
            super.start()
            Log.i(TAG, "HTTP server started on port $port")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }
    }

    override fun stop() {
        super.stop()
        Log.i(TAG, "HTTP server stopped")
    }
}