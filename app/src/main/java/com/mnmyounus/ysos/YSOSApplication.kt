package com.mnmyounus.ysos

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.mnmyounus.ysos.data.EmergencyDatabase
import com.mnmyounus.ysos.util.PreferencesManager

class YSOSApplication : Application() {

    val database: EmergencyDatabase by lazy { EmergencyDatabase.getDatabase(this) }
    val preferences: PreferencesManager by lazy { PreferencesManager(this) }

    override fun onCreate() {
        super.onCreate()
        applyTheme(preferences.themeMode)
    }

    fun applyTheme(mode: Int) {
        val nightMode = when (mode) {
            PreferencesManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            PreferencesManager.THEME_DARK  -> AppCompatDelegate.MODE_NIGHT_YES
            else                           -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
