package com.example.screenshotmanager

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var summary: TextView
    private lateinit var allowDeleteCard: View
    private lateinit var batteryCard: View
    private lateinit var olderChip: Chip
    private lateinit var largeChip: Chip
    private lateinit var deleteButton: MaterialButton
    private lateinit var adapter: ScreenshotAdapter
    private val background = Executors.newSingleThreadExecutor()

    private lateinit var photoAccessCard: View
    private lateinit var photoAccessText: TextView
    private var askedForPhotos = false

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            AppLog.i("Permission answer: photo access ${ScreenshotStore.photoAccess(this)}")
            updatePhotoAccessCard()
            if (ScreenshotStore.hasAccess(this)) {
                startWatcherIfEnabled()
                reload()
            } else {
                summary.setText(R.string.no_permission)
            }
        }

    private var namesAwaitingSystemDelete: List<String> = emptyList()

    private val confirmDelete =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) ScreenshotDeleter.deletedBySystem(this, namesAwaitingSystemDelete)
            reload()
        }

    private val clearSelectionOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = adapter.clearSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).padForSystemBars()

        toolbar = findViewById(R.id.toolbar)
        summary = findViewById(R.id.summary)
        allowDeleteCard = findViewById(R.id.allowDeleteCard)
        batteryCard = findViewById(R.id.batteryCard)
        olderChip = findViewById(R.id.olderChip)
        largeChip = findViewById(R.id.largeChip)
        deleteButton = findViewById(R.id.deleteButton)
        adapter = ScreenshotAdapter(contentResolver, onOpen = ::open, onSelectionChanged = ::updateSelectionUi)

        val grid = findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, ScreenshotAdapter.COLUMNS)
            adapter = this@MainActivity.adapter
        }
        DragSelectHelper(grid, adapter)

        toolbar.setOnMenuItemClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        toolbar.setNavigationOnClickListener { adapter.clearSelection() }
        onBackPressedDispatcher.addCallback(this, clearSelectionOnBack)

        findViewById<Chip>(R.id.selectAllChip).setOnClickListener { adapter.toggleSelectAll() }
        olderChip.setOnClickListener {
            val now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
            val cutoff = now - TimeUnit.DAYS.toSeconds(Prefs.oldDays(this).toLong())
            adapter.selectWhere { it.dateAddedSeconds < cutoff }
        }
        largeChip.setOnClickListener {
            val limit = Prefs.largeMb(this) * 1024L * 1024L
            adapter.selectWhere { it.size > limit }
        }
        photoAccessCard = findViewById(R.id.photoAccessCard)
        photoAccessText = findViewById(R.id.photoAccessText)
        findViewById<View>(R.id.photoAccessButton).setOnClickListener {
            // Asking again offers "Allow all"; if Android no longer shows the dialog, go to the app's settings.
            if (!askedForPhotos) {
                askedForPhotos = true
                requestPermissions.launch(requiredPermissions())
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        findViewById<View>(R.id.batteryButton).setOnClickListener { Battery.openSettings(this) }
        findViewById<View>(R.id.allowDeleteButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        deleteButton.setOnClickListener { delete(adapter.selected) }

        if (!ScreenshotStore.hasAccess(this)) {
            askedForPhotos = true
            requestPermissions.launch(requiredPermissions())
        }
    }

    override fun onResume() {
        super.onResume()
        allowDeleteCard.isVisible = !ScreenshotDeleter.canDeleteSilently()
        batteryCard.isVisible = Prefs.notificationsEnabled(this) && !Battery.isUnrestricted(this)
        olderChip.text = getString(R.string.older_than_days, Prefs.oldDays(this))
        largeChip.text = getString(R.string.larger_than_mb, Prefs.largeMb(this))
        updatePhotoAccessCard()
        if (ScreenshotStore.hasAccess(this)) {
            // Re-register on every visit, in case the system dropped the job (e.g. unused-app hibernation).
            startWatcherIfEnabled()
            reload()
        }
    }

    private fun updatePhotoAccessCard() {
        val access = ScreenshotStore.photoAccess(this)
        photoAccessCard.isVisible = access != ScreenshotStore.PhotoAccess.FULL
        photoAccessText.setText(
            if (access == ScreenshotStore.PhotoAccess.PARTIAL) R.string.photo_access_partial else R.string.photo_access_none
        )
    }

    private fun startWatcherIfEnabled() {
        if (Prefs.notificationsEnabled(this)) ScreenshotWatcher.start(this)
    }

    private fun reload() {
        background.execute {
            val screenshots = ScreenshotStore.query(this)
            runOnUiThread { adapter.submit(screenshots) }
        }
    }

    private fun updateSelectionUi() {
        val all = adapter.all
        val selected = adapter.selected
        val selecting = selected.isNotEmpty()

        summary.text = getString(
            R.string.summary,
            all.size,
            Formatter.formatShortFileSize(this, all.sumOf { it.size }),
        )
        toolbar.title = if (selecting) getString(R.string.n_selected, selected.size) else getString(R.string.app_name)
        toolbar.navigationIcon = if (selecting) getDrawable(R.drawable.ic_close) else null
        clearSelectionOnBack.isEnabled = selecting

        deleteButton.text = getString(
            R.string.delete_selected,
            selected.size,
            Formatter.formatShortFileSize(this, selected.sumOf { it.size }),
        )
        deleteButton.isVisible = selecting
    }

    private fun open(screenshot: Screenshot) {
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(screenshot.uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun delete(screenshots: List<Screenshot>) {
        if (screenshots.isEmpty()) return
        if (!ScreenshotDeleter.canDeleteSilently()) {
            // A single system dialog confirms the whole batch.
            namesAwaitingSystemDelete = screenshots.map { it.name }
            val request = MediaStore.createDeleteRequest(contentResolver, screenshots.map { it.uri })
            confirmDelete.launch(IntentSenderRequest.Builder(request).build())
            return
        }
        val deleteNow = {
            background.execute {
                ScreenshotDeleter.delete(this, screenshots.map { it.uri })
                reload()
            }
        }
        if (Prefs.confirmInApp(this)) {
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.confirm_delete, screenshots.size))
                .setPositiveButton(R.string.delete) { _, _ -> deleteNow() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            deleteNow()
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Declaring "selected photos" support makes Android report that choice instead of hiding it.
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
}
