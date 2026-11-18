package com.example.screenshotmanager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

/**
 * Minimal client for the pCloud HTTP API (https://docs.pcloud.com). Only the auth token is
 * stored, never the password. All calls block, so run them off the main thread.
 */
object Pcloud {

    enum class Outcome { DELETED, NOT_FOUND, FAILED, NOT_CONNECTED, SESSION_EXPIRED }

    /** An API call that pCloud answered with an error [code]. */
    private class PcloudError(val code: Int, message: String) : IOException("$message ($code)")

    sealed class LoginResult {
        object Success : LoginResult()

        /** Two-step verification is on: a code must be sent with [submitCode]. */
        data class NeedsCode(val email: String, val host: String, val token: String, val hasDevices: Boolean) : LoginResult()

        /**
         * pCloud's check for logins from a new device: it emails a code that has to be sent back
         * with [submitEmailCode]. The password is only kept in memory for that second attempt.
         */
        data class NeedsEmailCode(val email: String, val password: String, val host: String) : LoginResult()

        data class Failed(val message: String) : LoginResult()
    }

    // US and EU data regions; an account only works on one of them.
    private val HOSTS = listOf("api.pcloud.com", "eapi.pcloud.com")
    private const val ERROR_FILE_NOT_FOUND = 2009
    private const val ERROR_TWO_STEP_CODE_REQUIRED = 2297
    private const val ERROR_EMAIL_CODE_REQUIRED = 1022

    // "Log in required", "Log in failed", invalid or expired tokens: the saved session no longer works.
    private val SESSION_ERRORS = setOf(1000, 2000, 2094, 2095)
    private const val DEVICE_NAME = "Gestor de capturas (Android)"

    fun isConnected(context: Context) = prefs(context).getString("auth", null) != null

    /** pCloud rejected the saved session (web logins in particular expire); the user must log in again. */
    fun isExpired(context: Context) = prefs(context).getBoolean("expired", false)

    /** Names whose pCloud copies couldn't be deleted while the session was expired. */
    fun pendingDeletes(context: Context): Set<String> = prefs(context).getStringSet("pending_deletes", null).orEmpty()

    fun addPendingDelete(context: Context, name: String) {
        prefs(context).edit().putStringSet("pending_deletes", pendingDeletes(context) + name).apply()
    }

    /** Checks the saved session against pCloud, for the diagnostics report. Blocking. */
    fun checkSession(context: Context): String {
        val prefs = prefs(context)
        val auth = prefs.getString("auth", null) ?: return "not connected"
        val host = prefs.getString("host", null) ?: return "no host"
        return try {
            val json = call(host, "userinfo", mapOf("auth" to auth))
            when (val code = json.optInt("result")) {
                0 -> "OK on $host (${json.optString("email")})"
                in SESSION_ERRORS -> {
                    markExpired(context)
                    "EXPIRED: ${errorOf(json)}"
                }
                else -> "error ${errorOf(json)} ($code)"
            }
        } catch (e: IOException) {
            "network error: $e"
        }
    }

    private fun markExpired(context: Context) {
        if (!isExpired(context)) AppLog.e("pCloud session expired")
        prefs(context).edit().putBoolean("expired", true).apply()
    }

    fun email(context: Context): String? = prefs(context).getString("email", null)

    /** Logs in the way pCloud's website does (method "login"), including its email and two-step checks. */
    fun login(context: Context, email: String, password: String): LoginResult {
        var error = ""
        for (host in HOSTS) {
            val result = attemptLogin(context, host, email, password, emailCode = null)
            if (result !is LoginResult.Failed) return result
            error = result.message
        }
        return LoginResult.Failed(error)
    }

    fun submitEmailCode(context: Context, pending: LoginResult.NeedsEmailCode, code: String): LoginResult =
        attemptLogin(context, pending.host, pending.email, pending.password, emailCode = code.trim())

    /** Emails a new login code. Returns null on success. */
    fun resendEmailCode(pending: LoginResult.NeedsEmailCode): String? = sendEmailCode(pending.host, pending.email)

    private fun attemptLogin(context: Context, host: String, email: String, password: String, emailCode: String?): LoginResult {
        val params = deviceParams(context) + mapOf(
            "username" to email,
            "password" to password,
            "language" to Locale.getDefault().language,
        ) + (if (emailCode != null) mapOf("emailtfacode" to emailCode) else emptyMap())
        val json = try {
            call(host, "login", params)
        } catch (e: IOException) {
            return LoginResult.Failed(e.message ?: e.toString())
        }
        return when (json.optInt("result")) {
            0 -> {
                saveLogin(context, host, json.getString("auth"), email)
                LoginResult.Success
            }
            ERROR_TWO_STEP_CODE_REQUIRED ->
                LoginResult.NeedsCode(email, host, json.getString("token"), json.optBoolean("hasdevices"))
            ERROR_EMAIL_CODE_REQUIRED -> {
                if (emailCode != null) return LoginResult.Failed(errorOf(json))
                sendEmailCode(host, email)?.let { return LoginResult.Failed(it) }
                LoginResult.NeedsEmailCode(email, password, host)
            }
            else -> LoginResult.Failed(errorOf(json))
        }
    }

    private fun sendEmailCode(host: String, email: String): String? = try {
        val json = call(host, "user/sendemailtfamail", mapOf("email" to email, "language" to Locale.getDefault().language))
        if (json.optInt("result") == 0) null else errorOf(json)
    } catch (e: IOException) {
        e.message ?: e.toString()
    }

    private fun errorOf(json: JSONObject) = "${json.optString("error")} (${json.optInt("result")})"

    /**
     * Asks pCloud to send the two-step code, either as a notification to devices already logged
     * in to pCloud (such as the pCloud app on this phone) or by SMS. Returns null on success.
     */
    fun sendCode(pending: LoginResult.NeedsCode, toApp: Boolean): String? = try {
        val method = if (toApp) "tfa_sendcodeviasysnotification" else "tfa_sendcodeviasms"
        val json = call(pending.host, method, mapOf("token" to pending.token))
        if (json.optInt("result") == 0) null else json.optString("error", "Error ${json.optInt("result")}")
    } catch (e: IOException) {
        e.message ?: e.toString()
    }

    /** Finishes a two-step login. This device is marked as trusted so pCloud stops asking. Returns null on success. */
    fun submitCode(context: Context, pending: LoginResult.NeedsCode, code: String): String? {
        val trimmed = code.replace(" ", "")
        // Six digits come from SMS, the pCloud app or an authenticator; anything else is a recovery code.
        val method = if (trimmed.length == 6 && trimmed.all(Char::isDigit)) "tfa_login" else "tfa_loginwithrecoverycode"
        return try {
            val json = call(
                pending.host,
                method,
                deviceParams(context) + mapOf("token" to pending.token, "code" to trimmed, "trustdevice" to "1", "getauth" to "1"),
            )
            if (json.optInt("result") == 0) {
                saveLogin(context, pending.host, json.getString("auth"), pending.email)
                null
            } else {
                json.optString("error", "Error ${json.optInt("result")}")
            }
        } catch (e: IOException) {
            e.message ?: e.toString()
        }
    }

    private fun saveLogin(context: Context, host: String, auth: String, email: String?) {
        val pending = pendingDeletes(context).toList()
        prefs(context).edit()
            .putString("auth", auth)
            .putString("host", host)
            .putString("email", email)
            .remove("folder")
            .remove("expired")
            .remove("pending_deletes")
            .apply()
        AppLog.i("pCloud logged in on $host; retrying ${pending.size} pending deletes")
        // Deletes that failed while the session was expired.
        PcloudDeleteWorker.enqueue(context, pending)
    }

    /**
     * Adopts a session token taken from pCloud's own login page (see [PcloudWebLoginActivity]).
     * Returns true if pCloud accepts it on either data region.
     */
    fun loginWithToken(context: Context, token: String): Boolean {
        for (host in HOSTS) {
            val json = runCatching { call(host, "userinfo", mapOf("auth" to token)) }.getOrNull() ?: continue
            if (json.optInt("result") == 0) {
                saveLogin(context, host, token, json.optString("email").ifBlank { null })
                return true
            }
        }
        return false
    }

    /** Identifies this app as a device in pCloud, which "trust this device" needs. */
    private fun deviceParams(context: Context): Map<String, String> {
        val prefs = prefs(context)
        val id = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
        return mapOf("device" to DEVICE_NAME, "deviceid" to id)
    }

    fun logout(context: Context) {
        val prefs = prefs(context)
        val auth = prefs.getString("auth", null)
        val host = prefs.getString("host", null)
        if (auth != null && host != null) runCatching { call(host, "logout", mapOf("auth" to auth)) }
        prefs.edit().remove("auth").remove("host").remove("email").remove("folder").remove("expired").apply()
    }

    /**
     * Moves the file called [name] to pCloud's trash. Screenshot names contain the date and time,
     * so the name alone identifies the uploaded copy wherever the auto-upload put it.
     */
    fun deleteByName(context: Context, name: String): Outcome {
        val prefs = prefs(context)
        val auth = mapOf("auth" to (prefs.getString("auth", null) ?: return Outcome.NOT_CONNECTED))
        val host = prefs.getString("host", null) ?: return Outcome.NOT_CONNECTED
        return try {
            // Look in the folder where the last screenshot was found first, then everywhere.
            val lastFolder = prefs.getLong("folder", -1)
            val file = (if (lastFolder >= 0) runCatching { find(host, auth, lastFolder, false, name) }.getOrNull() else null)
                ?: find(host, auth, 0, true, name)
                ?: return Outcome.NOT_FOUND

            val result = call(host, "deletefile", auth + ("fileid" to file.getLong("fileid").toString()))
            when (val code = result.optInt("result")) {
                0 -> {
                    prefs.edit().putLong("folder", file.getLong("parentfolderid")).apply()
                    Outcome.DELETED
                }
                ERROR_FILE_NOT_FOUND -> Outcome.NOT_FOUND
                in SESSION_ERRORS -> {
                    markExpired(context)
                    Outcome.SESSION_EXPIRED
                }
                else -> {
                    AppLog.e("pCloud deletefile $name: ${errorOf(result)}")
                    Outcome.FAILED
                }
            }
        } catch (e: PcloudError) {
            if (e.code in SESSION_ERRORS) {
                markExpired(context)
                Outcome.SESSION_EXPIRED
            } else {
                AppLog.e("pCloud search for $name", e)
                Outcome.FAILED
            }
        } catch (e: Exception) {
            AppLog.e("pCloud delete $name", e)
            Outcome.FAILED
        }
    }

    private fun find(host: String, auth: Map<String, String>, folderId: Long, recursive: Boolean, name: String): JSONObject? {
        val params = auth.toMutableMap()
        params["folderid"] = folderId.toString()
        params["noshares"] = "1"
        if (recursive) params["recursive"] = "1"
        val json = call(host, "listfolder", params)
        if (json.optInt("result") != 0) throw PcloudError(json.optInt("result"), json.optString("error"))
        return search(json.getJSONObject("metadata"), name)
    }

    private fun search(folder: JSONObject, name: String): JSONObject? {
        val contents = folder.optJSONArray("contents") ?: return null
        for (i in 0 until contents.length()) {
            val item = contents.getJSONObject(i)
            val match = if (item.optBoolean("isfolder")) search(item, name) else item.takeIf { it.optString("name") == name }
            if (match != null) return match
        }
        return null
    }

    private fun call(host: String, method: String, params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val connection = URL("https://$host/$method").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray()) }
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("pcloud", Context.MODE_PRIVATE)
}
