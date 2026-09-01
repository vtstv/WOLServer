/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver.server.web

/**
 * Client-side JavaScript logic for the Simple WOL Server Web Control Dashboard.
 */
object WebDashboardJs {

    fun getJs(): String {
        return """
        ${WebTranslations.getTranslationsJs()}

        function getDefaultLanguage() {
            const navLang = (navigator.language || navigator.userLanguage || 'en').toLowerCase();
            if (navLang.startsWith('ru')) return 'ru';
            if (navLang.startsWith('de')) return 'de';
            return 'en';
        }

        let currentLang = localStorage.getItem('wol_lang') || getDefaultLanguage();

        function setLanguage(lang) {
            if (!i18n[lang]) lang = 'en';
            currentLang = lang;
            localStorage.setItem('wol_lang', lang);
            const selectEl = document.getElementById('cfgLanguage');
            if (selectEl) selectEl.value = lang;

            document.querySelectorAll('[data-i18n]').forEach(el => {
                const key = el.getAttribute('data-i18n');
                if (i18n[lang] && i18n[lang][key]) {
                    el.innerText = i18n[lang][key];
                }
            });

            if (devicesList && devicesList.length >= 0) {
                renderDevices(devicesList);
            }
        }

        let authToken = localStorage.getItem('wol_token') || '';
        let devicesList = [];
        let devicesStatusMap = {};
        let schedulesList = [];
        let selectedDeviceForIntegration = null;
        let currentTab = 'ha';
        let statusPollInterval = null;

        window.onload = function() {
            setLanguage(currentLang);
            if (authToken) {
                checkAuth();
            }
        };

        function t(key, params = {}) {
            let str = (i18n[currentLang] && i18n[currentLang][key]) || (i18n['en'][key]) || key;
            for (const p in params) {
                str = str.replace(new RegExp('\\{' + p + '\\}', 'g'), params[p]);
            }
            return str;
        }

        function showToast(msg, type = 'success') {
            const container = document.getElementById('toastContainer');
            const toast = document.createElement('div');
            toast.className = 'toast ' + type;
            toast.innerHTML = (type === 'success' ? '⚡ ' : '⚠️ ') + msg;
            container.appendChild(toast);
            setTimeout(() => toast.remove(), 4000);
        }

        function checkAuth() {
            fetch('/api/devices', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(res => {
                    if (res.ok) {
                        document.getElementById('loginScreen').classList.add('hidden');
                        document.getElementById('dashboardScreen').classList.remove('hidden');
                        loadDevices();
                        loadConfigData();
                        startStatusPolling();
                    } else {
                        authToken = '';
                        localStorage.removeItem('wol_token');
                    }
                })
                .catch(() => {});
        }

        function login() {
            const password = document.getElementById('loginPassword').value;
            fetch('/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password })
            })
            .then(r => r.json())
            .then(data => {
                if (data.success) {
                    authToken = data.authToken;
                    localStorage.setItem('wol_token', authToken);
                    document.getElementById('loginScreen').classList.add('hidden');
                    document.getElementById('dashboardScreen').classList.remove('hidden');
                    loadDevices();
                    loadConfigData();
                    startStatusPolling();
                    showToast(t('toastLoginSuccess'));
                } else {
                    showToast(t('toastLoginFailed'), 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function logout() {
            if (statusPollInterval) clearInterval(statusPollInterval);
            authToken = '';
            localStorage.removeItem('wol_token');
            document.getElementById('dashboardScreen').classList.add('hidden');
            document.getElementById('loginScreen').classList.remove('hidden');
        }

        function loadDevices() {
            fetch('/api/devices', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(r => r.json())
                .then(devices => {
                    devicesList = devices;
                    renderDevices(devices);
                    checkDevicesLiveness();
                })
                .catch(e => showToast('Failed to load devices: ' + e.message, 'error'));
        }

        function startStatusPolling() {
            if (statusPollInterval) clearInterval(statusPollInterval);
            statusPollInterval = setInterval(() => {
                checkDevicesLiveness();
            }, 10000);
        }

        function checkDevicesLiveness() {
            fetch('/api/devices/status', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(r => r.json())
                .then(res => {
                    if (res.success && res.status) {
                        devicesStatusMap = res.status;
                        updateStatusBadges();
                    }
                })
                .catch(() => {});
        }

        function updateStatusBadges() {
            devicesList.forEach(dev => {
                const el = document.getElementById('status-pill-' + dev.id);
                if (!el) return;
                const status = devicesStatusMap[dev.id];
                if (status && status.isOnline) {
                    el.className = 'status-pill online';
                    el.innerHTML = `<span class="status-dot"></span>` + t('online') + ' (' + status.latencyMs + 'ms)';
                } else {
                    el.className = 'status-pill offline';
                    el.innerHTML = `<span class="status-dot"></span>` + t('offline');
                }
            });
        }

        const iconMap = { desktop: '🖥️', server: '🗄️', laptop: '💻', console: '🎮', tv: '📺' };

        function renderDevices(devices) {
            document.getElementById('deviceCount').innerText = devices.length;
            const container = document.getElementById('devicesContainer');
            container.innerHTML = '';

            devices.forEach(dev => {
                const icon = iconMap[dev.iconType] || '🖥️';
                const status = devicesStatusMap[dev.id];
                const isOnline = status && status.isOnline;
                const statusHtml = isOnline
                    ? '<span id="status-pill-' + dev.id + '" class="status-pill online"><span class="status-dot"></span>' + t('online') + ' (' + status.latencyMs + 'ms)</span>'
                    : '<span id="status-pill-' + dev.id + '" class="status-pill offline"><span class="status-dot"></span>' + t('offline') + '</span>';

                const card = document.createElement('div');
                card.className = 'device-card';
                card.innerHTML = `
                    <div class="card-header">
                        <div class="device-icon-box">` + icon + `</div>
                        <div class="card-actions">
                            <button onclick="openIntegrationModal('` + dev.id + `')" class="icon-btn" title="` + t('integrate') + `">🔗</button>
                            <button onclick="openEditModal('` + dev.id + `')" class="icon-btn" title="` + t('edit') + `">✏️</button>
                            <button onclick="deleteDevice('` + dev.id + `', '` + dev.name + `')" class="icon-btn" title="` + t('delete') + `" style="color: var(--accent-red);">🗑️</button>
                        </div>
                    </div>
                    <div>` + statusHtml + `</div>
                    <div class="device-name">` + dev.name + `</div>
                    <div class="device-meta">
                        <div class="meta-row"><span class="meta-label">MAC:</span><span class="meta-val">` + dev.macAddress + `</span></div>
                        <div class="meta-row"><span class="meta-label">IP:</span><span class="meta-val">` + (dev.ipAddress || 'Broadcast') + `</span></div>
                        <div class="meta-row"><span class="meta-label">Broadcast:</span><span class="meta-val">` + dev.broadcastAddress + `:` + dev.port + `</span></div>
                    </div>
                    <div class="card-footer">
                        <button onclick="wakeDevice('` + dev.id + `', '` + dev.name + `')" class="btn btn-wake btn-card-wake">` + t('wake') + `</button>
                    </div>
                `;
                container.appendChild(card);
            });

            const addCard = document.createElement('div');
            addCard.className = 'add-card';
            addCard.onclick = openAddModal;
            addCard.innerHTML = `<span class="add-icon">➕</span><span style="font-weight: 600;">` + t('addNewDevice') + `</span>`;
            container.appendChild(addCard);
        }

        function wakeDevice(id, name) {
            fetch('/wake?id=' + id, {
                method: 'POST',
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    showToast(t('toastWakeSuccess', { name: name }));
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function wakeAll() {
            fetch('/wake', {
                method: 'POST',
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    showToast(t('toastWakeAllSuccess', { count: res.count || devicesList.length }));
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function openAddModal() {
            document.getElementById('modalTitle').innerText = t('addDeviceTitle');
            document.getElementById('devId').value = '';
            document.getElementById('devName').value = '';
            document.getElementById('devMac').value = '';
            document.getElementById('devIp').value = '';
            document.getElementById('devBroadcast').value = '255.255.255.255';
            document.getElementById('devPort').value = '9';
            document.getElementById('devIcon').value = 'desktop';
            document.getElementById('deviceModal').classList.add('active');
        }

        function openEditModal(id) {
            const dev = devicesList.find(d => d.id === id);
            if (!dev) return;
            document.getElementById('modalTitle').innerText = t('editDeviceTitle');
            document.getElementById('devId').value = dev.id;
            document.getElementById('devName').value = dev.name;
            document.getElementById('devMac').value = dev.macAddress;
            document.getElementById('devIp').value = dev.ipAddress || '';
            document.getElementById('devBroadcast').value = dev.broadcastAddress;
            document.getElementById('devPort').value = dev.port;
            document.getElementById('devIcon').value = dev.iconType || 'desktop';
            document.getElementById('deviceModal').classList.add('active');
        }

        function closeModal() {
            document.getElementById('deviceModal').classList.remove('active');
        }

        function saveDeviceModal() {
            const id = document.getElementById('devId').value;
            const name = document.getElementById('devName').value.trim() || 'Computer';
            const macAddress = document.getElementById('devMac').value.trim();
            const ipAddress = document.getElementById('devIp').value.trim();
            const broadcastAddress = document.getElementById('devBroadcast').value.trim() || '255.255.255.255';
            const port = parseInt(document.getElementById('devPort').value) || 9;
            const iconType = document.getElementById('devIcon').value;

            if (!macAddress || macAddress.length < 12) {
                showToast('Please enter a valid MAC address', 'error');
                return;
            }

            const payload = { id, name, macAddress, ipAddress, broadcastAddress, port, iconType };

            fetch('/api/devices', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken },
                body: JSON.stringify(payload)
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    closeModal();
                    loadDevices();
                    showToast(t('toastSaveSuccess', { name: name }));
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function deleteDevice(id, name) {
            if (!confirm(t('confirmDelete', { name: name }))) return;
            fetch('/api/devices?id=' + id, {
                method: 'DELETE',
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    loadDevices();
                    showToast(t('toastDeleteSuccess', { name: name }));
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function openScanModal() {
            document.getElementById('scanModal').classList.add('active');
        }

        function closeScanModal() {
            document.getElementById('scanModal').classList.remove('active');
        }

        function startNetworkScan() {
            const container = document.getElementById('scanResultsContainer');
            const btn = document.getElementById('btnStartScan');
            btn.disabled = true;
            btn.innerText = 'Scanning...';
            container.innerHTML = `<div style="text-align: center; padding: 28px; color: var(--accent-cyan); font-weight: 600;">⚡ ` + t('scanning') + `</div>`;

            fetch('/api/scan', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(r => r.json())
                .then(res => {
                    btn.disabled = false;
                    btn.innerText = t('scanNow');
                    if (res.success && res.devices && res.devices.length > 0) {
                        renderScanResults(res.devices);
                    } else {
                        container.innerHTML = `<p style="color: var(--text-muted); font-size: 13px; text-align: center; padding: 24px;">No active devices found. Ensure devices are powered on.</p>`;
                    }
                })
                .catch(e => {
                    btn.disabled = false;
                    btn.innerText = t('scanNow');
                    container.innerHTML = `<p style="color: var(--accent-red); font-size: 13px; text-align: center; padding: 24px;">Scan error: ` + e.message + `</p>`;
                });
        }

        function renderScanResults(devices) {
            const container = document.getElementById('scanResultsContainer');
            let html = `
                <table class="scan-table">
                    <thead>
                        <tr>
                            <th>Host / Device</th>
                            <th>IP Address</th>
                            <th>MAC Address</th>
                            <th>Action</th>
                        </tr>
                    </thead>
                    <tbody>
            `;

            devices.forEach(d => {
                const icon = iconMap[d.vendor] || '🖥️';
                html += `
                    <tr>
                        <td><strong>` + icon + ` ` + d.hostname + `</strong></td>
                        <td style="font-family: 'JetBrains Mono', monospace;">` + d.ip + `</td>
                        <td style="font-family: 'JetBrains Mono', monospace; color: var(--accent-cyan);">` + d.mac + `</td>
                        <td>
                            <button onclick="addScannedDevice('` + d.hostname + `', '` + d.mac + `', '` + d.ip + `', '` + d.vendor + `')" class="btn btn-primary" style="padding: 4px 10px; font-size: 12px; min-height: 28px;">➕ Add</button>
                        </td>
                    </tr>
                `;
            });

            html += `</tbody></table>`;
            container.innerHTML = html;
        }

        function addScannedDevice(name, mac, ip, vendor) {
            document.getElementById('devId').value = '';
            document.getElementById('devName').value = name;
            document.getElementById('devMac').value = mac;
            document.getElementById('devIp').value = ip;
            document.getElementById('devBroadcast').value = '255.255.255.255';
            document.getElementById('devPort').value = '9';
            document.getElementById('devIcon').value = vendor || 'desktop';
            closeScanModal();
            document.getElementById('deviceModal').classList.add('active');
        }

        function openSchedulesModal() {
            document.getElementById('schedulesModal').classList.add('active');
            loadSchedulesData();
        }

        function closeSchedulesModal() {
            document.getElementById('schedulesModal').classList.remove('active');
        }

        function loadSchedulesData() {
            const container = document.getElementById('schedulesContainer');
            fetch('/api/schedules', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(r => r.json())
                .then(schedules => {
                    schedulesList = schedules;
                    renderSchedules(schedules);
                })
                .catch(e => {
                    container.innerHTML = `<p style="color: var(--accent-red); font-size: 13px;">Error: ` + e.message + `</p>`;
                });
        }

        const dayNames = { 1: 'Mon', 2: 'Tue', 3: 'Wed', 4: 'Thu', 5: 'Fri', 6: 'Sat', 7: 'Sun' };

        function renderSchedules(schedules) {
            const container = document.getElementById('schedulesContainer');
            if (schedules.length === 0) {
                container.innerHTML = `<p style="color: var(--text-muted); font-size: 13px; text-align: center; padding: 24px;">No auto-wake schedules configured yet.</p>`;
                return;
            }

            container.innerHTML = '';
            schedules.forEach(s => {
                const card = document.createElement('div');
                card.className = 'schedule-card';
                const timeStr = String(s.hour).padStart(2, '0') + ':' + String(s.minute).padStart(2, '0');
                const daysStr = (s.daysOfWeek || []).map(d => dayNames[d] || d).join(', ');

                card.innerHTML = `
                    <div style="flex: 1;">
                        <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">
                            <span style="font-size: 17px; font-weight: 700; font-family: 'JetBrains Mono', monospace; color: var(--accent-cyan);">` + timeStr + `</span>
                            <span style="font-size: 14px; font-weight: 600; color: #fff;">` + s.name + `</span>
                        </div>
                        <div style="font-size: 12px; color: var(--text-secondary);">
                            Target: <strong style="color: #fff;">` + s.deviceName + `</strong> | Days: <strong style="color: var(--accent-orange);">` + daysStr + `</strong>
                        </div>
                    </div>
                    <div style="display: flex; align-items: center; gap: 8px;">
                        <input type="checkbox" ` + (s.enabled ? 'checked' : '') + ` onchange="toggleScheduleActive('` + s.id + `', this.checked)" style="width: 20px; height: 20px; accent-color: var(--accent-green); cursor: pointer;">
                        <button onclick="deleteSchedule('` + s.id + `')" class="icon-btn" title="Delete" style="color: var(--accent-red);">🗑️</button>
                    </div>
                `;
                container.appendChild(card);
            });
        }

        function openAddScheduleModal() {
            document.getElementById('schedId').value = '';
            document.getElementById('schedName').value = 'Morning Auto-Wake';
            document.getElementById('schedHour').value = '8';
            document.getElementById('schedMinute').value = '30';

            const select = document.getElementById('schedDevice');
            select.innerHTML = '<option value="all">⚡ All Configured Devices</option>';
            devicesList.forEach(dev => {
                select.innerHTML += `<option value="` + dev.id + `">` + dev.name + ` (` + dev.macAddress + `)</option>`;
            });

            document.getElementById('editScheduleModal').classList.add('active');
        }

        function closeEditScheduleModal() {
            document.getElementById('editScheduleModal').classList.remove('active');
        }

        function saveScheduleModal() {
            const id = document.getElementById('schedId').value;
            const name = document.getElementById('schedName').value.trim() || 'Auto Wake';
            const deviceId = document.getElementById('schedDevice').value;
            const hour = parseInt(document.getElementById('schedHour').value) || 0;
            const minute = parseInt(document.getElementById('schedMinute').value) || 0;

            const days = [];
            document.querySelectorAll('#schedDaysContainer input:checked').forEach(cb => {
                days.push(parseInt(cb.value));
            });

            const payload = { id, name, deviceId, hour, minute, daysOfWeek: days, enabled: true };

            fetch('/api/schedules', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken },
                body: JSON.stringify(payload)
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    closeEditScheduleModal();
                    loadSchedulesData();
                    showToast(t('toastScheduleSaved', { name: name }));
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function toggleScheduleActive(id, enabled) {
            const sched = schedulesList.find(s => s.id === id);
            if (!sched) return;
            sched.enabled = enabled;
            fetch('/api/schedules', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken },
                body: JSON.stringify(sched)
            });
        }

        function deleteSchedule(id) {
            fetch('/api/schedules?id=' + id, {
                method: 'DELETE',
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                loadSchedulesData();
                showToast(t('toastScheduleDeleted'));
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function openIntegrationModal(deviceId) {
            selectedDeviceForIntegration = devicesList.find(d => d.id === deviceId);
            if (!selectedDeviceForIntegration) return;
            document.getElementById('integrationTitle').innerText = '🔗 Integration: ' + selectedDeviceForIntegration.name;
            switchTab('ha');
            document.getElementById('integrationModal').classList.add('active');
        }

        function closeIntegrationModal() {
            document.getElementById('integrationModal').classList.remove('active');
        }

        function switchTab(tab) {
            currentTab = tab;
            document.querySelectorAll('.code-tab-btn').forEach(btn => btn.classList.remove('active'));
            if (tab === 'ha') document.getElementById('tabHa').classList.add('active');
            if (tab === 'shortcuts') document.getElementById('tabShortcuts').classList.add('active');
            if (tab === 'curl') document.getElementById('tabCurl').classList.add('active');
            if (tab === 'python') document.getElementById('tabPython').classList.add('active');

            renderSnippet();
        }

        function renderSnippet() {
            if (!selectedDeviceForIntegration) return;
            const dev = selectedDeviceForIntegration;
            const host = window.location.host;
            const token = authToken || 'YOUR_AUTH_TOKEN';
            const box = document.getElementById('snippetContainer');

            if (currentTab === 'ha') {
                box.innerText = 
`# Home Assistant configuration.yaml
rest_command:
  wake_` + dev.name.toLowerCase().replace(/\\s+/g, '_') + `:
    url: "http://` + host + `/wake?id=` + dev.id + `"
    method: "POST"
    headers:
      Authorization: "Bearer ` + token + `"

# Switch Entity Template:
switch:
  - platform: template
    switches:
      ` + dev.name.toLowerCase().replace(/\\s+/g, '_') + `_power:
        friendly_name: "` + dev.name + `"
        turn_on:
          service: rest_command.wake_` + dev.name.toLowerCase().replace(/\\s+/g, '_');
            } else if (currentTab === 'shortcuts') {
                box.innerText = 
`# Apple Shortcuts / Webhook URL
Method: POST
URL: http://` + host + `/wake?id=` + dev.id + `&token=` + token;
            } else if (currentTab === 'curl') {
                box.innerText = 
`# cURL Terminal Command:
curl -X POST "http://` + host + `/wake?id=` + dev.id + `" \\
     -H "Authorization: Bearer ` + token + `"`;
            } else if (currentTab === 'python') {
                box.innerText = 
`import requests

url = "http://` + host + `/wake?id=` + dev.id + `"
headers = {"Authorization": "Bearer ` + token + `"}

response = requests.post(url, headers=headers)
print("WoL Status:", response.json())`;
            }
        }

        function copySnippet() {
            const text = document.getElementById('snippetContainer').innerText;
            copyTextToClipboard(text, t('toastSnippetCopied'));
        }

        function downloadBackup() {
            window.location.href = '/api/backup?token=' + authToken;
            showToast(t('toastBackupDownloaded'));
        }

        function triggerRestoreUpload() {
            document.getElementById('restoreFileInput').click();
        }

        function handleFileRestore(input) {
            const file = input.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = function(e) {
                try {
                    const json = JSON.parse(e.target.result);
                    fetch('/api/restore', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken },
                        body: JSON.stringify(json)
                    })
                    .then(r => r.json())
                    .then(res => {
                        if (res.success) {
                            showToast(t('toastRestoreSuccess'));
                            loadDevices();
                            loadConfigData();
                        } else {
                            showToast(res.message, 'error');
                        }
                    })
                    .catch(err => showToast(err.message, 'error'));
                } catch (err) {
                    showToast('Invalid JSON file format', 'error');
                }
            };
            reader.readAsText(file);
        }

        function openLogsModal() {
            document.getElementById('logsModal').classList.add('active');
            fetchLogs();
        }

        function closeLogsModal() {
            document.getElementById('logsModal').classList.remove('active');
        }

        function fetchLogs() {
            const consoleBox = document.getElementById('logsConsole');
            consoleBox.innerText = 'Loading system logs...';
            fetch('/api/logs', {
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    consoleBox.innerText = res.logs || 'No logs available.';
                    consoleBox.scrollTop = consoleBox.scrollHeight;
                } else {
                    consoleBox.innerText = 'Error loading logs: ' + (res.error || res.message);
                }
            })
            .catch(e => {
                consoleBox.innerText = 'Error fetching logs: ' + e.message;
            });
        }

        function clearServerLogs() {
            fetch('/api/logs', {
                method: 'DELETE',
                headers: { 'Authorization': 'Bearer ' + authToken }
            })
            .then(r => r.json())
            .then(res => {
                document.getElementById('logsConsole').innerText = 'Logs cleared.';
                showToast('Logs cleared');
            })
            .catch(e => showToast(e.message, 'error'));
        }

        function copyLogs() {
            const text = document.getElementById('logsConsole').innerText;
            copyTextToClipboard(text, t('toastLogsCopied'));
        }

        function copyToken() {
            const token = document.getElementById('cfgAuthToken').value;
            if (!token) return;
            copyTextToClipboard(token, t('toastTokenCopied'));
        }

        function copyTextToClipboard(text, successMessage) {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(() => {
                    showToast(successMessage);
                }).catch(() => fallbackCopy(text, successMessage));
            } else {
                fallbackCopy(text, successMessage);
            }
        }

        function fallbackCopy(text, successMessage) {
            const ta = document.createElement('textarea');
            ta.value = text;
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
            showToast(successMessage);
        }

        function formatMacInput(elem) {
            let val = elem.value.replace(/[^0-9A-Fa-f]/g, '').toUpperCase();
            if (val.length > 12) val = val.substring(0, 12);
            let formatted = val.match(/.{1,2}/g)?.join(':') || val;
            elem.value = formatted;
        }

        function toggleSettings() {
            openSettingsModal();
        }

        function openSettingsModal() {
            loadConfigData();
            document.getElementById('settingsModal').classList.add('active');
        }

        function closeSettingsModal() {
            document.getElementById('settingsModal').classList.remove('active');
        }

        function openAboutModal() {
            document.getElementById('aboutModal').classList.add('active');
        }

        function closeAboutModal() {
            document.getElementById('aboutModal').classList.remove('active');
        }

        function loadConfigData() {
            fetch('/config', { headers: { 'Authorization': 'Bearer ' + authToken } })
                .then(r => r.json())
                .then(cfg => {
                    document.getElementById('cfgWebPassword').value = cfg.webPassword || '';
                    document.getElementById('cfgAuthToken').value = cfg.authToken || '';
                    document.getElementById('cfgBroadcast').value = cfg.broadcastAddress || '255.255.255.255';
                    document.getElementById('cfgHttpPort').value = cfg.httpPort || 8085;
                    document.getElementById('cfgAllowlist').value = (cfg.ipAllowlist || []).join(', ');
                    document.getElementById('cfgRequireAuth').checked = cfg.requireAuthentication !== false;
                    document.getElementById('cfgAutoStart').checked = cfg.autoStartEnabled !== false;
                    const langEl = document.getElementById('cfgLanguage');
                    if (langEl) langEl.value = currentLang;
                })
                .catch(() => {});
        }

        function generateToken() {
            const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
            let t = '';
            for (let i = 0; i < 32; i++) t += chars.charAt(Math.floor(Math.random() * chars.length));
            document.getElementById('cfgAuthToken').value = t;
        }

        function saveServerConfig() {
            const payload = {
                webPassword: document.getElementById('cfgWebPassword').value,
                authToken: document.getElementById('cfgAuthToken').value,
                broadcastAddress: document.getElementById('cfgBroadcast').value,
                httpPort: parseInt(document.getElementById('cfgHttpPort').value) || 8085,
                ipAllowlist: document.getElementById('cfgAllowlist').value.split(',').map(s => s.trim()).filter(s => s.length > 0),
                requireAuthentication: document.getElementById('cfgRequireAuth').checked,
                autoStartEnabled: document.getElementById('cfgAutoStart').checked
            };

            fetch('/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken },
                body: JSON.stringify(payload)
            })
            .then(r => r.json())
            .then(res => {
                if (res.success) {
                    showToast(t('toastSettingsSuccess'));
                    closeSettingsModal();
                } else {
                    showToast(res.message, 'error');
                }
            })
            .catch(e => showToast(e.message, 'error'));
        }
        """.trimIndent()
    }
}
