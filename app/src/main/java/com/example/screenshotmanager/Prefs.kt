package com.example.screenshotmanager

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** User settings; the keys match res/xml/preferences.xml. */
object Prefs {

    const val KEY_NOTIFY = "notify_enabled"
    const val KEY_SHOW_DELETE_ALL = "show_delete_all"
    const val KEY_CONFIRM_IN_APP = "confirm_in_app"
    const val KEY_OLD_DAYS = "old_days"
    const val KEY_LARGE_MB = "large_mb"
    const val KEY_ALL_FILES = "all_files"
    const val KEY_SYSTEM_NOTIFICATIONS = "system_notifications"
    const val KEY_PCLOUD_ACCOUNT = "pcloud_account"
    const val KEY_PCLOUD_DELETE = "pcloud_delete"
    const val KEY_BATTERY = "battery"
    const val KEY_DIAGNOSTICS = "diagnostics"

    fun notificationsEnabled(context: Context) = of(context).getBoolean(KEY_NOTIFY, true)

    fun showDeleteAll(context: Context) = of(context).getBoolean(KEY_SHOW_DELETE_ALL, true)

    fun confirmInApp(context: Context) = of(context).getBoolean(KEY_CONFIRM_IN_APP, true)

    fun deleteFromPcloud(context: Context) = of(context).getBoolean(KEY_PCLOUD_DELETE, true)

    fun oldDays(context: Context) = of(context).getString(KEY_OLD_DAYS, null)?.toIntOrNull() ?: 30

    fun largeMb(context: Context) = of(context).getString(KEY_LARGE_MB, null)?.toIntOrNull() ?: 2

    private fun of(context: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
}
