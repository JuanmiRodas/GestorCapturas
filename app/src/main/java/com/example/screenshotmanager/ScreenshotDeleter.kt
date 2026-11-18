package com.example.screenshotmanager

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Deletes screenshots from the phone and, through [PcloudDeleteWorker], their pCloud copies.
 * Deleting without the system confirmation dialog is only allowed once the user grants
 * "All files access"; without it callers use MediaStore.createDeleteRequest and then [deletedBySystem].
 */
object ScreenshotDeleter {

    fun canDeleteSilently(): Boolean = Environment.isExternalStorageManager()

    /** Returns how many of [uris] were deleted. */
    fun delete(context: Context, uris: List<Uri>): Int {
        val deletedNames = mutableListOf<String>()
        var deleted = 0
        for (uri in uris) {
            val name = nameOf(context, uri)
            if (deleteOne(context, uri)) {
                deleted++
                name?.let(deletedNames::add)
            }
        }
        AppLog.i("Deleted $deleted of ${uris.size} on the phone: ${deletedNames.joinToString()}")
        PcloudDeleteWorker.enqueue(context, deletedNames)
        return deleted
    }

    /** Call after the system dialog deleted the screenshots called [names]. */
    fun deletedBySystem(context: Context, names: List<String>) {
        AppLog.i("Deleted through the system dialog: ${names.joinToString()}")
        PcloudDeleteWorker.enqueue(context, names)
    }

    fun namesOf(context: Context, uris: List<Uri>): List<String> = uris.mapNotNull { nameOf(context, it) }

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
