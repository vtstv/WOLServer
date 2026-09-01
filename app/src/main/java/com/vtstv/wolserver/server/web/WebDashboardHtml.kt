package com.vtstv.wolserver.server.web

/**
 * HTML layout template for Simple WOL Server Web Control Dashboard.
 */
object WebDashboardHtml {

    fun render(hostHeader: String, serverIp: String, port: Int): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Simple WOL Server 2.0 Pro</title>
    <link rel="icon" type="image/png" href="/icon.png">
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&family=JetBrains+Mono:wght@500;600&display=swap" rel="stylesheet">
    <style>
        ${WebDashboardCss.getCss()}
    </style>
</head>
<body>
    <div class="container">

        <!-- Login Overlay -->
        <div id="loginScreen" class="login-box">
            <img src="/icon.png" class="login-logo" alt="Simple WOL Server">
            <h2 style="margin-bottom: 8px;" data-i18n="brandTitle">Simple WOL Server</h2>
            <p style="color: var(--text-secondary); font-size: 13px; margin-bottom: 24px;" data-i18n="enterPassword">Enter web password to access control panel</p>
            <div class="form-group" style="text-align: left; margin-bottom: 20px;">
                <label data-i18n="password">Password</label>
                <input type="password" id="loginPassword" class="form-control" placeholder="••••••••">
            </div>
            <button onclick="login()" class="btn btn-primary" style="width: 100%; justify-content: center;" data-i18n="unlockDashboard">Unlock Dashboard</button>
        </div>

        <!-- Main Dashboard -->
        <div id="dashboardScreen" class="hidden">
            <!-- Navbar -->
            <nav class="navbar">
                <div class="brand">
                    <div style="display: flex; align-items: center; gap: 12px;">
                        <img src="/icon.png" class="brand-img" alt="Simple WOL Server">
                        <div class="brand-text">
                            <h1 data-i18n="brandTitle">Simple WOL Server</h1>
                            <p data-i18n="brandSub">Fire TV / Android TV Edition</p>
                        </div>
                    </div>
                    <div class="server-badge">
                        <span class="pulse-dot"></span>
                        <span id="badgeAddress">$hostHeader</span>
                    </div>
                </div>
                <div class="nav-actions">
                    <button onclick="openScanModal()" class="btn btn-secondary" data-i18n="scanNetwork">🔍 Scan Network</button>
                    <button onclick="openSchedulesModal()" class="btn btn-secondary" data-i18n="schedules">⏰ Schedules</button>
                    <button onclick="openLogsModal()" class="btn btn-secondary" data-i18n="logs">📋 Logs</button>
                    <button onclick="toggleSettings()" class="btn btn-secondary" data-i18n="settings">⚙️ Settings</button>
                    <button onclick="openAboutModal()" class="btn btn-secondary" data-i18n="about">ℹ️ About</button>
                    <button onclick="logout()" class="btn btn-secondary" title="Logout">🚪</button>
                </div>
            </nav>

            <!-- Quick Action Hero -->
            <div class="quick-bar">
                <div class="quick-info">
                    <h2 data-i18n="heroTitle">Multi-Device Wake Control</h2>
                    <p data-i18n="heroSub">Send magic packets individually or wake all devices at once</p>
                </div>
                <div class="quick-actions">
                    <button onclick="wakeAll()" class="btn btn-wake" data-i18n="wakeAll">⚡ Wake All Devices</button>
                    <button onclick="openAddModal()" class="btn btn-primary" data-i18n="addDevice">➕ Add Device</button>
                </div>
            </div>

            <!-- Devices Grid -->
            <div class="section-title">
                <span><span data-i18n="configuredDevices">Configured Devices</span> (<span id="deviceCount">0</span>)</span>
                <div style="display: flex; gap: 8px;">
                    <button onclick="checkDevicesLiveness()" class="btn btn-secondary" style="padding: 6px 12px; font-size: 12px; min-height: 32px;" data-i18n="checkStatus">🟢 Ping Status</button>
                    <button onclick="loadDevices()" class="btn btn-secondary" style="padding: 6px 12px; font-size: 12px; min-height: 32px;" data-i18n="refresh">🔄 Refresh</button>
                </div>
            </div>

            <div id="devicesContainer" class="device-grid">
                <!-- Device cards rendered via JS -->
            </div>

        </div>
    </div>

    <!-- Server Settings Modal -->
    <div id="settingsModal" class="modal-overlay">
        <div class="modal modal-large">
            <div class="modal-header">
                <h3 data-i18n="serverSettingsTitle">⚙️ Server &amp; Security Settings</h3>
                <button onclick="closeSettingsModal()" class="icon-btn" style="border: none;">✖</button>
            </div>

            <div class="form-grid">
                <div class="form-group">
                    <label data-i18n="languageSetting">🌐 Interface Language / Sprache / Язык</label>
                    <select id="cfgLanguage" onchange="setLanguage(this.value)" class="form-control" style="cursor: pointer;">
                        <option value="en">English</option>
                        <option value="de">Deutsch</option>
                        <option value="ru">Русский</option>
                    </select>
                </div>

                <div class="form-group">
                    <label data-i18n="webPassword">Web Dashboard Password</label>
                    <input type="password" id="cfgWebPassword" class="form-control">
                </div>

                <div class="form-group form-group-full">
                    <label data-i18n="apiToken">API Authentication Token (Bearer)</label>
                    <div class="token-card">
                        <div class="token-input-row">
                            <input type="text" id="cfgAuthToken" class="token-display" placeholder="Click generate for new token">
                            <div class="token-actions">
                                <button type="button" onclick="copyToken()" class="btn-token-action" data-i18n="copyToken">📋 Copy</button>
                                <button type="button" onclick="generateToken()" class="btn-token-action" data-i18n="generateToken">🎲 Generate</button>
                            </div>
                        </div>
                        <span class="field-hint" data-i18n="tokenHint">Bearer token for Home Assistant, Apple Shortcuts, and external REST API integrations (/wake, /api/devices).</span>
                    </div>
                </div>

                <div class="form-group">
                    <label data-i18n="httpPort">HTTP Server Port</label>
                    <input type="number" id="cfgHttpPort" class="form-control" value="8085">
                </div>

                <div class="form-group">
                    <label data-i18n="broadcastIp">Default Broadcast IP</label>
                    <input type="text" id="cfgBroadcast" class="form-control" value="255.255.255.255">
                </div>

                <div class="form-group form-group-full">
                    <label data-i18n="ipAllowlist">IP Allowlist (Comma separated, empty for all)</label>
                    <input type="text" id="cfgAllowlist" class="form-control" placeholder="192.168.1.100, 192.168.1.50">
                </div>

                <div class="form-group form-group-full">
                    <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 12px;">
                        <div class="checkbox-group">
                            <input type="checkbox" id="cfgRequireAuth">
                            <label for="cfgRequireAuth" data-i18n="requireAuth">Require Authentication Token for /wake endpoint</label>
                        </div>
                        <div class="checkbox-group">
                            <input type="checkbox" id="cfgAutoStart">
                            <label for="cfgAutoStart" data-i18n="autoStart">Auto-start Server on Device Boot</label>
                        </div>
                    </div>
                </div>

                <div class="form-group form-group-full" style="border-top: 1px solid var(--border-glass); padding-top: 16px; margin-top: 8px;">
                    <label data-i18n="backupTitle">💾 Backup &amp; Restore Configuration</label>
                    <div style="display: flex; flex-wrap: wrap; gap: 10px; margin-top: 6px;">
                        <button type="button" onclick="downloadBackup()" class="btn btn-secondary" data-i18n="downloadBackup">💾 Download JSON Backup</button>
                        <button type="button" onclick="triggerRestoreUpload()" class="btn btn-secondary" data-i18n="restoreBackup">📥 Restore from JSON File</button>
                        <input type="file" id="restoreFileInput" accept=".json" style="display: none;" onchange="handleFileRestore(this)">
                    </div>
                </div>
            </div>

            <div class="modal-footer">
                <button onclick="closeSettingsModal()" class="btn btn-secondary" data-i18n="cancel">Cancel</button>
                <button onclick="saveServerConfig()" class="btn btn-primary" data-i18n="saveSettings">Save &amp; Apply</button>
            </div>
        </div>
    </div>

    <!-- Add/Edit Device Modal -->
    <div id="deviceModal" class="modal-overlay">
        <div class="modal">
            <div class="modal-header">
                <h3 id="modalTitle" data-i18n="addDeviceTitle">+ Add WoL Target Device</h3>
                <button onclick="closeModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <input type="hidden" id="devId">
            <div class="form-group" style="margin-bottom: 14px;">
                <label data-i18n="deviceName">Device Name</label>
                <input type="text" id="devName" class="form-control" placeholder="e.g. Gaming Rig">
            </div>
            <div class="form-group" style="margin-bottom: 14px;">
                <label data-i18n="macAddress">MAC Address</label>
                <input type="text" id="devMac" class="form-control" placeholder="AA:BB:CC:DD:EE:FF" oninput="formatMacInput(this)" style="font-family: 'JetBrains Mono', monospace;">
            </div>
            <div class="form-group" style="margin-bottom: 14px;">
                <label data-i18n="targetIp">Target IP Address (Optional, for Live Ping)</label>
                <input type="text" id="devIp" class="form-control" placeholder="192.168.0.100" style="font-family: 'JetBrains Mono', monospace;">
            </div>
            <div class="form-group" style="margin-bottom: 14px;">
                <label data-i18n="deviceType">Device Type / Icon</label>
                <select id="devIcon" class="form-control">
                    <option value="desktop" data-i18n="typeDesktop">🖥️ Desktop PC</option>
                    <option value="server" data-i18n="typeServer">🗄️ Home Server / NAS</option>
                    <option value="laptop" data-i18n="typeLaptop">💻 Laptop</option>
                    <option value="console" data-i18n="typeConsole">🎮 Game Console</option>
                    <option value="tv" data-i18n="typeTv">📺 Smart TV</option>
                </select>
            </div>
            <div class="form-grid" style="grid-template-columns: 2fr 1fr; margin-bottom: 0;">
                <div class="form-group">
                    <label data-i18n="broadcastIp">Broadcast Address</label>
                    <input type="text" id="devBroadcast" class="form-control" value="255.255.255.255">
                </div>
                <div class="form-group">
                    <label data-i18n="port">Port</label>
                    <input type="number" id="devPort" class="form-control" value="9">
                </div>
            </div>
            <div class="modal-footer">
                <button onclick="closeModal()" class="btn btn-secondary" data-i18n="cancel">Cancel</button>
                <button onclick="saveDeviceModal()" class="btn btn-primary" data-i18n="saveDevice">Save Device</button>
            </div>
        </div>
    </div>

    <!-- Network Scanner Modal -->
    <div id="scanModal" class="modal-overlay">
        <div class="modal modal-large">
            <div class="modal-header">
                <h3 data-i18n="scanTitle">🔍 LAN Network Device Scanner</h3>
                <button onclick="closeScanModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; flex-wrap: wrap; gap: 8px;">
                <span style="font-size: 13px; color: var(--text-secondary);" data-i18n="scanSubtitle">Scan local subnet to automatically discover PCs, servers, and MAC addresses.</span>
                <button onclick="startNetworkScan()" id="btnStartScan" class="btn btn-primary" style="padding: 6px 14px; font-size: 13px; min-height: 34px;" data-i18n="scanNow">🔍 Scan Subnet</button>
            </div>
            <div id="scanResultsContainer">
                <p style="color: var(--text-muted); font-size: 13px; text-align: center; padding: 24px;" data-i18n="scanPrompt">Click 'Scan Subnet' to discover devices on your local network.</p>
            </div>
            <div class="modal-footer">
                <button onclick="closeScanModal()" class="btn btn-secondary" data-i18n="close">Close</button>
            </div>
        </div>
    </div>

    <!-- Schedules Modal -->
    <div id="schedulesModal" class="modal-overlay">
        <div class="modal modal-large">
            <div class="modal-header">
                <h3 data-i18n="schedulesTitle">⏰ Auto-Wake Schedules &amp; Timers</h3>
                <button onclick="closeSchedulesModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 8px;">
                <span style="font-size: 13px; color: var(--text-secondary);" data-i18n="schedulesSubtitle">Automatically wake PCs and servers on designated days and times.</span>
                <button onclick="openAddScheduleModal()" class="btn btn-primary" style="padding: 6px 14px; font-size: 13px; min-height: 34px;" data-i18n="addSchedule">➕ Add Schedule</button>
            </div>
            <div id="schedulesContainer">
                <p style="color: var(--text-muted); font-size: 13px; text-align: center; padding: 20px;">Loading schedules...</p>
            </div>
            <div class="modal-footer">
                <button onclick="closeSchedulesModal()" class="btn btn-secondary" data-i18n="close">Close</button>
            </div>
        </div>
    </div>

    <!-- Add/Edit Schedule Modal -->
    <div id="editScheduleModal" class="modal-overlay" style="z-index: 1100;">
        <div class="modal">
            <div class="modal-header">
                <h3 id="schedModalTitle" data-i18n="addScheduleTitle">+ Add Auto-Wake Schedule</h3>
                <button onclick="closeEditScheduleModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <input type="hidden" id="schedId">
            <div class="form-group" style="margin-bottom: 12px;">
                <label data-i18n="scheduleName">Schedule Name</label>
                <input type="text" id="schedName" class="form-control" placeholder="e.g. Work Morning Wake">
            </div>
            <div class="form-group" style="margin-bottom: 12px;">
                <label data-i18n="targetDevice">Target Device</label>
                <select id="schedDevice" class="form-control"></select>
            </div>
            <div class="form-grid" style="grid-template-columns: 1fr 1fr; margin-bottom: 12px;">
                <div class="form-group">
                    <label data-i18n="timeHour">Hour (0-23)</label>
                    <input type="number" id="schedHour" class="form-control" min="0" max="23" value="8">
                </div>
                <div class="form-group">
                    <label data-i18n="timeMinute">Minute (0-59)</label>
                    <input type="number" id="schedMinute" class="form-control" min="0" max="59" value="30">
                </div>
            </div>
            <div class="form-group" style="margin-bottom: 14px;">
                <label data-i18n="activeDays">Active Days</label>
                <div style="display: flex; gap: 6px; flex-wrap: wrap;" id="schedDaysContainer">
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="1" checked> Mon
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="2" checked> Tue
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="3" checked> Wed
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="4" checked> Thu
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="5" checked> Fri
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="6"> Sat
                    </label>
                    <label style="background: rgba(10,14,23,0.8); border: 1px solid var(--border-glass); padding: 6px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;">
                        <input type="checkbox" value="7"> Sun
                    </label>
                </div>
            </div>
            <div class="modal-footer">
                <button onclick="closeEditScheduleModal()" class="btn btn-secondary" data-i18n="cancel">Cancel</button>
                <button onclick="saveScheduleModal()" class="btn btn-primary" data-i18n="saveSchedule">Save Schedule</button>
            </div>
        </div>
    </div>

    <!-- Integrations Generator Modal -->
    <div id="integrationModal" class="modal-overlay">
        <div class="modal modal-large">
            <div class="modal-header">
                <h3 id="integrationTitle" data-i18n="integrationTitle">🔗 Home Assistant &amp; API Integration</h3>
                <button onclick="closeIntegrationModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <div class="code-tabs">
                <button onclick="switchTab('ha')" id="tabHa" class="code-tab-btn active">Home Assistant (YAML)</button>
                <button onclick="switchTab('shortcuts')" id="tabShortcuts" class="code-tab-btn">Apple Shortcuts (Webhook)</button>
                <button onclick="switchTab('curl')" id="tabCurl" class="code-tab-btn">cURL / Shell</button>
                <button onclick="switchTab('python')" id="tabPython" class="code-tab-btn">Python</button>
            </div>
            <div id="snippetContainer" class="code-snippet-box"></div>
            <div class="modal-footer" style="justify-content: space-between;">
                <span style="font-size: 12px; color: var(--text-muted);" data-i18n="integrationHint">Copy snippet into your home automation platform.</span>
                <div style="display: flex; gap: 8px;">
                    <button onclick="copySnippet()" class="btn btn-primary" style="padding: 8px 16px;" data-i18n="copySnippet">📋 Copy Snippet</button>
                    <button onclick="closeIntegrationModal()" class="btn btn-secondary" data-i18n="close">Close</button>
                </div>
            </div>
        </div>
    </div>

    <!-- Logs Viewer Modal -->
    <div id="logsModal" class="modal-overlay">
        <div class="modal modal-large">
            <div class="modal-header">
                <h3 data-i18n="logsTitle">📋 Server &amp; Network Logs</h3>
                <button onclick="closeLogsModal()" class="icon-btn" style="border: none;">✖</button>
            </div>
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; flex-wrap: wrap; gap: 8px;">
                <span style="font-size: 12px; color: var(--text-secondary);" data-i18n="logsSubtitle">Live logcat entries for WOL service, scheduler, scanner, and HTTP daemon</span>
                <div style="display: flex; gap: 6px;">
                    <button onclick="fetchLogs()" class="btn btn-secondary" style="padding: 6px 12px; font-size: 12px; min-height: 32px;" data-i18n="refresh">🔄 Refresh</button>
                    <button onclick="copyLogs()" class="btn btn-secondary" style="padding: 6px 12px; font-size: 12px; min-height: 32px;" data-i18n="copyLogs">📋 Copy</button>
                    <button onclick="clearServerLogs()" class="btn btn-secondary" style="padding: 6px 12px; font-size: 12px; min-height: 32px; color: var(--accent-red);" data-i18n="clearLogs">🗑️ Clear</button>
                </div>
            </div>
            <div id="logsConsole" class="logs-console">Loading system logs...</div>
            <div class="modal-footer">
                <button onclick="closeLogsModal()" class="btn btn-primary" data-i18n="close">Close</button>
            </div>
        </div>
    </div>

    <!-- About Modal -->
    <div id="aboutModal" class="modal-overlay">
        <div class="modal" style="text-align: center; max-width: 440px;">
            <div style="position: relative; width: 80px; height: 80px; margin: 0 auto 16px auto;">
                <div style="position: absolute; inset: 0; background: radial-gradient(circle, rgba(0,229,255,0.4) 0%, transparent 70%); border-radius: 50%; animation: pulseGlow 2s infinite alternate;"></div>
                <img src="/icon.png" class="about-logo" style="position: relative; margin: 8px auto; animation: floatAnim 2.5s ease-in-out infinite alternate;" alt="Simple WOL Server">
            </div>
            <h2 style="font-size: 22px; font-weight: 800; margin-bottom: 4px;" data-i18n="aboutTitle">Simple WOL Server</h2>
            <p style="color: var(--accent-cyan); font-size: 13px; font-weight: 600; margin-bottom: 14px;" data-i18n="aboutVersion">Version 2.0.0 (Fire TV Edition)</p>
            <p style="color: var(--text-secondary); font-size: 13px; line-height: 1.5; margin-bottom: 20px;" data-i18n="aboutDesc">
                Lightweight Wake-on-LAN daemon &amp; multi-device management hub designed for Amazon Fire TV and Android TV.
            </p>
            <div style="background: rgba(10, 14, 23, 0.7); border: 1px solid var(--border-glass); border-radius: 14px; padding: 16px; margin-bottom: 20px;">
                <div style="font-size: 12px; color: var(--text-muted); margin-bottom: 4px;" data-i18n="createdBy">Created with ❤️ by</div>
                <div style="font-size: 18px; font-weight: 800; color: var(--accent-orange); margin-bottom: 8px;">Murr</div>
                <div style="margin-bottom: 6px;">
                    <a href="https://github.com/vtstv" target="_blank" style="color: var(--accent-cyan); text-decoration: none; font-size: 13px; font-family: monospace;">🌐 github.com/vtstv</a>
                </div>
                <div style="margin-bottom: 8px;">
                    <a href="https://github.com/vtstv/wolserver" target="_blank" style="color: var(--text-secondary); text-decoration: none; font-size: 12px; font-family: monospace;">📦 github.com/vtstv/wolserver</a>
                </div>
                <div style="font-size: 11px; color: var(--text-muted);" data-i18n="allRightsReserved">Copyright © 2025-2026 Murr. All rights reserved.</div>
            </div>
            <button onclick="closeAboutModal()" class="btn btn-primary" style="width: 140px; justify-content: center;" data-i18n="close">Close</button>
        </div>
    </div>

    <!-- Toast container -->
    <div id="toastContainer" class="toast-container"></div>

    <script>
        ${WebDashboardJs.getJs()}
    </script>
</body>
</html>
        """.trimIndent()
    }
}
