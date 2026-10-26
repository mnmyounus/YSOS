package com.mnmyounus.ysos.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "ysos_prefs"
        const val KEY_SELECTED_COUNTRY_CODE = "selected_country_code"
        const val KEY_SELECTED_COUNTRY_NAME = "selected_country_name"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        const val KEY_FIRST_LAUNCH = "first_launch"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }

    var selectedCountryCode: String
        get() = prefs.getString(KEY_SELECTED_COUNTRY_CODE, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SELECTED_COUNTRY_CODE, value) }

    var selectedCountryName: String
        get() = prefs.getString(KEY_SELECTED_COUNTRY_NAME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SELECTED_COUNTRY_NAME, value) }

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
        set(value) = prefs.edit { putInt(KEY_THEME_MODE, value) }

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_BIOMETRIC_ENABLED, value) }

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = prefs.edit { putBoolean(KEY_FIRST_LAUNCH, value) }
}
