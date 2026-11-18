package com.example.screenshotmanager

import android.content.Context

/**
 * Watching for screenshots needs no running service and no permanent notification: Android starts
 * [ScreenshotJobService] when a new image appears, even after the app was killed.
 */
object ScreenshotWatcher {

    fun start(context: Context) {
        AppLog.i("Watcher start (photo access ${ScreenshotStore.photoAccess(context)}, battery unrestricted ${Battery.isUnrestricted(context)})")
        ScreenshotNotifier.createChannels(context)
        ScreenshotJobService.schedule(context)
        // Notify anything taken while the app was not watching (or set the starting point on first run).
        Thread { NewScreenshots.check(context.applicationContext) }.start()
    }

    fun stop(context: Context) = ScreenshotJobService.cancel(context)
}
