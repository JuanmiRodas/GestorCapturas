package com.example.screenshotmanager

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery optimisation is what makes OxygenOS and other Android versions kill the watcher after a
 * few hours. Being exempt keeps it alive and also lets it restart itself from the background.
 */
object Battery {

    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife")
    fun openSettings(activity: Activity) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
        // Already exempt: show the app's settings, where OxygenOS keeps its own background options.
        val intent = if (isUnrestricted(activity)) appDetails else request
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(appDetails)
        }
    }
}
