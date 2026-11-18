package com.example.screenshotmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    val updates = UpdatePrompt(this)

    override fun onResume() {
        super.onResume()
        updates.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.root).padForSystemBars()
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val context = requireContext()

            preference(Prefs.KEY_NOTIFY).setOnPreferenceChangeListener { _, enabled ->
                if (enabled == true) {
                    ScreenshotWatcher.start(context)
                } else {
                    ScreenshotWatcher.stop(context)
                }
                true
            }
            preference(Prefs.KEY_SHOW_DELETE_ALL).setOnPreferenceChangeListener { _, _ ->
                // The new value is saved after this listener returns.
                listView.post { ScreenshotNotifier.refreshSummary(context) }
                true
            }
            preference(Prefs.KEY_ALL_FILES).setOnPreferenceClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                true
            }
            preference(Prefs.KEY_PCLOUD_ACCOUNT).setOnPreferenceClickListener {
                if (Pcloud.isConnected(context) && !Pcloud.isExpired(context)) confirmPcloudLogout() else choosePcloudLogin()
                true
            }
            preference(Prefs.KEY_BATTERY).setOnPreferenceClickListener {
                Battery.openSettings(requireActivity())
                true
            }
            preference(Prefs.KEY_UPDATES).summary = getString(R.string.pref_updates_summary, Updater.currentVersion(context))
            preference(Prefs.KEY_UPDATES).setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).updates.check(manual = true)
                true
            }
            preference(Prefs.KEY_DIAGNOSTICS).setOnPreferenceClickListener {
                startActivity(Intent(context, DiagnosticsActivity::class.java))
                true
            }
            preference(Prefs.KEY_SYSTEM_NOTIFICATIONS).setOnPreferenceClickListener {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
                true
            }
        }

        override fun onResume() {
            super.onResume()
            preference(Prefs.KEY_ALL_FILES).setSummary(
                if (ScreenshotDeleter.canDeleteSilently()) R.string.pref_all_files_on else R.string.pref_all_files_off
            )
            updatePcloudSummary()
            preference(Prefs.KEY_BATTERY).setSummary(
                if (Battery.isUnrestricted(requireContext())) R.string.pref_battery_on else R.string.pref_battery_off
            )
        }

        private fun updatePcloudSummary() {
            val context = requireContext()
            preference(Prefs.KEY_PCLOUD_ACCOUNT).summary = when {
                !Pcloud.isConnected(context) -> getString(R.string.pref_pcloud_disconnected)
                Pcloud.isExpired(context) -> getString(R.string.pref_pcloud_expired)
                Pcloud.email(context) != null -> getString(R.string.pref_pcloud_connected, Pcloud.email(context))
                else -> getString(R.string.pref_pcloud_connected_no_email)
            }
        }

        private fun choosePcloudLogin() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pcloud_login_title)
                .setItems(R.array.pcloud_login_methods) { _, which ->
                    if (which == 0) startActivity(Intent(requireContext(), PcloudWebLoginActivity::class.java)) else showPcloudLogin()
                }
                .show()
        }

        private fun showPcloudLogin() {
            val view = layoutInflater.inflate(R.layout.dialog_pcloud_login, null)
            val email = view.findViewById<TextInputEditText>(R.id.pcloudEmail)
            val password = view.findViewById<TextInputEditText>(R.id.pcloudPassword)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pcloud_login_title)
                .setView(view)
                .setPositiveButton(R.string.pcloud_login) { _, _ ->
                    val context = requireContext().applicationContext
                    thread {
                        val result = Pcloud.login(context, email.text.toString().trim(), password.text.toString())
                        activity?.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            handleLoginResult(result)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun handleLoginResult(result: Pcloud.LoginResult) {
            when (result) {
                is Pcloud.LoginResult.Success -> loginFinished(null)
                is Pcloud.LoginResult.NeedsCode -> showPcloudCode(result)
                is Pcloud.LoginResult.NeedsEmailCode -> showPcloudEmailCode(result)
                is Pcloud.LoginResult.Failed -> loginFinished(result.message)
            }
        }

        /** pCloud's "verify login attempt" step: a code sent by email for logins from a new device. */
        private fun showPcloudEmailCode(pending: Pcloud.LoginResult.NeedsEmailCode) {
            val context = requireContext().applicationContext
            val view = layoutInflater.inflate(R.layout.dialog_pcloud_email_code, null)
            view.findViewById<TextView>(R.id.emailCodeExplanation).text = getString(R.string.pcloud_email_code_explanation, pending.email)
            val code = view.findViewById<TextInputEditText>(R.id.pcloudEmailCode)
            view.findViewById<View>(R.id.resendEmailCode).setOnClickListener {
                thread {
                    val error = Pcloud.resendEmailCode(pending)
                    activity?.runOnUiThread {
                        val message = if (error == null) getString(R.string.pcloud_email_code_resent) else getString(R.string.pcloud_code_send_failed, error)
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pcloud_email_code_title)
                .setView(view)
                .setPositiveButton(R.string.pcloud_code_verify) { _, _ ->
                    thread {
                        val result = Pcloud.submitEmailCode(context, pending, code.text.toString())
                        activity?.runOnUiThread { if (isAdded) handleLoginResult(result) }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** Second step for accounts with two-step verification. */
        private fun showPcloudCode(pending: Pcloud.LoginResult.NeedsCode) {
            val context = requireContext().applicationContext
            val view = layoutInflater.inflate(R.layout.dialog_pcloud_code, null)
            val code = view.findViewById<TextInputEditText>(R.id.pcloudCode)
            val sendToApp = view.findViewById<View>(R.id.sendCodeToApp)
            val sendSms = view.findViewById<View>(R.id.sendCodeSms)

            fun send(toApp: Boolean) = thread {
                val error = Pcloud.sendCode(pending, toApp)
                activity?.runOnUiThread {
                    val message = when {
                        error != null -> getString(R.string.pcloud_code_send_failed, error)
                        toApp -> getString(R.string.pcloud_code_sent_app)
                        else -> getString(R.string.pcloud_code_sent_sms)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
            sendToApp.visibility = if (pending.hasDevices) View.VISIBLE else View.GONE
            sendToApp.setOnClickListener { send(toApp = true) }
            sendSms.setOnClickListener { send(toApp = false) }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pcloud_code_title)
                .setView(view)
                .setPositiveButton(R.string.pcloud_code_verify) { _, _ ->
                    thread {
                        val error = Pcloud.submitCode(context, pending, code.text.toString())
                        activity?.runOnUiThread { if (isAdded) loginFinished(error) }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun loginFinished(error: String?) {
            updatePcloudSummary()
            val message = if (error == null) getString(R.string.pcloud_login_ok) else getString(R.string.pcloud_login_failed, error)
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }

        private fun confirmPcloudLogout() {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.pcloud_logout_confirm)
                .setPositiveButton(R.string.pcloud_logout) { _, _ ->
                    val context = requireContext().applicationContext
                    thread {
                        Pcloud.logout(context)
                        activity?.runOnUiThread { if (isAdded) updatePcloudSummary() }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun preference(key: String): Preference = findPreference(key)!!
    }
}
