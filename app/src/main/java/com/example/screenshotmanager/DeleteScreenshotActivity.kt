package com.example.screenshotmanager

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat

/**
 * Transparent trampoline used by "Borrar" while the app lacks all-files access. Android then
 * requires the user to confirm deleting media the app did not create, so the system dialog is
 * shown here and the notifications are only removed if the user confirms.
 */
class DeleteScreenshotActivity : ComponentActivity() {

    private var uris: List<Uri> = emptyList()
    private var names: List<String> = emptyList()

    private val confirmDelete =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                ScreenshotNotifier.handled(this, uris)
                ScreenshotDeleter.deletedBySystem(this, names)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uris = if (intent.getBooleanExtra(ScreenshotNotifier.EXTRA_ALL_PENDING, false)) {
            ScreenshotNotifier.pendingUris(this)
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, ScreenshotNotifier.EXTRA_URIS, Uri::class.java).orEmpty()
        }
        if (uris.isEmpty()) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            // Names must be read before the files are gone.
            names = ScreenshotDeleter.namesOf(this, uris)
            val request = MediaStore.createDeleteRequest(contentResolver, uris)
            confirmDelete.launch(IntentSenderRequest.Builder(request).build())
        }
    }
}
