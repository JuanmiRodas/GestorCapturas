package com.example.screenshotmanager

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Moves screenshots to Android's trash (kept 30 days, then deleted by the system), restores them
 * and deletes them for good. Their pCloud copies follow: trashed with [PcloudDeleteWorker] and
 * restored from pCloud's trash. Doing this without a system confirmation dialog needs "All files
 * access"; without it callers use MediaStore.createTrashRequest/createDeleteRequest and then
 * [trashedBySystem] or [restoredBySystem].
 */
object ScreenshotDeleter {

    fun canDeleteSilently(): Boolean = Environment.isExternalStorageManager()

    /** Returns how many of [uris] were moved to the trash. */
    fun moveToTrash(context: Context, uris: List<Uri>): Int {
        val trashedNames = mutableListOf<String>()
        var trashed = 0
        for (uri in uris) {
            val name = nameOf(context, uri)
            if (setTrashed(context, uri, true)) {
                trashed++
                name?.let(trashedNames::add)
            }
        }
        AppLog.i("Moved $trashed of ${uris.size} to the trash: ${trashedNames.joinToString()}")
        PcloudDeleteWorker.enqueue(context, trashedNames)
        return trashed
    }

    /** Call after the system dialog moved the screenshots called [names] to the trash. */
    fun trashedBySystem(context: Context, names: List<String>) {
        AppLog.i("Moved to the trash through the system dialog: ${names.joinToString()}")
        PcloudDeleteWorker.enqueue(context, names)
    }

    /** Takes [screenshots] out of the trash. Returns how many were restored. */
    fun restore(context: Context, screenshots: List<Screenshot>): Int {
        val restored = screenshots.filter { setTrashed(context, it.uri, false) }
        restoredBySystem(context, restored.map { it.name })
        return restored.size
    }

    /** Brings the pCloud copies back too, and stops deletions of them that are still waiting. */
    fun restoredBySystem(context: Context, names: List<String>) {
        AppLog.i("Restored from the trash: ${names.joinToString()}")
        names.forEach { PcloudDeleteWorker.cancel(context, it) }
        Thread { names.forEach { Pcloud.restoreByName(context, it) } }.start()
    }

    /** Deletes for good, e.g. from the trash. Returns how many were deleted. */
    fun deleteForever(context: Context, uris: List<Uri>): Int {
        val deleted = uris.count { deleteOne(context, it) }
        AppLog.i("Deleted for good $deleted of ${uris.size}")
        return deleted
    }

    fun namesOf(context: Context, uris: List<Uri>): List<String> = uris.mapNotNull { nameOf(context, it) }

    private fun setTrashed(context: Context, uri: Uri, trashed: Boolean): Boolean = try {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }
        context.contentResolver.update(uri, values, null, null) > 0
    } catch (e: Exception) {
        AppLog.e("Could not ${if (trashed) "trash" else "restore"} $uri", e)
        false
    }

    private fun nameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun deleteOne(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        if (runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)) return true

        // Fallback: remove the file itself and let the media scanner drop its stale entry.
        val path = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: return false
        val deleted = File(path).delete()
        if (deleted) MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        return deleted
    }
}
