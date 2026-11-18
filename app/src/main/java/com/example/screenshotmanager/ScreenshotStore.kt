package com.example.screenshotmanager

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

data class Screenshot(val id: Long, val uri: Uri, val name: String, val dateAddedSeconds: Long, val size: Long)

object ScreenshotStore {

    enum class PhotoAccess { FULL, PARTIAL, NONE }

    /**
     * Android 14+ lets people share only "selected photos". The app then can't see new
     * screenshots at all, so that case is reported separately from having no access.
     */
    fun photoAccess(context: Context): PhotoAccess {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.FULL
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
            else -> PhotoAccess.NONE
        }
    }

    fun hasAccess(context: Context): Boolean = photoAccess(context) == PhotoAccess.FULL

    /** All finished screenshots, newest first. */
    fun query(context: Context): List<Screenshot> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
        )
        // Samsung saves to DCIM/Screenshots, Pixel and stock Android to Pictures/Screenshots.
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING} = 0"
        val args = arrayOf("%Screenshots%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        return context.contentResolver.query(collection, projection, selection, args, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            buildList<Screenshot> {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    add(
                        Screenshot(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = cursor.getString(nameColumn).orEmpty(),
                            dateAddedSeconds = cursor.getLong(dateColumn),
                            size = cursor.getLong(sizeColumn),
                        )
                    )
                }
            }
        }.orEmpty()
    }
}
