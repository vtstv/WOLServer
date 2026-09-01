/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver.ui.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Utility to manage runtime language switching in the Fire TV / Android TV application.
 * Supports English, Deutsch, and Русский with persistent SharedPreferences storage.
 */
object LocaleHelper {
    private const val KEY_LANGUAGE = "app_language"

    fun onAttach(context: Context): Context {
        val lang = getLanguage(context)
        return setLocale(context, lang)
    }

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("wol_prefs", Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LANGUAGE)) {
            val saved = prefs.getString(KEY_LANGUAGE, null)
            if (!saved.isNullOrBlank()) {
                return when (saved.lowercase()) {
                    "de" -> "de"
                    "ru" -> "ru"
                    else -> "en"
                }
            }
        }
        val systemLang = Locale.getDefault().language.lowercase()
        return when {
            systemLang.startsWith("de") -> "de"
            systemLang.startsWith("ru") -> "ru"
            else -> "en"
        }
    }

    fun setLocale(context: Context, language: String): Context {
        val cleanLang = when (language.lowercase()) {
            "de" -> "de"
            "ru" -> "ru"
            else -> "en"
        }

        val prefs = context.getSharedPreferences("wol_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, cleanLang).apply()

        val locale = Locale(cleanLang)
        Locale.setDefault(locale)

        val res = context.resources
        val config = Configuration(res.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
            return context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            return context
        }
    }

    fun getLanguageDisplayName(language: String): String {
        return when (language.lowercase()) {
            "de" -> "Deutsch"
            "ru" -> "Русский"
            else -> "English"
        }
    }
}
