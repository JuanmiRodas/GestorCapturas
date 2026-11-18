package com.example.screenshotmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.IntentCompat
import kotlin.concurrent.thread

/** Handles the notification "Borrar" buttons (when allowed to delete without asking) and swipes. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uris = if (intent.getBooleanExtra(ScreenshotNotifier.EXTRA_ALL_PENDING, false)) {
            ScreenshotNotifier.pendingUris(context)
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, ScreenshotNotifier.EXTRA_URIS, Uri::class.java).orEmpty()
        }

        if (intent.action != ACTION_DELETE) {
            ScreenshotNotifier.handled(context, uris)
            return
        }

        val pending = goAsync()
        thread {
            val deleted = ScreenshotDeleter.delete(context, uris)
            ScreenshotNotifier.handled(context, uris)
            if (deleted < uris.size) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
            pending.finish()
        }
    }

    companion object {
        const val ACTION_DELETE = "com.example.screenshotmanager.DELETE"
        const val ACTION_DISMISSED = "com.example.screenshotmanager.DISMISSED"
    }
}
