package com.gone.ai.health.nfc

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Encodes content as a QR code [Bitmap].
 *
 * QR CONTENT CONTRACT
 *
 * The caller passes either an HTTPS emergency link (browser + internet) or a plain-text
 * offline snapshot (compatible QR reader required). A data-URI HTML document is not a
 * reliably interoperable phone-camera QR payload and is deliberately not used.
 *
 * WHY ZXing CORE (NOT zxing-android-embedded)
 *
 * `zxing-android-embedded` drags in camera, permissions, and a bundle of Activities that
 * we don't need — we only need the QR *encoder*, not a scanner. `com.google.zxing:core`
 * is the pure-Java encoder/decoder library (~300 KB, no Android transitive deps).
 *
 * ERROR CORRECTION LEVEL
 *
 * M (15% recovery capacity) is used. L (7%) would make a denser, harder-to-scan code.
 * H (30%) is overkill for a QR that's displayed on-screen and printed fresh. M gives
 * good resilience against physical damage (smudge, partial obstruction) without
 * needlessly enlarging the code.
 *
 * THREAD SAFETY
 *
 * [encode] is pure computation (no shared mutable state). Safe to call from any thread,
 * including Dispatchers.IO in the ViewModel.
 */
object QrCodeGenerator {

    /**
     * Encode [content] as a QR code bitmap.
     *
     * @param content HTTPS emergency link or plain-text snapshot.
     * @param sizePx   Width and height of the output bitmap in pixels. 800 is a good default
     *                 for on-screen display; use 1200+ if the bitmap will be printed.
     * @return         The QR code as a [Bitmap], or null if ZXing cannot encode [content]
     *                 (e.g. content is too large for the chosen correction level).
     */
    fun encode(content: String, sizePx: Int = 800): Bitmap? {
        if (content.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN          to 4     // standard quiet zone for reliable scanning
            )
            val matrix: BitMatrix = MultiFormatWriter()
                .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            toBitmap(matrix)
        } catch (e: WriterException) {
            null
        }
    }

    private fun toBitmap(matrix: BitMatrix): Bitmap {
        val width  = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }
}
