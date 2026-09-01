package com.vtstv.wolserver.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vtstv.wolserver.R
import com.vtstv.wolserver.core.engine.DevicePinger
import com.vtstv.wolserver.core.engine.NetworkScanner
import com.vtstv.wolserver.core.engine.WakeOnLan
import com.vtstv.wolserver.data.model.WolConfig
import com.vtstv.wolserver.data.model.WolDevice
import com.vtstv.wolserver.data.repository.ConfigManager
import com.vtstv.wolserver.service.WolService
import com.vtstv.wolserver.ui.adapter.DeviceCardAdapter
import com.vtstv.wolserver.ui.dialog.DeviceEditDialog
import com.vtstv.wolserver.ui.dialog.NetworkScannerDialog
import com.vtstv.wolserver.ui.dialog.ServerSettingsDialog
import com.vtstv.wolserver.ui.util.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.Collections

/**
 * Main TV Activity for Simple WOL Server (Fire TV / Android TV).
 * Implements a 10-foot TV UI/UX with full D-Pad navigation, multi-device management,
 * Live Ping Liveness Probing, LAN Network ARP Scanner, and server controls.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager
    private lateinit var wakeOnLan: WakeOnLan
    private lateinit var devicePinger: DevicePinger
    private lateinit var networkScanner: NetworkScanner
    private var currentConfig = WolConfig()
    private val devicesList = mutableListOf<WolDevice>()
    private var statusMap = mapOf<String, DevicePinger.PingResult>()
    private lateinit var devicesAdapter: DeviceCardAdapter

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
        devicesAdapter = DeviceCardAdapter(
            devices = devicesList,
            getStatus = { devId -> statusMap[devId] },
            onWake = { device -> wakeSingleDevice(device) },
            onEdit = { device -> DeviceEditDialog.show(this, device, configManager, wakeOnLan) { loadData() } },
            onDelete = { device -> confirmDeleteDevice(device) },
            onAddClick = { DeviceEditDialog.show(this, null, configManager, wakeOnLan) { loadData() } }
        )

        recyclerDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = devicesAdapter
            setHasFixedSize(false)
        }
    }

    private fun setupListeners() {
        btnHeaderWakeAll.setOnClickListener { wakeAllDevices() }
        btnHeaderScan.setOnClickListener {
            NetworkScannerDialog.show(this, this, networkScanner) { dev ->
                DeviceEditDialog.show(this, dev, configManager, wakeOnLan) { loadData() }
            }
        }
        btnHeaderSettings.setOnClickListener {
            ServerSettingsDialog.show(this, currentConfig, configManager) {
                loadData()
                updateServiceStatus()
            }
        }
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
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
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

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
