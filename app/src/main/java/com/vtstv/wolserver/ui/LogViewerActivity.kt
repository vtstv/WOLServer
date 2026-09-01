package com.vtstv.wolserver.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vtstv.wolserver.R
import com.vtstv.wolserver.ui.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * TV Logcat viewer activity that displays recent diagnostic and runtime logs.
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var textLogs: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var buttonRefresh: Button
    private lateinit var buttonClear: Button
    private lateinit var buttonBack: Button

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        initializeViews()
        setupListeners()
        loadLogs()
        buttonRefresh.post { buttonRefresh.requestFocus() }
    }

    private fun initializeViews() {
        textLogs = findViewById(R.id.textLogs)
        scrollView = findViewById(R.id.scrollView)
        buttonRefresh = findViewById(R.id.buttonRefresh)
        buttonClear = findViewById(R.id.buttonClear)
        buttonBack = findViewById(R.id.buttonBack)

        textLogs.typeface = Typeface.MONOSPACE
    }

    private fun setupListeners() {
        buttonBack.setOnClickListener { finish() }
        buttonRefresh.setOnClickListener { loadLogs() }
        buttonClear.setOnClickListener { clearLogs() }

        listOf(buttonBack, buttonRefresh, buttonClear).forEach { btn ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
        }
    }

    private var isLoadingLogs = false

    private fun loadLogs() {
        if (isLoadingLogs) return
        isLoadingLogs = true
        CoroutineScope(Dispatchers.Main).launch {
            buttonRefresh.text = "⏳ ..."

            try {
                val logs = withContext(Dispatchers.IO) {
                    getLogcatOutput()
                }
                textLogs.text = logs
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            } catch (e: Exception) {
                textLogs.text = "Error loading logs: ${e.message}"
            } finally {
                isLoadingLogs = false
                buttonRefresh.text = getString(R.string.btn_refresh)
                buttonRefresh.requestFocus()
            }
        }
    }

    private fun clearLogs() {
        textLogs.text = "Logs cleared. Press Refresh to reload."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Runtime.getRuntime().exec("logcat -c")
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun getLogcatOutput(): String {
        return try {
            val command = arrayOf(
                "logcat",
                "-d",
                "-v", "time",
                "-s", "WolService:*,WolHttpServer:*,WakeOnLan:*,MainActivity:*,BootReceiver:*,System.err:*"
            )

            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val logs = StringBuilder()
            var line: String?
            var lineCount = 0
            val maxLines = 500

            while (reader.readLine().also { line = it } != null && lineCount < maxLines) {
                logs.appendLine(line)
                lineCount++
            }

            reader.close()
            process.destroy()

            if (logs.isEmpty()) {
                "No logs recorded yet. Start the service or send a Wake packet to view logs."
            } else {
                "=== Simple WOL Server Logs ($lineCount entries) ===\n\n$logs"
            }
        } catch (e: Exception) {
            "Error reading logs: ${e.message}\n\nUse 'adb logcat' for live terminal output."
        }
    }
}
