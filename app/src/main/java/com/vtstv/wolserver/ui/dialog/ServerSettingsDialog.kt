package com.vtstv.wolserver.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import com.vtstv.wolserver.R
import com.vtstv.wolserver.data.model.WolConfig
import com.vtstv.wolserver.data.repository.ConfigManager
import com.vtstv.wolserver.service.WolService
import com.vtstv.wolserver.ui.LogViewerActivity
import com.vtstv.wolserver.ui.util.LocaleHelper

/**
 * 2-Column TV Server & Security Settings dialog for 10-foot experience.
 */
object ServerSettingsDialog {

    fun show(
        activity: Activity,
        currentConfig: WolConfig,
        configManager: ConfigManager,
        onConfigUpdated: () -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_server_settings)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.85).toInt(),
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
            activity.startActivity(Intent(activity, LogViewerActivity::class.java))
        }

        btnSettingsAbout?.setOnClickListener {
            dialog.dismiss()
            AboutDialog.show(activity)
        }

        val langOptions = listOf(
            "en" to activity.getString(R.string.lang_english),
            "de" to activity.getString(R.string.lang_deutsch),
            "ru" to activity.getString(R.string.lang_russian)
        )
        val langAdapter = ArrayAdapter(
            activity,
            R.layout.item_spinner,
            langOptions.map { it.second }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerLanguage.adapter = langAdapter
        val currentLang = LocaleHelper.getLanguage(activity)
        val initialLangIdx = langOptions.indexOfFirst { it.first == currentLang }.coerceAtLeast(0)
        spinnerLanguage.setSelection(initialLangIdx)

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
            Toast.makeText(activity, "New token generated", Toast.LENGTH_SHORT).show()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val password = inputPassword.text.toString().trim()
            val token = inputToken.text.toString().trim()
            val port = inputPort.text.toString().toIntOrNull() ?: 8085
            val selectedLangCode = langOptions[spinnerLanguage.selectedItemPosition].first

            if (port !in 1..65535) {
                Toast.makeText(activity, activity.getString(R.string.toast_invalid_port), Toast.LENGTH_SHORT).show()
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
                LocaleHelper.setLocale(activity, selectedLangCode)
                dialog.dismiss()
                Toast.makeText(activity, activity.getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
                activity.recreate()
                return@setOnClickListener
            }

            if (portChanged && WolService.ServiceManager.isServiceRunning(activity)) {
                WolService.ServiceManager.restartService(activity)
            }

            dialog.dismiss()
            onConfigUpdated()
            Toast.makeText(activity, activity.getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }
}
