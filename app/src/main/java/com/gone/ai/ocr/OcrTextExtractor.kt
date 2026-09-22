package com.gone.ai.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OcrTextExtractor
 *
 * Wraps ML Kit Text Recognition as a suspend function.
 * Returns cleaned text capped at MAX_CHARS. The cap is only a safety ceiling; how much
 * text reaches the model is decided by the token budget in [AiTextProcessor].
 * Reused by the OCR, screenshot and quiz features.
 */
class OcrTextExtractor {

    companion object {
        private const val TAG = "OcrTextExtractor"
        const val MAX_CHARS   = AiTextProcessor.MAX_EXTRACTED_CHARS
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Run OCR on the image at [uri].
     * Returns Result.success(Pair(cleanedText, wasTruncated)) or Result.failure.
     */
    suspend fun extract(context: Context, uri: Uri): Result<Pair<String, Boolean>> {
        return try {
            val image = InputImage.fromFilePath(context, uri)
            val raw   = recognize(image)
            if (raw.isBlank()) return Result.failure(Exception("No text detected in image."))
            val clean       = OcrTextCleanup.clean(raw)
            val truncated   = clean.length > MAX_CHARS
            val final       = if (truncated) clean.take(MAX_CHARS) else clean
            // Lengths only: the text is often a lab report or prescription.
            Log.i(TAG, "OCR: raw=${raw.length} clean=${clean.length} final=${final.length} truncated=$truncated")
            Result.success(Pair(final, truncated))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed", e)
            Result.failure(e)
        }
    }

    /** Suspend wrapper around ML Kit's Task-based API. */
    private suspend fun recognize(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result.text) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }

    fun close() = recognizer.close()
}
