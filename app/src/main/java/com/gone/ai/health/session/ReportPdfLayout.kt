package com.gone.ai.health.session

import com.gone.ai.health.session.ReportPdfContent.Tone

/**
 * Places a [ReportPdfContent] on A4 pages, without drawing anything.
 *
 * Kept free of Android so pagination can be unit-tested: text width comes from the injected
 * `measure` function, which the writer backs with a real Paint. Text wraps at word
 * boundaries and may continue on the next page; a chart or a status box never splits.
 * Every page gets a "Page n of N" footer.
 */
object ReportPdfLayout {

    const val PAGE_WIDTH = 595f      // A4 in PostScript points
    const val PAGE_HEIGHT = 842f
    const val MARGIN = 48f
    const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    const val FOOTER_SPACE = 28f
    const val CHART_HEIGHT = 120f

    enum class Style(val size: Float, val bold: Boolean, val lineHeight: Float) {
        TITLE(20f, true, 26f),
        SUBTITLE(10.5f, false, 15f),
        HEADING(13f, true, 20f),
        BODY(10.5f, false, 15f),
        SMALL(8.5f, false, 12f)
    }

    sealed interface Item

    /** A line of text; [y] is the baseline. */
    data class Text(val text: String, val style: Style, val x: Float, val y: Float, val tone: Tone? = null) : Item

    /** A tinted rectangle behind status lines. */
    data class Box(val x: Float, val y: Float, val width: Float, val height: Float, val tone: Tone) : Item

    data class Chart(val series: ReportPdfContent.ChartSeries, val x: Float, val y: Float, val width: Float, val height: Float) : Item

    data class Page(val number: Int, val items: List<Item>)

    fun layout(content: ReportPdfContent, measure: (String, Style) -> Float): List<Page> {
        val pages = mutableListOf<MutableList<Item>>(mutableListOf())
        var y = MARGIN
        val bottom = PAGE_HEIGHT - MARGIN - FOOTER_SPACE

        fun newPage() {
            pages += mutableListOf<Item>()
            y = MARGIN
        }
        fun ensure(height: Float) {
            if (y + height > bottom && y > MARGIN) newPage()
        }
        fun text(value: String, style: Style, indent: Float = 0f, tone: Tone? = null) {
            for (line in wrap(value, style, CONTENT_WIDTH - indent, measure)) {
                ensure(style.lineHeight)
                y += style.lineHeight
                pages.last() += Text(line, style, MARGIN + indent, y - (style.lineHeight - style.size) / 2f, tone)
            }
        }
        fun gap(height: Float) {
            y += height
        }
        fun heading(value: String) {
            // Keep a heading with at least two lines of what follows.
            ensure(Style.HEADING.lineHeight + 2 * Style.BODY.lineHeight + 10f)
            gap(10f)
            text(value, Style.HEADING)
            gap(2f)
        }

        text(content.title, Style.TITLE)
        text(content.subtitle, Style.SUBTITLE)
        gap(10f)

        // Status box: every line in its own tint so an alert reads differently from a note.
        for (line in content.status) {
            val lines = wrap(line.text, Style.BODY, CONTENT_WIDTH - 20f, measure)
            val height = lines.size * Style.BODY.lineHeight + 10f
            ensure(height + 4f)
            pages.last() += Box(MARGIN, y, CONTENT_WIDTH, height, line.tone)
            var lineY = y + 5f
            for (l in lines) {
                lineY += Style.BODY.lineHeight
                pages.last() += Text(l, Style.BODY, MARGIN + 10f, lineY - (Style.BODY.lineHeight - Style.BODY.size) / 2f, line.tone)
            }
            y += height + 4f
        }

        heading("What was recorded")
        content.observations.forEach { text("•  $it", Style.BODY, indent = 4f) }

        if (content.points.isNotEmpty()) {
            heading("Through the session")
            text("Each point averages one fifth of the session; motion shows its peak.", Style.SMALL)
            gap(2f)
            content.points.forEach { row -> text("${row.label} (${row.time}):  ${row.values}", Style.BODY, indent = 4f) }
        }

        if (content.charts.isNotEmpty()) {
            heading("Readings over time")
            for (series in content.charts) {
                val block = Style.BODY.lineHeight + CHART_HEIGHT + 14f
                ensure(block)
                text(if (series.unit.isEmpty()) series.title else "${series.title} (${series.unit})", Style.BODY)
                pages.last() += Chart(series, MARGIN, y + 4f, CONTENT_WIDTH, CHART_HEIGHT)
                y += CHART_HEIGHT + 14f
            }
        }

        heading("Alerts raised")
        if (content.events.isEmpty()) {
            text("No alerts were raised.", Style.BODY)
        } else {
            content.events.forEach { e -> text("${e.time}  ${e.label} — ${e.severity}", Style.BODY, indent = 4f, tone = e.tone) }
        }

        content.aiSummary?.let { summary ->
            heading("Plain-language summary")
            text("Reworded on this phone by the on-device model from the observations above, and checked: it may not add numbers, advice or diagnoses.", Style.SMALL)
            gap(2f)
            text(summary, Style.BODY)
        }

        gap(12f)
        text(content.disclaimer, Style.SMALL)

        val total = pages.size
        return pages.mapIndexed { index, items ->
            val footer = "G-one monitoring report · Page ${index + 1} of $total"
            Page(index + 1, items + Text(footer, Style.SMALL, MARGIN, PAGE_HEIGHT - MARGIN + 8f))
        }
    }

    /**
     * Lines of [text] no wider than [width]. Breaks at spaces; a single word wider than the
     * line is split, so nothing runs off the page. Existing line breaks are kept.
     */
    fun wrap(text: String, style: Style, width: Float, measure: (String, Style) -> Float): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            var current = ""
            for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (measure(candidate, style) <= width) {
                    current = candidate
                    continue
                }
                if (current.isNotEmpty()) lines += current
                current = word
                while (measure(current, style) > width && current.length > 1) {
                    var cut = current.length - 1
                    while (cut > 1 && measure(current.substring(0, cut), style) > width) cut--
                    lines += current.substring(0, cut)
                    current = current.substring(cut)
                }
            }
            lines += current
        }
        return lines
    }
}
