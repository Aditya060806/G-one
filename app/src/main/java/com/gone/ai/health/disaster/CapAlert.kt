package com.gone.ai.health.disaster

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Official disaster alerts from SACHET, the National Disaster Management Authority's Common
 * Alerting Protocol (CAP 1.2) system.
 *
 * PURE KOTLIN (the JDK's XML parser, which Android also has), so the parsing is tested on real
 * saved feeds in plain JVM tests.
 *
 * Two documents are read. A state's RSS feed lists recent alerts, newest first, each with a link
 * to its CAP message. A CAP message carries one `info` block per language: the event, how severe,
 * urgent and certain it is, when it starts and expires, what to do, and the districts it covers.
 */

/** One entry of a SACHET RSS feed. */
data class FeedItem(
    val identifier: String,
    val title: String,
    val link: String,
    val publishedAt: Long?,
    /** The issuing office, e.g. "IMD Chennai" or "CWC". */
    val issuer: String
)

/** One language's version of an alert. */
data class CapInfo(
    val language: String,
    val category: String,
    val event: String,
    val urgency: String,
    val severity: String,
    val certainty: String,
    val headline: String,
    val description: String,
    val instruction: String,
    val areaDesc: String,
    val onset: Long?,
    val expires: Long?
)

data class CapAlert(
    val identifier: String,
    val sender: String,
    val sent: Long?,
    val status: String,
    val msgType: String,
    val infos: List<CapInfo>
) {
    /** The English block when there is one; SACHET sends English beside the local language. */
    val english: CapInfo? get() = infos.firstOrNull { it.language.lowercase().startsWith("en") } ?: infos.firstOrNull()

    /** The local-language headline, when the alert has one besides the English. */
    val localHeadline: String?
        get() = infos.firstOrNull { !it.language.lowercase().startsWith("en") && it.headline.isNotBlank() }?.headline

    val expires: Long? get() = infos.mapNotNull { it.expires }.maxOrNull()

    /** A real, current warning: not an exercise, not a cancellation, not expired. */
    fun isActive(now: Long): Boolean =
        status.equals("Actual", ignoreCase = true) &&
            !msgType.equals("Cancel", ignoreCase = true) &&
            (expires?.let { it > now } ?: true)
}

object CapParser {

    private val RSS_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

    /** The items of a SACHET RSS feed, in the feed's order. Malformed items are skipped. */
    fun feed(xml: String): List<FeedItem> {
        val root = parse(xml) ?: return emptyList()
        return children(root, "channel").flatMap { children(it, "item") }.mapNotNull { item ->
            val link = text(item, "link")
            val identifier = text(item, "guid").ifBlank { link.substringAfter("identifier=", "") }
            if (identifier.isBlank() || link.isBlank()) return@mapNotNull null
            FeedItem(
                identifier = identifier,
                title = text(item, "title"),
                link = link,
                publishedAt = runCatching { ZonedDateTime.parse(text(item, "pubDate"), RSS_DATE).toInstant().toEpochMilli() }.getOrNull(),
                issuer = text(item, "author").substringAfter('(', "").substringBefore(')').trim()
            )
        }
    }

    /** A CAP 1.2 message, or null when the document is not one. */
    fun alert(xml: String): CapAlert? {
        val root = parse(xml) ?: return null
        if (root.localName() != "alert") return null
        val identifier = text(root, "identifier").ifBlank { return null }
        return CapAlert(
            identifier = identifier,
            sender = text(root, "sender"),
            sent = time(text(root, "sent")),
            status = text(root, "status"),
            msgType = text(root, "msgType"),
            infos = children(root, "info").map { info ->
                CapInfo(
                    language = text(info, "language"),
                    category = text(info, "category"),
                    event = text(info, "event"),
                    urgency = text(info, "urgency"),
                    severity = text(info, "severity"),
                    certainty = text(info, "certainty"),
                    headline = text(info, "headline"),
                    description = text(info, "description"),
                    instruction = text(info, "instruction"),
                    areaDesc = children(info, "area").joinToString("; ") { text(it, "areaDesc") }.trim(),
                    onset = time(text(info, "onset")) ?: time(text(info, "effective")),
                    expires = time(text(info, "expires"))
                )
            }
        )
    }

    private fun time(value: String): Long? =
        value.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /**
     * A namespace-aware parse with no DTDs and no external entities: the documents come from the
     * network, so nothing in them may make the parser fetch or expand anything.
     */
    private fun parse(xml: String): Element? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }.getOrNull()

    private fun Node.localName(): String = (localName ?: nodeName).substringAfter(':')

    private fun children(parent: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.localName() == name) out += n
        }
        return out
    }

    private fun text(parent: Element, name: String): String =
        children(parent, name).firstOrNull()?.textContent?.trim().orEmpty()
}
