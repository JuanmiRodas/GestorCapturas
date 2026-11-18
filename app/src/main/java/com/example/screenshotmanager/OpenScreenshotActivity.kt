package com.example.screenshotmanager

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat

/**
 * Transparent trampoline for tapping a notification: removes it (opening counts as handled, so
 * "Borrar todas" no longer includes it) and then shows the screenshot in the gallery.
 */
class OpenScreenshotActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = IntentCompat.getParcelableArrayListExtra(intent, ScreenshotNotifier.EXTRA_URIS, Uri::class.java)
            ?.firstOrNull()
        if (uri != null) {
            ScreenshotNotifier.handled(this, listOf(uri))
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
        finish()
    }
}
