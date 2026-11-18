package com.example.screenshotmanager

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log kept in the app's private storage, so problems that happen in the background
 * (often while the app is closed) can be shared from Ajustes → Diagnóstico.
 */
object AppLog {

    private const val MAX_BYTES = 512 * 1024
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "log.txt")
    }

    @Synchronized
    fun i(message: String) = write("I", message)

    @Synchronized
    fun e(message: String, error: Throwable? = null) =
        write("E", if (error == null) message else "$message: ${error.stackTraceToString().take(4000)}")

    @Synchronized
    fun read(): String = file?.takeIf { it.exists() }?.readText().orEmpty()

    @Synchronized
    fun clear() {
        file?.delete()
    }

    private fun write(level: String, message: String) {
        val log = file ?: return
        runCatching {
            // Keep the newest half when the log gets too big.
            if (log.length() > MAX_BYTES) log.writeText(log.readText().takeLast(MAX_BYTES / 2))
            log.appendText("${format.format(Date())} $level $message\n")
        }
    }
}
