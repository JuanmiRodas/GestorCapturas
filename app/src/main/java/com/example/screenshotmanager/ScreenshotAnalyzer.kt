package com.example.screenshotmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Proposes a Keep note for a screenshot using Google ML Kit on the device: the text in the image
 * (OCR) plus a description from Gemini Nano where the phone supports it, or image labels otherwise.
 */
object ScreenshotAnalyzer {

    data class Proposal(val title: String, val text: String)

    private const val MAX_SIDE = 2400

    /** Blocking; call it off the main thread. */
    fun analyze(context: Context, uri: Uri): Proposal {
        val bitmap = decode(context, uri) ?: return Proposal("", "")
        val ocr = recognizeText(bitmap)
        val description = (describe(context, bitmap) ?: labels(bitmap))?.let(::translate)

        val title = description?.substringBefore('.')?.trim()?.take(80)
            ?: ocr.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80)
            ?: ""
        val text = listOfNotNull(description, ocr.ifBlank { null }).joinToString("\n\n")
        return Proposal(title, text)
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            // ML Kit can't read hardware bitmaps.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    private fun recognizeText(bitmap: Bitmap): String = runCatching {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS).text.trim()
        } finally {
            recognizer.close()
        }
    }.getOrDefault("")

    /** Gemini Nano image description; null when the phone doesn't support it (yet). */
    private fun describe(context: Context, bitmap: Bitmap): String? {
        val describer = ImageDescription.getClient(ImageDescriberOptions.builder(context).build())
        var downloading = false
        return try {
            when (describer.checkFeatureStatus().get(10, TimeUnit.SECONDS)) {
                FeatureStatus.AVAILABLE ->
                    describer.runInference(ImageDescriptionRequest.builder(bitmap).build())
                        .get(60, TimeUnit.SECONDS).description.trim().ifBlank { null }
                FeatureStatus.DOWNLOADABLE -> {
                    // Fetch the model in the background so it is ready for the next screenshot.
                    downloading = true
                    describer.downloadFeature(object : DownloadCallback {
                        override fun onDownloadStarted(bytesToDownload: Long) {}
                        override fun onDownloadProgress(totalBytesDownloaded: Long) {}
                        override fun onDownloadCompleted() = describer.close()
                        override fun onDownloadFailed(e: GenAiException) = describer.close()
                    })
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        } finally {
            if (!downloading) describer.close()
        }
    }

    private fun labels(bitmap: Bitmap): String? = runCatching {
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        try {
            Tasks.await(labeler.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
                .filter { it.confidence >= 0.7f }
                .take(5)
                .joinToString(", ") { it.text }
                .ifBlank { null }
        } finally {
            labeler.close()
        }
    }.getOrNull()

    /** Both description sources answer in English; translate into the phone's language. */
    private fun translate(text: String): String {
        val target = TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: return text
        if (target == TranslateLanguage.ENGLISH) return text
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(target)
                .build()
        )
        return try {
            Tasks.await(translator.downloadModelIfNeeded(), 60, TimeUnit.SECONDS)
            Tasks.await(translator.translate(text), 30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            text
        } finally {
            translator.close()
        }
    }
}
