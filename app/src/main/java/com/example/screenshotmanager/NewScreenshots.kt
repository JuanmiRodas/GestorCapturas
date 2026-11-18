package com.example.screenshotmanager

import android.content.Context

/**
 * Finds screenshots taken since the last one notified and notifies them. The last notified id is
 * stored, so screenshots taken while the system had killed the app are still notified later.
 */
object NewScreenshots {

    private const val KEY_LAST_ID = "last_notified_id"

    fun lastNotifiedId(context: Context) =
        context.getSharedPreferences("watcher", Context.MODE_PRIVATE).getLong(KEY_LAST_ID, -1)

    @Synchronized
    fun check(context: Context) {
        val prefs = context.getSharedPreferences("watcher", Context.MODE_PRIVATE)
        if (!ScreenshotStore.hasAccess(context)) {
            AppLog.e("Check skipped: photo access is ${ScreenshotStore.photoAccess(context)}")
            return
        }
        val screenshots = try {
            ScreenshotStore.query(context)
        } catch (e: Exception) {
            AppLog.e("Screenshot query failed", e)
            return
        }
        val lastId = prefs.getLong(KEY_LAST_ID, -1)
        if (lastId < 0) {
            AppLog.i("First check: ${screenshots.size} existing screenshots, not notified")
            // First run: the screenshots already on the phone are not news.
            prefs.edit().putLong(KEY_LAST_ID, screenshots.maxOfOrNull { it.id } ?: 0).apply()
            return
        }
        val fresh = screenshots.filter { it.id > lastId }.sortedBy { it.id }
        if (fresh.isEmpty()) return
        AppLog.i("New screenshots: ${fresh.joinToString { it.name }}")
        prefs.edit().putLong(KEY_LAST_ID, fresh.last().id).apply()
        ScreenshotNotifier.createChannels(context)
        fresh.forEach { ScreenshotNotifier.show(context, it) }
    }
}
