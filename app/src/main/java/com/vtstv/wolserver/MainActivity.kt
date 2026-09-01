package com.vtstv.wolserver

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Main Activity for Simple WOL Server (Fire TV / Android TV).
 * Implements a 10-foot TV UI/UX with full D-Pad navigation, multi-device management,
 * Live Ping Liveness Probing, LAN Network ARP Scanner, and server controls.
 * 
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
class MainActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager
    private lateinit var wakeOnLan: WakeOnLan
    private lateinit var devicePinger: DevicePinger
    private lateinit var networkScanner: NetworkScanner
    private var currentConfig = WolConfig()
    private val devicesList = mutableListOf<WolDevice>()
    private var statusMap = mapOf<String, DevicePinger.PingResult>()
    private lateinit var devicesAdapter: DevicesAdapter

    // UI elements
    private lateinit var textDevicesHeader: TextView
    private lateinit var textServiceStatus: TextView
    private lateinit var textServerAddress: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnHeaderWakeAll: Button
    private lateinit var btnHeaderScan: Button
    private lateinit var btnHeaderSettings: Button
    private lateinit var recyclerDevices: RecyclerView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configManager = ConfigManager(this)
        wakeOnLan = WakeOnLan()
        devicePinger = DevicePinger()
        networkScanner = NetworkScanner(wakeOnLan)

        initViews()
        setupRecyclerView()
        setupListeners()
        loadData()
        updateServiceStatus()
        startPeriodicPing()

        // Ensure background service is running if auto-start enabled
        if (currentConfig.autoStartEnabled && !WolService.ServiceManager.isServiceRunning(this)) {
            WolService.ServiceManager.startService(this)
            recyclerDevices.postDelayed({ updateServiceStatus() }, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        loadData()
        updateServiceStatus()
    }

    private fun initViews() {
        textDevicesHeader = findViewById(R.id.textDevicesHeader)
        textServiceStatus = findViewById(R.id.textServiceStatus)
        textServerAddress = findViewById(R.id.textServerAddress)
        btnToggleService = findViewById(R.id.btnToggleService)
        btnHeaderWakeAll = findViewById(R.id.btnHeaderWakeAll)
        btnHeaderScan = findViewById(R.id.btnHeaderScan)
        btnHeaderSettings = findViewById(R.id.btnHeaderSettings)
        recyclerDevices = findViewById(R.id.recyclerDevices)
    }

    private fun setupRecyclerView() {
        devicesAdapter = DevicesAdapter(
            devices = devicesList,
            getStatus = { devId -> statusMap[devId] },
            onWake = { device -> wakeSingleDevice(device) },
            onEdit = { device -> showDeviceDialog(device) },
            onDelete = { device -> confirmDeleteDevice(device) },
            onAddClick = { showDeviceDialog(null) }
        )

        recyclerDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = devicesAdapter
            setHasFixedSize(false)
        }
    }

    private fun setupListeners() {
        btnHeaderWakeAll.setOnClickListener { wakeAllDevices() }
        btnHeaderScan.setOnClickListener { showNetworkScanDialog() }
        btnHeaderSettings.setOnClickListener { showSettingsDialog() }
        btnToggleService.setOnClickListener { toggleService() }
    }

    private fun loadData() {
        currentConfig = configManager.loadConfig()
        devicesList.clear()
        devicesList.addAll(configManager.loadDevices())
        textDevicesHeader.text = getString(R.string.target_devices_header, devicesList.size)
        devicesAdapter.notifyDataSetChanged()
    }

    private fun startPeriodicPing() {
        lifecycleScope.launch {
            while (isActive) {
                if (devicesList.isNotEmpty()) {
                    val results = withContext(Dispatchers.IO) {
                        devicePinger.pingAll(devicesList)
                    }
                    statusMap = results
                    devicesAdapter.notifyDataSetChanged()
                }
                kotlinx.coroutines.delay(12000L)
            }
        }
    }

    private fun wakeSingleDevice(device: WolDevice) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                wakeOnLan.sendWakePacket(device)
            }
            if (success) {
                configManager.updateDeviceLastWoken(device.id)
                showToast(getString(R.string.toast_woken_device, device.name))
                // Quick ping check after waking
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(3000L)
                    val result = withContext(Dispatchers.IO) { devicePinger.pingDevice(device) }
                    statusMap = statusMap + (device.id to result)
                    devicesAdapter.notifyDataSetChanged()
                }
            } else {
                showToast("⚠️ Failed to wake ${device.name}")
            }
        }
    }

    private fun wakeAllDevices() {
        if (devicesList.isEmpty()) {
            showToast(getString(R.string.toast_no_devices))
            return
        }

        lifecycleScope.launch {
            val count = devicesList.size
            btnHeaderWakeAll.isEnabled = false
            btnHeaderWakeAll.text = "⚡ Waking..."

            withContext(Dispatchers.IO) {
                wakeOnLan.sendWakePackets(devicesList)
            }

            btnHeaderWakeAll.isEnabled = true
            btnHeaderWakeAll.text = getString(R.string.btn_wake_all)
            showToast(getString(R.string.toast_woken_all, count))
        }
    }

    private fun toggleService() {
        if (WolService.ServiceManager.isServiceRunning(this)) {
            WolService.ServiceManager.stopService(this)
        } else {
            WolService.ServiceManager.startService(this)
        }
        btnToggleService.postDelayed({ updateServiceStatus() }, 1000)
    }

    private fun updateServiceStatus() {
        val isRunning = WolService.ServiceManager.isServiceRunning(this)
        val ip = getLocalIpAddress()

        if (isRunning) {
            textServiceStatus.text = getString(R.string.service_running)
            textServiceStatus.setTextColor(Color.parseColor("#00E676"))
            textServerAddress.text = "http://$ip:${currentConfig.httpPort}"
            btnToggleService.text = getString(R.string.btn_stop_service)
            btnToggleService.setBackgroundResource(R.drawable.bg_btn_secondary_selector)
        } else {
            textServiceStatus.text = getString(R.string.service_stopped)
            textServiceStatus.setTextColor(Color.parseColor("#FF5252"))
            textServerAddress.text = "Server: Inactive"
            btnToggleService.text = getString(R.string.btn_start_service)
            btnToggleService.setBackgroundResource(R.drawable.bg_btn_primary_selector)
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
                        if (sAddr != null && sAddr.indexOf(':') < 0) {
                            return sAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return "127.0.0.1"
    }

    // --- Device Add/Edit Dialog ---
    private fun showDeviceDialog(deviceToEdit: WolDevice?) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_device_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.70).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        val inputName = dialog.findViewById<EditText>(R.id.inputDeviceName)
        val inputMac = dialog.findViewById<EditText>(R.id.inputDeviceMac)
        val inputIp = dialog.findViewById<EditText>(R.id.inputDeviceIp)
        val spinnerIcon = dialog.findViewById<Spinner>(R.id.spinnerDeviceIcon)
        val inputBroadcast = dialog.findViewById<EditText>(R.id.inputDeviceBroadcast)
        val inputPort = dialog.findViewById<EditText>(R.id.inputDevicePort)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelDialog)
        val btnSave = dialog.findViewById<Button>(R.id.btnSaveDialog)

        // Setup icon spinner with localized strings
        val iconOptions = listOf(
            getString(R.string.device_type_desktop) to "desktop",
            getString(R.string.device_type_server) to "server",
            getString(R.string.device_type_laptop) to "laptop",
            getString(R.string.device_type_console) to "console",
            getString(R.string.device_type_tv) to "tv"
        )
        val spinnerAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            iconOptions.map { it.first }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerIcon.adapter = spinnerAdapter

        // Populate fields
        if (deviceToEdit != null) {
            dialogTitle.text = getString(R.string.dialog_edit_device_title)
            inputName.setText(deviceToEdit.name)
            inputMac.setText(deviceToEdit.macAddress)
            inputIp.setText(deviceToEdit.ipAddress)
            inputBroadcast.setText(deviceToEdit.broadcastAddress)
            inputPort.setText(deviceToEdit.port.toString())
            val iconIdx = iconOptions.indexOfFirst { it.second == deviceToEdit.iconType }
            if (iconIdx >= 0) spinnerIcon.setSelection(iconIdx)
        } else {
            dialogTitle.text = getString(R.string.dialog_add_device_title)
            inputBroadcast.setText("255.255.255.255")
            inputPort.setText("9")
        }

        // Automatic MAC colon formatting
        inputMac.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return
                isFormatting = true
                val clean = s.toString().replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
                val formatted = clean.chunked(2).joinToString(":")
                if (formatted != s.toString()) {
                    s.replace(0, s.length, formatted)
                }
                isFormatting = false
            }
        })

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val name = inputName.text.toString().trim()
            val mac = inputMac.text.toString().trim()
            val ip = inputIp.text.toString().trim()
            val broadcast = inputBroadcast.text.toString().trim().ifBlank { "255.255.255.255" }
            val port = inputPort.text.toString().toIntOrNull() ?: 9
            val selectedIcon = iconOptions[spinnerIcon.selectedItemPosition].second

            if (name.isEmpty() || mac.isEmpty()) {
                showToast(getString(R.string.toast_empty_fields))
                return@setOnClickListener
            }

            if (!wakeOnLan.isValidMacAddress(mac)) {
                showToast(getString(R.string.toast_invalid_mac))
                return@setOnClickListener
            }

            if (port !in 1..65535) {
                showToast(getString(R.string.toast_invalid_port))
                return@setOnClickListener
            }

            val device = deviceToEdit ?: WolDevice(id = UUID.randomUUID().toString())
            device.name = name
            device.macAddress = wakeOnLan.formatMacAddress(mac)
            device.ipAddress = ip
            device.broadcastAddress = broadcast
            device.port = port
            device.iconType = selectedIcon

            configManager.addOrUpdateDevice(device)
            loadData()
            dialog.dismiss()
            showToast(getString(R.string.toast_device_saved, name))
        }

        dialog.show()
        inputName.requestFocus()
    }

    // --- Network Scanner Dialog ---
    private fun showNetworkScanDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_network_scan)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.75).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val textStatus = dialog.findViewById<TextView>(R.id.textScanStatus)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.scanProgressBar)
        val recycler = dialog.findViewById<RecyclerView>(R.id.recyclerScannedDevices)
        val btnScanNow = dialog.findViewById<Button>(R.id.btnStartSubnetScan)
        val btnClose = dialog.findViewById<Button>(R.id.btnCloseScan)

        val scannedList = mutableListOf<NetworkScanner.DiscoveredDevice>()
        val adapter = ScannedDevicesAdapter(scannedList) { dev ->
            dialog.dismiss()
            val newDev = WolDevice(
                name = dev.hostname,
                macAddress = dev.mac,
                ipAddress = dev.ip,
                iconType = dev.vendor
            )
            showDeviceDialog(newDev)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        fun runScan() {
            btnScanNow.isEnabled = false
            progressBar.visibility = View.VISIBLE
            textStatus.text = getString(R.string.scan_scanning)

            lifecycleScope.launch {
                val results = withContext(Dispatchers.IO) {
                    networkScanner.scanLocalSubnet()
                }
                progressBar.visibility = View.GONE
                btnScanNow.isEnabled = true

                scannedList.clear()
                scannedList.addAll(results)
                adapter.notifyDataSetChanged()

                if (results.isEmpty()) {
                    textStatus.text = getString(R.string.scan_empty)
                } else {
                    textStatus.text = "Found ${results.size} device(s) on local subnet."
                }
            }
        }

        btnScanNow.setOnClickListener { runScan() }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        btnScanNow.requestFocus()
        runScan()
    }

    private fun confirmDeleteDevice(device: WolDevice) {
        AlertDialog.Builder(this, R.style.Theme_Leanback)
            .setTitle(getString(R.string.dialog_delete_confirm_title))
            .setMessage(getString(R.string.dialog_delete_confirm_msg, device.name, device.macAddress))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                configManager.deleteDevice(device.id)
                loadData()
                showToast(getString(R.string.toast_device_deleted, device.name))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // --- Settings Dialog ---
    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_server_settings)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val inputPassword = dialog.findViewById<EditText>(R.id.settingsWebPassword)
        val inputToken = dialog.findViewById<EditText>(R.id.settingsAuthToken)
        val btnToggleToken = dialog.findViewById<Button>(R.id.btnToggleToken)
        val btnGenerateToken = dialog.findViewById<Button>(R.id.btnGenerateToken)
        val inputPort = dialog.findViewById<EditText>(R.id.settingsHttpPort)
        val switchRequireAuth = dialog.findViewById<Switch>(R.id.switchRequireAuth)
        val switchAutoStart = dialog.findViewById<Switch>(R.id.switchAutoStart)
        val spinnerLanguage = dialog.findViewById<Spinner>(R.id.settingsLanguage)
        val btnSettingsLogs = dialog.findViewById<Button>(R.id.btnSettingsLogs)
        val btnSettingsAbout = dialog.findViewById<Button>(R.id.btnSettingsAbout)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelSettings)
        val btnSave = dialog.findViewById<Button>(R.id.btnSaveSettings)

        btnSettingsLogs?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        btnSettingsAbout?.setOnClickListener {
            dialog.dismiss()
            showAboutDialog()
        }

        // Setup Language Spinner
        val langOptions = listOf(
            "en" to getString(R.string.lang_english),
            "de" to getString(R.string.lang_deutsch),
            "ru" to getString(R.string.lang_russian)
        )
        val langAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            langOptions.map { it.second }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerLanguage.adapter = langAdapter
        val currentLang = LocaleHelper.getLanguage(this)
        val initialLangIdx = langOptions.indexOfFirst { it.first == currentLang }.coerceAtLeast(0)
        spinnerLanguage.setSelection(initialLangIdx)

        // Populate current settings
        inputPassword.setText(currentConfig.webPassword)
        inputToken.setText(currentConfig.authToken)
        inputPort.setText(currentConfig.httpPort.toString())
        switchRequireAuth.isChecked = currentConfig.requireAuthentication
        switchAutoStart.isChecked = currentConfig.autoStartEnabled

        var isTokenVisible = false
        btnToggleToken.setOnClickListener {
            isTokenVisible = !isTokenVisible
            inputToken.inputType = if (isTokenVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            inputToken.setSelection(inputToken.text.length)
        }

        btnGenerateToken.setOnClickListener {
            inputToken.setText(configManager.generateRandomToken())
            showToast("New token generated")
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val password = inputPassword.text.toString().trim()
            val token = inputToken.text.toString().trim()
            val port = inputPort.text.toString().toIntOrNull() ?: 8085
            val selectedLangCode = langOptions[spinnerLanguage.selectedItemPosition].first

            if (port !in 1..65535) {
                showToast(getString(R.string.toast_invalid_port))
                return@setOnClickListener
            }

            val portChanged = currentConfig.httpPort != port

            currentConfig.webPassword = password.ifBlank { "admin123" }
            currentConfig.authToken = token.ifBlank { configManager.generateRandomToken() }
            currentConfig.httpPort = port
            currentConfig.requireAuthentication = switchRequireAuth.isChecked
            currentConfig.autoStartEnabled = switchAutoStart.isChecked

            configManager.saveConfig(currentConfig)

            if (selectedLangCode != currentLang) {
                LocaleHelper.setLocale(this, selectedLangCode)
                dialog.dismiss()
                showToast(getString(R.string.toast_settings_saved))
                recreate()
                return@setOnClickListener
            }

            if (portChanged && WolService.ServiceManager.isServiceRunning(this)) {
                WolService.ServiceManager.restartService(this)
            }

            dialog.dismiss()
            updateServiceStatus()
            showToast(getString(R.string.toast_settings_saved))
        }

        dialog.show()
    }

    // --- About Dialog ---
    private fun showAboutDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_about)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.65).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val logo = dialog.findViewById<ImageView>(R.id.aboutAppIcon)
        val glowRing = dialog.findViewById<View>(R.id.aboutGlowRing)
        val btnClose = dialog.findViewById<Button>(R.id.btnAboutClose)

        // Floating Levitation Animation
        val floatAnim = android.animation.ObjectAnimator.ofFloat(logo, "translationY", 0f, -12f, 0f).apply {
            duration = 2400
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        floatAnim.start()

        // Pulsing Scale Animation for Glow Ring
        val scaleXAnim = android.animation.ObjectAnimator.ofFloat(glowRing, "scaleX", 1.0f, 1.20f).apply {
            duration = 1400
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
        }
        val scaleYAnim = android.animation.ObjectAnimator.ofFloat(glowRing, "scaleY", 1.0f, 1.20f).apply {
            duration = 1400
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
        }
        scaleXAnim.start()
        scaleYAnim.start()

        // Continuous Rotation for Glow Ring
        val rotateAnim = android.animation.ObjectAnimator.ofFloat(glowRing, "rotation", 0f, 360f).apply {
            duration = 8000
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        rotateAnim.start()

        dialog.setOnDismissListener {
            floatAnim.cancel()
            scaleXAnim.cancel()
            scaleYAnim.cancel()
            rotateAnim.cancel()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // --- RecyclerView Adapters ---
    class DevicesAdapter(
        private val devices: List<WolDevice>,
        private val getStatus: (String) -> DevicePinger.PingResult?,
        private val onWake: (WolDevice) -> Unit,
        private val onEdit: (WolDevice) -> Unit,
        private val onDelete: (WolDevice) -> Unit,
        private val onAddClick: () -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_DEVICE = 0
            private const val TYPE_ADD = 1
        }

        override fun getItemCount(): Int = devices.size + 1

        override fun getItemViewType(position: Int): Int {
            return if (position < devices.size) TYPE_DEVICE else TYPE_ADD
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_DEVICE) {
                val view = inflater.inflate(R.layout.item_device_card, parent, false)
                DeviceViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.item_add_device, parent, false)
                AddViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is DeviceViewHolder) {
                val device = devices[position]
                holder.bind(device, getStatus(device.id), onWake, onEdit, onDelete)
            } else if (holder is AddViewHolder) {
                holder.bind(onAddClick)
            }
        }

        class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textIcon: TextView = itemView.findViewById(R.id.textDeviceIcon)
            private val textStatus: TextView = itemView.findViewById(R.id.textDeviceStatus)
            private val textName: TextView = itemView.findViewById(R.id.textDeviceName)
            private val textMac: TextView = itemView.findViewById(R.id.textDeviceMac)
            private val textBroadcast: TextView = itemView.findViewById(R.id.textDeviceBroadcast)
            private val btnWake: Button = itemView.findViewById(R.id.btnWakeDevice)
            private val btnEdit: Button = itemView.findViewById(R.id.btnEditDevice)
            private val btnDelete: Button = itemView.findViewById(R.id.btnDeleteDevice)

            fun bind(
                device: WolDevice,
                status: DevicePinger.PingResult?,
                onWake: (WolDevice) -> Unit,
                onEdit: (WolDevice) -> Unit,
                onDelete: (WolDevice) -> Unit
            ) {
                val iconEmoji = when (device.iconType) {
                    "server" -> "🗄️"
                    "laptop" -> "💻"
                    "console" -> "🎮"
                    "tv" -> "📺"
                    else -> "🖥️"
                }

                textIcon.text = iconEmoji
                textName.text = device.name
                textMac.text = device.macAddress
                textBroadcast.text = "${device.broadcastAddress}:${device.port}"

                // Status badge
                if (status != null && status.isOnline) {
                    textStatus.text = itemView.context.getString(R.string.status_online, status.latencyMs)
                    textStatus.setTextColor(Color.parseColor("#00E676"))
                } else {
                    textStatus.text = itemView.context.getString(R.string.status_offline)
                    textStatus.setTextColor(Color.parseColor("#94A3B8"))
                }

                // TV remote scale animation on card focus
                itemView.setOnFocusChangeListener { _, hasFocus ->
                    val scale = if (hasFocus) 1.04f else 1.0f
                    itemView.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                }

                itemView.setOnClickListener { onWake(device) }
                btnWake.setOnClickListener { onWake(device) }
                btnEdit.setOnClickListener { onEdit(device) }
                btnDelete.setOnClickListener { onDelete(device) }
            }
        }

        class AddViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(onAddClick: () -> Unit) {
                itemView.setOnFocusChangeListener { _, hasFocus ->
                    val scale = if (hasFocus) 1.04f else 1.0f
                    itemView.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                }
                itemView.setOnClickListener { onAddClick() }
            }
        }
    }

    class ScannedDevicesAdapter(
        private val items: List<NetworkScanner.DiscoveredDevice>,
        private val onAdd: (NetworkScanner.DiscoveredDevice) -> Unit
    ) : RecyclerView.Adapter<ScannedDevicesAdapter.ViewHolder>() {

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_scanned_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], onAdd)
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textIcon: TextView = itemView.findViewById(R.id.textScannedIcon)
            private val textHost: TextView = itemView.findViewById(R.id.textScannedHostname)
            private val textIp: TextView = itemView.findViewById(R.id.textScannedIp)
            private val textMac: TextView = itemView.findViewById(R.id.textScannedMac)
            private val btnAdd: Button = itemView.findViewById(R.id.btnScannedAdd)

            fun bind(item: NetworkScanner.DiscoveredDevice, onAdd: (NetworkScanner.DiscoveredDevice) -> Unit) {
                textIcon.text = when (item.vendor) {
                    "server" -> "🗄️"
                    "laptop" -> "💻"
                    "console" -> "🎮"
                    "tv" -> "📺"
                    else -> "🖥️"
                }
                textHost.text = item.hostname
                textIp.text = item.ip
                textMac.text = item.mac

                itemView.setOnFocusChangeListener { _, hasFocus ->
                    val scale = if (hasFocus) 1.02f else 1.0f
                    itemView.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                }

                btnAdd.setOnClickListener { onAdd(item) }
                itemView.setOnClickListener { onAdd(item) }
            }
        }
    }
}
