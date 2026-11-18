package com.example.screenshotmanager

import android.Manifest
import android.app.job.JobScheduler
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the report shown and shared from Ajustes → Diagnóstico. Blocking (it calls pCloud). */
object Diagnostics {

    fun report(context: Context): String = buildString {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        appendLine("Gestor de capturas ${packageInfo.versionName} (${packageInfo.longVersionCode})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
        appendLine("Time: ${now()}")
        appendLine()

        appendLine("== Permissions")
        appendLine("Photo access: ${ScreenshotStore.photoAccess(context)}")
        for (permission in listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )) {
            appendLine("  $permission: ${ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED}")
        }
        appendLine("Notifications enabled: ${NotificationManagerCompat.from(context).areNotificationsEnabled()}")
        appendLine("All files access (delete without asking): ${ScreenshotDeleter.canDeleteSilently()}")
        appendLine("Battery unrestricted: ${Battery.isUnrestricted(context)}")
        appendLine()

        appendLine("== Watcher")
        appendLine("Notify setting on: ${Prefs.notificationsEnabled(context)}")
        appendLine("Job scheduled: ${context.getSystemService(JobScheduler::class.java).getPendingJob(ScreenshotJobService.JOB_ID) != null}")
        appendLine("Last notified id: ${NewScreenshots.lastNotifiedId(context)}")
        appendLine()

        appendLine("== Screenshots found by the app")
        val screenshots = runCatching { ScreenshotStore.query(context) }
        screenshots.onFailure { appendLine("Query failed: $it") }
        screenshots.getOrNull()?.let { list ->
            appendLine("Count: ${list.size}")
            list.take(5).forEach { appendLine("  #${it.id} ${it.name} ${date(it.dateAddedSeconds)}") }
        }
        appendLine("Newest images on the phone (any folder):")
        recentImages(context).forEach { appendLine("  $it") }
        appendLine()

        appendLine("== pCloud")
        appendLine("Connected: ${Pcloud.isConnected(context)} ${Pcloud.email(context).orEmpty()}")
        if (Pcloud.isConnected(context)) appendLine("Session check: ${Pcloud.checkSession(context)}")
        appendLine("Waiting to delete after re-login: ${Pcloud.pendingDeletes(context).size}")
        appendLine("Delete from pCloud setting on: ${Prefs.deleteFromPcloud(context)}")
        appendLine()

        appendLine("== Log")
        append(AppLog.read().takeLast(60_000))
    }

    /** Folder and date of the newest images, to spot screenshots saved somewhere unexpected. */
    private fun recentImages(context: Context): List<String> = runCatching {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.IS_PENDING,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < 8) {
                    val id = cursor.getLong(0)
                    add("#$id ${cursor.getString(1)}${cursor.getString(2)} ${date(cursor.getLong(3))} pending=${cursor.getInt(4)}")
                }
            }
        }.orEmpty()
    }.getOrElse { listOf("Query failed: $it") }

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun date(seconds: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(seconds * 1000))
}
