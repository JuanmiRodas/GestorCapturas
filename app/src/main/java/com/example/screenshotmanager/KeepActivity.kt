package com.example.screenshotmanager

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlin.concurrent.thread

/**
 * Opened by the notification's "Keep" button: previews the Google Keep note (screenshot, proposed
 * title and recognised text, all editable) and hands it to Keep. Keep has no public API for
 * personal accounts, so the note goes through Keep's own share screen.
 */
class KeepActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = IntentCompat.getParcelableArrayListExtra(intent, ScreenshotNotifier.EXTRA_URIS, Uri::class.java)
            ?.firstOrNull()
        if (uri == null) {
            finish()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_keep, null)
        val preview = view.findViewById<ImageView>(R.id.keepPreview)
        val analyzing = view.findViewById<View>(R.id.analyzing)
        val status = view.findViewById<TextView>(R.id.analyzingStatus)
        val title = view.findViewById<TextInputEditText>(R.id.keepTitle)
        val text = view.findViewById<TextInputEditText>(R.id.keepText)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.keep_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.save_to_keep) { _, _ ->
                sendToKeep(uri, title.text.toString(), text.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()

        thread {
            val thumbnail = runCatching { contentResolver.loadThumbnail(uri, Size(720, 720), null) }.getOrNull()
            runOnUiThread { thumbnail?.let(preview::setImageBitmap) }

            val proposal = ScreenshotAnalyzer.analyze(this, uri)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                // Don't overwrite anything the user already typed.
                if (title.text.isNullOrEmpty()) title.setText(proposal.title)
                if (text.text.isNullOrEmpty()) text.setText(proposal.text)
                analyzing.visibility = View.GONE
                status.visibility = View.GONE
            }
        }
    }

    private fun sendToKeep(uri: Uri, title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/*")
            .setPackage(KEEP_PACKAGE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TITLE, title)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(null, uri)
        try {
            startActivity(intent)
            ScreenshotNotifier.handled(this, listOf(uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.keep_not_installed, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val KEEP_PACKAGE = "com.google.android.keep"
    }
}
