package com.example.screenshotmanager

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the app from the GitHub releases of the project, without any store or extra app.
 * All calls block, so run them off the main thread.
 */
object Updater {

    data class Release(val version: String, val notes: String, val apkUrl: String)

    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/JuanmiRodas/GestorCapturas/releases/latest"
    private const val APK_NAME = "GestorCapturas.apk"

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** The latest published release, or null if GitHub can't be reached. */
    fun latestRelease(): Release? = try {
        val json = JSONObject(get(LATEST_RELEASE_URL).bufferedReader().use { it.readText() })
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.optString("name") == APK_NAME }
        apk?.let {
            Release(
                version = json.getString("tag_name").removePrefix("v"),
                notes = json.optString("body").trim(),
                apkUrl = it.getString("browser_download_url"),
            )
        }
    } catch (e: Exception) {
        AppLog.e("Update check failed", e)
        null
    }

    /** Compares dotted versions number by number, so 2.10 is newer than 2.9. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (difference != 0) return difference > 0
        }
        return false
    }

    /** Downloads the release APK into the cache, reporting progress from 0 to 100. */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val file = File(File(context.cacheDir, "updates").apply { mkdirs() }, APK_NAME)
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress((copied * 100 / total).toInt())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        checkIsUpdate(context, file)
        return file
    }

    /** Refuses anything that isn't a newer build of this same app. */
    private fun checkIsUpdate(context: Context, apk: File) {
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IOException("The download is not a valid APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        if (archive.packageName != context.packageName) throw IOException("The download is a different app: ${archive.packageName}")
        if (archive.longVersionCode <= installed.longVersionCode) throw IOException("The download is not newer than the installed version")
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.github+json")
        if (responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP $responseCode")
        inputStream
    }
}
