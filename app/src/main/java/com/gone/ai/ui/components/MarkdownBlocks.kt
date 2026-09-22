package com.gone.ai.ui.components

/**
 * The structure of the on-device model's markdown, parsed without Compose.
 *
 * Chat, the document tools and the Vault all show model output. Chat used to parse it in
 * a private renderer while every tool printed the raw text, so a summary showed its `**`
 * and `###` to the user. The parsing now lives here, once, where it can be unit-tested,
 * and [MarkdownText] draws it.
 *
 * Deliberately small. A 1.5B model writes headings, bullets, numbered lists, quotes,
 * fenced code and inline emphasis; tables and links are shown as the text they are.
 */
sealed interface MarkdownBlock {
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Numbered(val number: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock

    /** [closed] is false while a streaming reply has opened a fence but not closed it yet. */
    data class Code(val language: String, val code: String, val closed: Boolean = true) : MarkdownBlock

    /** A blank line between blocks. Never first or last, never two in a row. */
    data object Gap : MarkdownBlock
}

/** Inline emphasis within one block's text. */
enum class InlineStyle { PLAIN, BOLD, ITALIC, BOLD_ITALIC, CODE, STRIKE }

data class InlineSpan(val text: String, val style: InlineStyle)

object MarkdownBlocks {

    private val FENCE = Regex("```([\\w+-]*)[ \\t]*\\n?([\\s\\S]*?)```")
    private val BULLET = Regex("""^([*\-•])\s+(.*)""")
    private val NUMBERED = Regex("""^(\d{1,3})[.)]\s+(.*)""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*)""")

    private val INLINE = Regex(
        """\*\*\*(.+?)\*\*\*|\*\*(.+?)\*\*|__(.+?)__|(?<![*\w])\*([^*\n]+?)\*(?![*\w])|(?<![_\w])_([^_\n]+?)_(?![_\w])|`([^`\n]+)`|~~([^~\n]+)~~"""
    )

    /**
     * Blocks in reading order. Empty input gives one empty paragraph, so a reply that has
     * not produced its first token still has somewhere to show a cursor.
     */
    fun parse(raw: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var cursor = 0
        // The line breaks that belong to a fence are not blank lines of their own.
        for (match in FENCE.findAll(raw)) {
            if (match.range.first > cursor) {
                parseText(raw.substring(cursor, match.range.first).removePrefix("\n").removeSuffix("\n"), blocks)
            }
            addBlock(blocks, MarkdownBlock.Code(match.groupValues[1], match.groupValues[2].trimEnd('\n')))
            cursor = match.range.last + 1
        }
        val rest = raw.substring(cursor).let { if (cursor > 0) it.removePrefix("\n") else it }
        val open = rest.indexOf("```")
        if (open >= 0) {
            // An opening fence with no closing one yet: a reply still streaming its code.
            parseText(rest.substring(0, open).removeSuffix("\n"), blocks)
            val afterFence = rest.substring(open + 3)
            val newline = afterFence.indexOf('\n')
            val language = if (newline >= 0) afterFence.substring(0, newline).trim() else afterFence.trim()
            val code = if (newline >= 0) afterFence.substring(newline + 1) else ""
            addBlock(blocks, MarkdownBlock.Code(language, code.trimEnd('\n'), closed = false))
        } else {
            parseText(rest, blocks)
        }
        while (blocks.lastOrNull() == MarkdownBlock.Gap) blocks.removeAt(blocks.lastIndex)
        return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(raw.trim())) }
    }

    /** Emphasis runs of [text]; markers are removed and unmatched ones kept as typed. */
    fun inline(text: String): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        var cursor = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > cursor) spans += InlineSpan(text.substring(cursor, match.range.first), InlineStyle.PLAIN)
            val g = match.groupValues
            spans += when {
                g[1].isNotEmpty() -> InlineSpan(g[1], InlineStyle.BOLD_ITALIC)
                g[2].isNotEmpty() -> InlineSpan(g[2], InlineStyle.BOLD)
                g[3].isNotEmpty() -> InlineSpan(g[3], InlineStyle.BOLD)
                g[4].isNotEmpty() -> InlineSpan(g[4], InlineStyle.ITALIC)
                g[5].isNotEmpty() -> InlineSpan(g[5], InlineStyle.ITALIC)
                g[6].isNotEmpty() -> InlineSpan(g[6], InlineStyle.CODE)
                else -> InlineSpan(g[7], InlineStyle.STRIKE)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) spans += InlineSpan(text.substring(cursor), InlineStyle.PLAIN)
        return spans
    }

    /**
     * [raw] without markup, one block per line: for sharing as plain text and for speech.
     * Bullets become "• " lines and numbered items keep their numbers.
     */
    fun plainText(raw: String): String = parse(raw).joinToString("\n") { block ->
        when (block) {
            is MarkdownBlock.Heading -> strip(block.text)
            is MarkdownBlock.Bullet -> "• " + strip(block.text)
            is MarkdownBlock.Numbered -> "${block.number}. " + strip(block.text)
            is MarkdownBlock.Quote -> strip(block.text)
            is MarkdownBlock.Paragraph -> strip(block.text)
            is MarkdownBlock.Table -> (listOf(block.headers) + block.rows).joinToString("\n") { row -> row.joinToString("\t") { strip(it) } }
            is MarkdownBlock.Code -> block.code
            MarkdownBlock.Gap -> ""
        }
    }.trim()

    private fun strip(text: String) = inline(text).joinToString("") { it.text }

    private fun parseText(text: String, blocks: MutableList<MarkdownBlock>) {
        val lines = text.lines()
        var index = 0
        fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
        while (index < lines.size) {
            val line = lines[index++]
            if ('|' in line && index < lines.size) {
                val headers = cells(line)
                val divider = cells(lines[index])
                if (headers.size > 1 && divider.size == headers.size && divider.all { it.matches(Regex(":?-{3,}:?")) }) {
                    index++
                    val rows = mutableListOf<List<String>>()
                    while (index < lines.size && '|' in lines[index] && cells(lines[index]).size == headers.size) {
                        rows += cells(lines[index++])
                    }
                    addBlock(blocks, MarkdownBlock.Table(headers, rows))
                    continue
                }
            }
            val trimmed = line.trim()
            val heading = HEADING.find(trimmed)
            val bullet = BULLET.find(trimmed)
            val numbered = NUMBERED.find(trimmed)
            when {
                trimmed.isEmpty() -> addBlock(blocks, MarkdownBlock.Gap)
                heading != null -> addBlock(
                    blocks,
                    MarkdownBlock.Heading(heading.groupValues[1].length.coerceAtMost(3), heading.groupValues[2].trim().trimEnd('#').trim())
                )
                // "> " with the space: a lab line such as ">90 mL/min" is a value, not a quote.
                trimmed.startsWith("> ") -> addBlock(blocks, MarkdownBlock.Quote(trimmed.removePrefix("> ").trim()))
                // "**Bold**" alone on a line starts with '*' but is emphasis, not a bullet.
                bullet != null && !trimmed.startsWith("**") -> addBlock(blocks, MarkdownBlock.Bullet(bullet.groupValues[2]))
                numbered != null -> addBlock(blocks, MarkdownBlock.Numbered(numbered.groupValues[1], numbered.groupValues[2]))
                else -> addBlock(blocks, MarkdownBlock.Paragraph(line.trimEnd()))
            }
        }
    }

    private fun addBlock(blocks: MutableList<MarkdownBlock>, block: MarkdownBlock) {
        if (block == MarkdownBlock.Gap && (blocks.isEmpty() || blocks.last() == MarkdownBlock.Gap)) return
        blocks += block
    }
}
