package com.etmw.foldpc

import android.content.Context

class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var homeUrl: String
        get() = prefs.getString(KEY_HOME, DEFAULT_HOME)?.trim().orEmpty().ifEmpty { DEFAULT_HOME }
        set(value) {
            prefs.edit().putString(KEY_HOME, normalizeHome(value)).apply()
        }

    var searchUrl: String
        get() = prefs.getString(KEY_SEARCH, DEFAULT_SEARCH).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SEARCH, value.ifBlank { DEFAULT_SEARCH }).apply()
        }

    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ADBLOCK, value).apply()
        }

    var showFunctionRow: Boolean
        get() = prefs.getBoolean(KEY_FUNCTION_ROW, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FUNCTION_ROW, value).apply()
        }

    var keyboardVisible: Boolean
        get() = prefs.getBoolean(KEY_KEYBOARD_VISIBLE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_KEYBOARD_VISIBLE, value).apply()
        }

    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DESKTOP, value).apply()
        }

    var hasSeenWelcome: Boolean
        get() = prefs.getBoolean(KEY_WELCOME, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WELCOME, value).apply()
        }

    fun normalizeHome(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return DEFAULT_HOME
        return if (value.startsWith("http://") || value.startsWith("https://")) value
        else "https://$value"
    }

    fun searchUrlFor(query: String): String {
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        return searchUrl.replace("%s", encoded)
    }

    companion object {
        const val DEFAULT_HOME = "https://entertomyworld.com"
        const val DEFAULT_SEARCH = "https://www.google.com/search?q=%s"

        private const val PREFS = "etmw_fold_pc"
        private const val KEY_HOME = "home_url"
        private const val KEY_SEARCH = "search_url"
        private const val KEY_ADBLOCK = "adblock_enabled"
        private const val KEY_FUNCTION_ROW = "function_row"
        private const val KEY_KEYBOARD_VISIBLE = "keyboard_visible"
        private const val KEY_DESKTOP = "desktop_mode"
        private const val KEY_WELCOME = "welcome_seen"
    }
}
