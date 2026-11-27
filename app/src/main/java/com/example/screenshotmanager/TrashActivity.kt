package com.example.screenshotmanager

import android.os.Bundle
import android.provider.MediaStore
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Screenshots in Android's trash: restore them or delete them for good before the 30 days are up. */
class TrashActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var info: TextView
    private lateinit var empty: View
    private lateinit var actions: View
    private lateinit var adapter: ScreenshotAdapter
    private val background = Executors.newSingleThreadExecutor()

    private var awaitingSystemRestore: List<Screenshot> = emptyList()

    private val systemRestore =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) ScreenshotDeleter.restoredBySystem(this, awaitingSystemRestore.map { it.name })
            reload()
        }

    private val systemDelete =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload() }

    private val clearSelectionOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = adapter.clearSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)
        findViewById<View>(R.id.root).padForSystemBars()

        toolbar = findViewById(R.id.toolbar)
        info = findViewById(R.id.trashInfo)
        empty = findViewById(R.id.trashEmpty)
        actions = findViewById(R.id.trashActions)
        // In the trash a tap selects: trashed images can't be opened by other apps.
        adapter = ScreenshotAdapter(contentResolver, onOpen = { adapter.toggleSelection(it) }, onSelectionChanged = ::updateUi)

        val grid = findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@TrashActivity, ScreenshotAdapter.COLUMNS)
            adapter = this@TrashActivity.adapter
        }
        DragSelectHelper(grid, adapter)

        toolbar.setNavigationOnClickListener {
            if (adapter.selected.isNotEmpty()) adapter.clearSelection() else finish()
        }
        toolbar.setOnMenuItemClickListener {
            if (adapter.all.isNotEmpty()) confirmDeleteForever(adapter.all)
            true
        }
        onBackPressedDispatcher.addCallback(this, clearSelectionOnBack)
        findViewById<View>(R.id.restoreButton).setOnClickListener { restore(adapter.selected) }
        findViewById<View>(R.id.deleteForeverButton).setOnClickListener { confirmDeleteForever(adapter.selected) }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        background.execute {
            val trashed = runCatching { ScreenshotStore.query(this, trashed = true) }
                .onFailure { AppLog.e("Trash query failed", it) }
                .getOrDefault(emptyList())
            runOnUiThread { adapter.submit(trashed) }
        }
    }

    private fun updateUi() {
        val all = adapter.all
        val selected = adapter.selected
        toolbar.title = if (selected.isEmpty()) getString(R.string.trash_title) else getString(R.string.n_selected, selected.size)
        toolbar.setNavigationIcon(if (selected.isEmpty()) R.drawable.ic_arrow_back else R.drawable.ic_close)
        clearSelectionOnBack.isEnabled = selected.isNotEmpty()
        actions.isVisible = selected.isNotEmpty()
        empty.isVisible = all.isEmpty()

        info.text = buildString {
            if (all.isNotEmpty()) {
                append(getString(R.string.summary, all.size, Formatter.formatShortFileSize(this@TrashActivity, all.sumOf { it.size })))
                append(". ")
            }
            append(getString(R.string.trash_explanation))
            daysUntilNextExpiry(all)?.let { append(' ').append(getString(R.string.trash_next_expiry, it)) }
            // Without all-files access Android only shows this app the trashed images it owns.
            if (!ScreenshotDeleter.canDeleteSilently()) append("\n\n").append(getString(R.string.trash_needs_all_files))
        }
    }

    private fun daysUntilNextExpiry(screenshots: List<Screenshot>): Long? {
        val next = screenshots.map { it.dateExpiresSeconds }.filter { it > 0 }.minOrNull() ?: return null
        val now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        return maxOf(0, TimeUnit.SECONDS.toDays(next - now))
    }

    private fun restore(screenshots: List<Screenshot>) {
        if (screenshots.isEmpty()) return
        if (ScreenshotDeleter.canDeleteSilently()) {
            background.execute {
                ScreenshotDeleter.restore(this, screenshots)
                reload()
            }
        } else {
            awaitingSystemRestore = screenshots
            val request = MediaStore.createTrashRequest(contentResolver, screenshots.map { it.uri }, false)
            systemRestore.launch(IntentSenderRequest.Builder(request).build())
        }
    }

    private fun confirmDeleteForever(screenshots: List<Screenshot>) {
        if (screenshots.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.trash_confirm_delete_forever, screenshots.size))
            .setPositiveButton(R.string.trash_delete_forever) { _, _ -> deleteForever(screenshots) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteForever(screenshots: List<Screenshot>) {
        if (ScreenshotDeleter.canDeleteSilently()) {
            background.execute {
                ScreenshotDeleter.deleteForever(this, screenshots.map { it.uri })
                reload()
            }
        } else {
            val request = MediaStore.createDeleteRequest(contentResolver, screenshots.map { it.uri })
            systemDelete.launch(IntentSenderRequest.Builder(request).build())
        }
    }
}
