package com.gone.ai.pdf

import android.content.Context
import androidx.core.graphics.createBitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the text of a PDF, page by page, whatever kind of PDF it is.
 *
 * For each page, in order of cost:
 *  1. Android's own text layer (Android 15 and later), which understands the embedded fonts
 *     that Word, Google Docs and browsers use.
 *  2. On older Android, the legacy content-stream parser, once for the whole file, kept only
 *     if [TextQuality] says the result is readable.
 *  3. Otherwise the page is drawn to an image and read with the bundled ML Kit recogniser,
 *     which is what makes scanned PDFs work at all.
 *
 * Reading stops once [maxChars] characters are collected, so a long document costs only the
 * pages that will be used.
 */
class PdfTextSource(private val context: Context) {

    data class Progress(val pagesRead: Int, val totalPages: Int, val readingImages: Boolean)

    data class Extracted(
        val text: String,
        val pagesRead: Int,
        val totalPages: Int,
        /** Pages that had to be read as images. */
        val imagePages: Int,
        /** True when reading stopped at [maxChars] before the last page. */
        val stoppedEarly: Boolean
    )

    /** Why a PDF could not be read, in words for the screen. */
    class PdfReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun extract(
        uri: Uri,
        maxChars: Int,
        onProgress: (Progress) -> Unit = {}
    ): Result<Extracted> = withContext(Dispatchers.IO) {
        try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw PdfReadException("The file could not be opened.")
            descriptor.use { read(it, uri, maxChars, onProgress) }.let { Result.success(it) }
        } catch (e: PdfReadException) {
            Result.failure(e)
        } catch (e: SecurityException) {
            Result.failure(PdfReadException("This PDF is password-protected. Open it in a PDF app, save an unlocked copy, and try that.", e))
        } catch (e: IOException) {
            Result.failure(PdfReadException("This file is not a readable PDF, or it is damaged.", e))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "PDF read failed", e)
            Result.failure(PdfReadException("The PDF could not be read: ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    private suspend fun read(
        descriptor: ParcelFileDescriptor,
        uri: Uri,
        maxChars: Int,
        onProgress: (Progress) -> Unit
    ): Extracted {
        val renderer = PdfRenderer(descriptor)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val total = renderer.pageCount
            if (total == 0) throw PdfReadException("This PDF has no pages.")
            onProgress(Progress(0, total, readingImages = false))

            // Before Android 15 there is no text layer API; try the legacy parser once.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val legacy = PdfTextExtractor.parseContentStreams(context, uri)
                if (legacy != null && TextQuality.isReadable(legacy)) {
                    onProgress(Progress(total, total, readingImages = false))
                    return Extracted(legacy.take(maxChars), total, total, imagePages = 0, stoppedEarly = legacy.length > maxChars)
                }
            }

            val text = StringBuilder()
            var imagePages = 0
            var pagesRead = 0
            for (index in 0 until total) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val pageText = renderer.openPage(index).use { page ->
                    val layer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        page.textContents.joinToString("\n") { it.text }.trim()
                    } else ""
                    if (TextQuality.isReadable(layer)) {
                        layer
                    } else {
                        onProgress(Progress(pagesRead, total, readingImages = true))
                        imagePages++
                        readPageImage(page, recognizer)
                    }
                }
                pagesRead++
                if (pageText.isNotBlank()) {
                    if (text.isNotEmpty()) text.append("\n\n")
                    text.append(pageText)
                }
                onProgress(Progress(pagesRead, total, readingImages = false))
                if (text.length >= maxChars) break
            }

            val clean = text.toString().trim()
            if (clean.isEmpty()) {
                throw PdfReadException("No readable text was found. The pages may be blank, or the scan too faint to read.")
            }
            return Extracted(
                text = clean.take(maxChars),
                pagesRead = pagesRead,
                totalPages = total,
                imagePages = imagePages,
                stoppedEarly = pagesRead < total || clean.length > maxChars
            )
        } finally {
            renderer.close()
            recognizer.close()
        }
    }

    /** Draw [page] at a size ML Kit reads well and recognise its text. */
    private suspend fun readPageImage(page: PdfRenderer.Page, recognizer: com.google.mlkit.vision.text.TextRecognizer): String {
        // PDF units are points (1/72 inch). About 2x gives ~144 dpi, enough for body text;
        // the longer side is capped so a poster-sized page cannot exhaust memory.
        val scale = minOf(2f, MAX_RENDER_SIDE.toFloat() / maxOf(page.width, page.height))
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        // Pages render onto transparency; OCR needs dark text on a light page.
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return suspendCancellableCoroutine { cont ->
            // Recycled when ML Kit reports back, not in a finally block: a read cancelled
            // mid-page may still have recognition running on these pixels.
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    bitmap.recycle()
                    cont.resume(result.text.trim())
                }
                .addOnFailureListener { e ->
                    bitmap.recycle()
                    cont.resumeWithException(e)
                }
        }
    }

    companion object {
        private const val TAG = "PdfTextSource"
        private const val MAX_RENDER_SIDE = 2_400
    }
}
