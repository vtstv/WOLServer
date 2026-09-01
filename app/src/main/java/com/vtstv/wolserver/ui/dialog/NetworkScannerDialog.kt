package com.vtstv.wolserver.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vtstv.wolserver.R
import com.vtstv.wolserver.core.engine.NetworkScanner
import com.vtstv.wolserver.data.model.DiscoveredDevice
import com.vtstv.wolserver.data.model.WolDevice
import com.vtstv.wolserver.ui.adapter.ScannerDeviceAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV Dialog for scanning the local subnet to auto-discover PCs, NAS units, and MAC addresses.
 */
object NetworkScannerDialog {

    fun show(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        networkScanner: NetworkScanner,
        onDeviceSelected: (WolDevice) -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_network_scan)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.75).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val textStatus = dialog.findViewById<TextView>(R.id.textScanStatus)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.scanProgressBar)
        val recycler = dialog.findViewById<RecyclerView>(R.id.recyclerScannedDevices)
        val btnScanNow = dialog.findViewById<Button>(R.id.btnStartSubnetScan)
        val btnClose = dialog.findViewById<Button>(R.id.btnCloseScan)

        val scannedList = mutableListOf<DiscoveredDevice>()
        val adapter = ScannerDeviceAdapter(scannedList) { dev ->
            dialog.dismiss()
            val newDev = WolDevice(
                name = dev.hostname,
                macAddress = dev.mac,
                ipAddress = dev.ip,
                iconType = dev.vendor
            )
            onDeviceSelected(newDev)
        }

        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter

        fun runScan() {
            btnScanNow.isEnabled = false
            progressBar.visibility = View.VISIBLE
            textStatus.text = activity.getString(R.string.scan_scanning)

            lifecycleOwner.lifecycleScope.launch {
                val results = withContext(Dispatchers.IO) {
                    networkScanner.scanLocalSubnet()
                }
                progressBar.visibility = View.GONE
                btnScanNow.isEnabled = true

                scannedList.clear()
                scannedList.addAll(results)
                adapter.notifyDataSetChanged()

                if (results.isEmpty()) {
                    textStatus.text = activity.getString(R.string.scan_empty)
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
}
