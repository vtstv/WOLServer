package com.vtstv.wolserver.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.vtstv.wolserver.R
import com.vtstv.wolserver.core.engine.WakeOnLan
import com.vtstv.wolserver.data.model.WolDevice
import com.vtstv.wolserver.data.repository.ConfigManager
import java.util.UUID

/**
 * Add and edit WoL target device dialog for 10-foot TV UI.
 */
object DeviceEditDialog {

    fun show(
        activity: Activity,
        deviceToEdit: WolDevice?,
        configManager: ConfigManager,
        wakeOnLan: WakeOnLan,
        onSaved: () -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_device_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.70).toInt(),
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

        val iconOptions = listOf(
            activity.getString(R.string.device_type_desktop) to "desktop",
            activity.getString(R.string.device_type_server) to "server",
            activity.getString(R.string.device_type_laptop) to "laptop",
            activity.getString(R.string.device_type_console) to "console",
            activity.getString(R.string.device_type_tv) to "tv"
        )
        val spinnerAdapter = ArrayAdapter(
            activity,
            R.layout.item_spinner,
            iconOptions.map { it.first }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerIcon.adapter = spinnerAdapter

        if (deviceToEdit != null) {
            dialogTitle.text = activity.getString(R.string.dialog_edit_device_title)
            inputName.setText(deviceToEdit.name)
            inputMac.setText(deviceToEdit.macAddress)
            inputIp.setText(deviceToEdit.ipAddress)
            inputBroadcast.setText(deviceToEdit.broadcastAddress)
            inputPort.setText(deviceToEdit.port.toString())
            val iconIdx = iconOptions.indexOfFirst { it.second == deviceToEdit.iconType }
            if (iconIdx >= 0) spinnerIcon.setSelection(iconIdx)
        } else {
            dialogTitle.text = activity.getString(R.string.dialog_add_device_title)
            inputBroadcast.setText("255.255.255.255")
            inputPort.setText("9")
        }

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
                Toast.makeText(activity, activity.getString(R.string.toast_empty_fields), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!wakeOnLan.isValidMacAddress(mac)) {
                Toast.makeText(activity, activity.getString(R.string.toast_invalid_mac), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (port !in 1..65535) {
                Toast.makeText(activity, activity.getString(R.string.toast_invalid_port), Toast.LENGTH_SHORT).show()
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
            onSaved()
            dialog.dismiss()
            Toast.makeText(activity, activity.getString(R.string.toast_device_saved, name), Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        inputName.requestFocus()
    }
}
