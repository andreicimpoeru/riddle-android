package com.riddle.diary

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    var apiBase: String
        get() = prefs.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE
        set(value) = prefs.edit().putString(KEY_BASE, value.trim()).apply()

    var apiModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    /** API key is optional for local servers (Ollama, etc.); base URL + model are required. */
    fun isConfigured(): Boolean = apiBase.isNotBlank() && apiModel.isNotBlank()

    companion object {
        private const val PREFS = "riddle_settings"
        private const val KEY_API = "api_key"
        private const val KEY_BASE = "api_base"
        private const val KEY_MODEL = "api_model"
        const val DEFAULT_BASE = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}