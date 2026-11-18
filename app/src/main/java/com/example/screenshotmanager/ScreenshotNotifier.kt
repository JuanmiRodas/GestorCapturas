package com.example.screenshotmanager

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Each pending screenshot has its own notification, tagged with its URI. A screenshot counts as
 * pending exactly while its notification is showing: opening it, sending it to Keep, swiping it away or deleting it
 * removes it, and "Borrar todas" only touches the ones still showing.
 */
object ScreenshotNotifier {

    const val EXTRA_URIS = "uris"
    const val EXTRA_ALL_PENDING = "all_pending"

    private const val SCREENSHOT_NOTIFICATION_ID = 2
    private const val SUMMARY_NOTIFICATION_ID = 3
    private const val PCLOUD_EXPIRED_NOTIFICATION_ID = 4
    private const val SUMMARY_REQUEST_CODE = -1

    // A channel's lock-screen visibility can't change after creation, so hiding them needed new channels.
    private const val SCREENSHOT_CHANNEL = "screenshots_v2"
    // The watcher no longer runs as a service, so its permanent-notification channel goes too.
    private val OLD_CHANNELS = listOf("watcher", "screenshots", "watcher_v2")

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        OLD_CHANNELS.forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannel(
            NotificationChannel(SCREENSHOT_CHANNEL, context.getString(R.string.channel_screenshots), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, screenshot: Screenshot) {
        val requestCode = screenshot.id.toInt()
        val uris = listOf(screenshot.uri)
        val thumbnail = thumbnail(context, screenshot)

        val open = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, OpenScreenshotActivity::class.java)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris)),
            PendingIntent.FLAG_IMMUTABLE
        )
        val delete = deleteIntent(context, requestCode, Intent().putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris)))
        val dismissed = actionIntent(context, NotificationActionReceiver.ACTION_DISMISSED, requestCode, uris)
        val keep = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, KeepActivity::class.java).putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris)),
            PendingIntent.FLAG_IMMUTABLE
        )

        fun views(layout: Int) = RemoteViews(context.packageName, layout).apply {
            thumbnail?.let { setImageViewBitmap(R.id.thumbnail, it) }
            setOnClickPendingIntent(R.id.thumbnail, open)
            setOnClickPendingIntent(R.id.delete, delete)
            setOnClickPendingIntent(R.id.keep, keep)
        }

        val notification = NotificationCompat.Builder(context, SCREENSHOT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_screenshot)
            .setContentTitle(context.getString(R.string.screenshot_title))
            .setContentText(screenshot.name)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.notification_small))
            .setCustomHeadsUpContentView(views(R.layout.notification_small))
            .setCustomBigContentView(views(R.layout.notification_big))
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        val tag = screenshot.uri.toString()
        NotificationManagerCompat.from(context).notify(tag, SCREENSHOT_NOTIFICATION_ID, notification)
        refreshSummary(context, added = tag)
    }

    /** Tells the user that pCloud needs a new login; tapping it opens the settings. */
    @SuppressLint("MissingPermission")
    fun showPcloudExpired(context: Context) {
        createChannels(context)
        val settings = PendingIntent.getActivity(
            context,
            PCLOUD_EXPIRED_NOTIFICATION_ID,
            Intent(context, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, SCREENSHOT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_screenshot)
            .setContentTitle(context.getString(R.string.pcloud_expired_title))
            .setContentText(context.getString(R.string.pcloud_expired_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.pcloud_expired_text)))
            .setContentIntent(settings)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        NotificationManagerCompat.from(context).notify(PCLOUD_EXPIRED_NOTIFICATION_ID, notification)
    }

    /** Screenshots whose notification is still showing. */
    fun pendingUris(context: Context): List<Uri> = pendingTags(context).map(Uri::parse)

    /** Removes the notifications of screenshots that were deleted, kept or dismissed. */
    fun handled(context: Context, uris: List<Uri>) {
        val tags = uris.map(Uri::toString)
        val manager = NotificationManagerCompat.from(context)
        tags.forEach { manager.cancel(it, SCREENSHOT_NOTIFICATION_ID) }
        refreshSummary(context, removed = tags)
    }

    /**
     * Shows "Borrar todas" while two or more screenshots are pending (unless turned off in settings).
     * Posting and cancelling are asynchronous, so the change that triggered the refresh is applied by hand.
     */
    @SuppressLint("MissingPermission")
    fun refreshSummary(context: Context, added: String? = null, removed: List<String> = emptyList()) {
        val count = (pendingTags(context).toSet() + listOfNotNull(added) - removed.toSet()).size
        val manager = NotificationManagerCompat.from(context)
        if (count < 2 || !Prefs.showDeleteAll(context)) {
            manager.cancel(SUMMARY_NOTIFICATION_ID)
            return
        }

        val deleteAll = deleteIntent(context, SUMMARY_REQUEST_CODE, Intent().putExtra(EXTRA_ALL_PENDING, true))
        val views = RemoteViews(context.packageName, R.layout.notification_delete_all).apply {
            setTextViewText(R.id.deleteAll, context.getString(R.string.delete_all, count))
            setOnClickPendingIntent(R.id.deleteAll, deleteAll)
        }
        val notification = NotificationCompat.Builder(context, SCREENSHOT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_screenshot)
            .setContentTitle(context.getString(R.string.delete_all, count))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            // Re-posted after every new screenshot so it is the newest one and stays on top.
            .setWhen(System.currentTimeMillis())
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        manager.notify(SUMMARY_NOTIFICATION_ID, notification)
    }

    private fun pendingTags(context: Context): List<String> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.id == SCREENSHOT_NOTIFICATION_ID && it.tag != null }
            .map { it.tag }

    /** With all-files access deletion happens straight away; otherwise Android insists on its dialog. */
    private fun deleteIntent(context: Context, requestCode: Int, extras: Intent): PendingIntent =
        if (ScreenshotDeleter.canDeleteSilently()) {
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, NotificationActionReceiver::class.java)
                    .setAction(NotificationActionReceiver.ACTION_DELETE)
                    .putExtras(extras),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, DeleteScreenshotActivity::class.java).putExtras(extras),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

    private fun actionIntent(context: Context, action: String, requestCode: Int, uris: List<Uri>): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris)),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun thumbnail(context: Context, screenshot: Screenshot): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(screenshot.uri, Size(540, 540), null) }.getOrNull()
}
