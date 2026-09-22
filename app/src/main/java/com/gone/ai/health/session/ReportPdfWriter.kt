package com.gone.ai.health.session

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.gone.ai.health.session.ReportPdfContent.Tone
import com.gone.ai.health.session.ReportPdfLayout.Style
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Draws a laid-out report into a PDF file with Android's [PdfDocument].
 *
 * All placement is decided by [ReportPdfLayout]; this class only turns its items into ink.
 * Colours are chosen to print legibly in black and white as well.
 */
class ReportPdfWriter(private val timeZone: TimeZone = TimeZone.getDefault()) {

    private val paints = Style.entries.associateWith { style ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = style.size
            typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            color = INK
        }
    }

    /** The text width function the layout needs, backed by the same paints that draw. */
    val measure: (String, Style) -> Float = { text, style -> paints.getValue(style).measureText(text) }

    /** Writes [content] to [target], replacing it only once the new file is complete. */
    fun write(content: ReportPdfContent, target: File) {
        val pages = ReportPdfLayout.layout(content, measure)
        val document = PdfDocument()
        try {
            for (page in pages) {
                val info = PdfDocument.PageInfo.Builder(
                    ReportPdfLayout.PAGE_WIDTH.toInt(), ReportPdfLayout.PAGE_HEIGHT.toInt(), page.number
                ).create()
                val pdfPage = document.startPage(info)
                page.items.forEach { draw(pdfPage.canvas, it) }
                document.finishPage(pdfPage)
            }
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, target.name + ".part")
            partial.outputStream().use { document.writeTo(it) }
            if (!partial.renameTo(target)) {
                target.delete()
                check(partial.renameTo(target)) { "Could not save the PDF" }
            }
        } finally {
            document.close()
        }
    }

    private fun draw(canvas: Canvas, item: ReportPdfLayout.Item) {
        when (item) {
            is ReportPdfLayout.Text -> {
                val paint = paints.getValue(item.style)
                val color = item.tone?.let { toneInk(it) } ?: if (item.style == Style.SMALL || item.style == Style.SUBTITLE) MUTED else INK
                paint.color = color
                canvas.drawText(item.text, item.x, item.y, paint)
                paint.color = INK
            }
            is ReportPdfLayout.Box -> {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = toneFill(item.tone) }
                val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = toneInk(item.tone); style = Paint.Style.STROKE; strokeWidth = 0.8f
                }
                val rect = RectF(item.x, item.y, item.x + item.width, item.y + item.height)
                canvas.drawRoundRect(rect, 6f, 6f, fill)
                canvas.drawRoundRect(rect, 6f, 6f, edge)
            }
            is ReportPdfLayout.Chart -> drawChart(canvas, item)
        }
    }

    private fun drawChart(canvas: Canvas, chart: ReportPdfLayout.Chart) {
        val series = chart.series
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = MUTED }
        val labelWidth = 36f
        val left = chart.x + labelWidth
        val right = chart.x + chart.width
        val top = chart.y + 4f
        val bottom = chart.y + chart.height - 14f

        val min = series.values.min()
        val max = series.values.max()
        // A flat line still gets a visible band rather than dividing by zero.
        val pad = if (max - min < 1e-3f) maxOf(1f, max * 0.05f) else (max - min) * 0.08f
        val low = min - pad
        val high = max + pad
        val start = series.times.first()
        val span = (series.times.last() - start).coerceAtLeast(1L)

        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID; strokeWidth = 0.6f }
        canvas.drawLine(left, bottom, right, bottom, grid)
        canvas.drawLine(left, top, right, top, grid)
        canvas.drawText(format(max, series.decimals), chart.x, yFor(max, low, high, top, bottom) + 3f, labelPaint)
        canvas.drawText(format(min, series.decimals), chart.x, yFor(min, low, high, top, bottom) + 3f, labelPaint)

        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = this@ReportPdfWriter.timeZone }
        canvas.drawText(clock.format(Date(start)), left, chart.y + chart.height - 2f, labelPaint)
        val endLabel = clock.format(Date(series.times.last()))
        canvas.drawText(endLabel, right - labelPaint.measureText(endLabel), chart.y + chart.height - 2f, labelPaint)

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LINE; style = Paint.Style.STROKE; strokeWidth = 1.2f; strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        series.times.forEachIndexed { i, t ->
            val x = left + (right - left) * ((t - start).toFloat() / span)
            val y = yFor(series.values[i], low, high, top, bottom)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
    }

    private fun yFor(value: Float, low: Float, high: Float, top: Float, bottom: Float) =
        bottom - (bottom - top) * ((value - low) / (high - low))

    private fun format(value: Float, decimals: Int) = String.format(Locale.US, "%.${decimals}f", value)

    private fun toneInk(tone: Tone) = when (tone) {
        Tone.OK -> Color.rgb(0x2E, 0x6B, 0x3A)
        Tone.NOTE -> MUTED
        Tone.WARN -> Color.rgb(0x8A, 0x5A, 0x00)
        Tone.ALERT -> Color.rgb(0xB0, 0x24, 0x23)
    }

    private fun toneFill(tone: Tone) = when (tone) {
        Tone.OK -> Color.rgb(0xEC, 0xF5, 0xEE)
        Tone.NOTE -> Color.rgb(0xF3, 0xF3, 0xF1)
        Tone.WARN -> Color.rgb(0xFF, 0xF4, 0xDC)
        Tone.ALERT -> Color.rgb(0xFB, 0xEA, 0xEA)
    }

    private companion object {
        val INK = Color.rgb(0x1D, 0x1D, 0x1B)
        val MUTED = Color.rgb(0x5E, 0x5E, 0x58)
        val GRID = Color.rgb(0xDD, 0xDA, 0xD3)
        val LINE = Color.rgb(0x2C, 0x2C, 0x2C)
    }
}
