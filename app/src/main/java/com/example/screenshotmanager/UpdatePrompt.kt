package com.example.screenshotmanager

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import kotlin.concurrent.thread

/**
 * The in-app update flow: checks GitHub, offers the new version in a dialog, downloads it with a
 * progress bar and opens Android's installer. The host activity calls [onResume] so an install
 * can continue after the user allows "install unknown apps" for this app.
 */
class UpdatePrompt(private val activity: AppCompatActivity) {

    private var downloaded: File? = null

    /** [manual]: the user asked, so also say when there is nothing new or the check failed. */
    fun check(manual: Boolean) {
        thread {
            val release = Updater.latestRelease()
            val current = Updater.currentVersion(activity)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                when {
                    release != null && Updater.isNewer(release.version, current) -> offer(release, current)
                    !manual -> Unit
                    release == null -> toast(activity.getString(R.string.update_check_failed))
                    else -> toast(activity.getString(R.string.update_none, current))
                }
            }
        }
    }

    fun onResume() {
        val apk = downloaded ?: return
        if (Updater.canInstall(activity)) install(apk)
    }

    private fun offer(release: Updater.Release, current: String) {
        AppLog.i("Update offered: $current -> ${release.version}")
        val notes = release.notes.take(1500).ifBlank { activity.getString(R.string.update_no_notes) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_title, release.version))
            .setMessage(activity.getString(R.string.update_message, current, notes))
            .setPositiveButton(R.string.update_now) { _, _ -> download(release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun download(release: Updater.Release) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.updateProgress)
        val status = view.findViewById<TextView>(R.id.updateStatus)
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_downloading, release.version))
            .setView(view)
            .setCancelable(false)
            .show()

        thread {
            val result = runCatching {
                Updater.download(activity, release) { percent ->
                    activity.runOnUiThread {
                        progress.isIndeterminate = false
                        progress.setProgressCompat(percent, true)
                        status.text = activity.getString(R.string.update_percent, percent)
                    }
                }
            }
            activity.runOnUiThread {
                dialog.dismiss()
                result.onSuccess { apk ->
                    AppLog.i("Update ${release.version} downloaded")
                    downloaded = apk
                    if (Updater.canInstall(activity)) install(apk) else askInstallPermission()
                }.onFailure {
                    AppLog.e("Update download failed", it)
                    toast(activity.getString(R.string.update_download_failed, it.message ?: it.toString()))
                }
            }
        }
    }

    /** Android requires the user to allow each app that installs updates; it is a one-time switch. */
    private fun askInstallPermission() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_permission_open) { _, _ ->
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> downloaded = null }
            .show()
    }

    private fun install(apk: File) {
        downloaded = null
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
