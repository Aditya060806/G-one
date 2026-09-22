package com.gone.ai.health.source

import java.util.TreeMap

/**
 * Decides which stored wearable records the phone may acknowledge (`ACK:<n>`), for one link.
 *
 * The wearable deletes a record once it is acknowledged, so an acknowledgement is a promise
 * that the reading is in the database. Two things can break that promise, and both are
 * handled here:
 *
 *  - A GAP. Records must be acknowledged in order, because `ACK:<n>` covers everything up to
 *    `n`. If record 41 never arrived, 42 cannot be acknowledged, however well it was stored;
 *    the wearable resends from 41 when acknowledgements stop.
 *  - A READING NOT YET STORED. Readings are combined into 5-second buckets before they are
 *    saved, so a record can have arrived while its reading still waits in an open bucket.
 *    The caller reports that with [hold] and [releaseAll].
 *
 * Acknowledgements are batched: one per [batch] newly covered records, or [maxDelayMillis]
 * after the first uncovered one, whichever comes first. See docs/WEARABLE_PROTOCOL.md.
 *
 * Pure and not thread-safe: one instance per link, used from one coroutine.
 */
class BacklogAckTracker(
    private val batch: Int = ACK_EVERY_RECORDS,
    private val maxDelayMillis: Long = ACK_MAX_DELAY_MILLIS
) {

    /** The lowest record number not yet received in order; null before the first record. */
    private var next: Long? = null
    /** Acknowledgements count progress from here on a new link. */
    private var base: Long? = null
    /** Records received after a gap, as first → last ranges, waiting for the gap to fill. */
    private val ahead = TreeMap<Long, Long>()
    /** The lowest record whose reading is received but not stored yet. */
    private var heldFrom: Long? = null
    private var lastSent: Long? = null
    private var coveredSince: Long? = null

    /** Every record up to this one has arrived and its reading, if any, is stored. */
    val acknowledgeable: Long?
        get() {
            val through = (next ?: return null) - 1
            val limit = heldFrom?.let { minOf(through, it - 1) } ?: through
            return limit.takeIf { it >= 1 && it > (base ?: 0) }
        }

    /**
     * A stored record arrived: [seq], or with [lost] > 0 the records `seq - lost + 1 .. seq`,
     * which exist but carry no reading.
     *
     * @return false when it was already received on this link (a resend), so its reading
     *   must not be processed again.
     */
    fun receive(seq: Long, lost: Int = 0): Boolean {
        val first = seq - maxOf(lost, 1) + 1
        val expected = next
        if (expected == null) {
            base = first - 1
            next = seq + 1
            return true
        }
        if (seq < expected) {
            // The wearable goes back to the record after the last acknowledgement when
            // acknowledgements stop, e.g. because a reading could not be stored. Start over
            // from there, so what was not stored is received and stored again.
            val resumePoint = (lastSent ?: base ?: return false) + 1
            if (first != resumePoint) return false
            reset()
            return receive(seq, lost)
        }
        if (first <= expected) {
            next = seq + 1
            absorbAhead()
            return true
        }
        // After a gap. A resend of something already held here is a duplicate.
        val floor = ahead.floorEntry(seq)
        if (floor != null && floor.value >= seq) return false
        ahead[first] = seq
        if (ahead.size > MAX_AHEAD_RANGES) ahead.pollFirstEntry()
        return true
    }

    /** The reading of record [seq] is waiting to be combined and stored. */
    fun hold(seq: Long) {
        heldFrom = heldFrom?.let { minOf(it, seq) } ?: seq
    }

    /** Every reading received so far is stored. */
    fun releaseAll() {
        heldFrom = null
    }

    /**
     * True when the wearable, whose newest record is [newest], has nothing this link has not
     * received or acknowledged: the last partly filled bucket can be stored now.
     */
    fun hasEverythingThrough(newest: Long): Boolean =
        (next?.let { it > newest } ?: false) || (lastSent?.let { it >= newest } ?: false)

    /** The acknowledgement to send at [now], or null. [force] skips the batching. */
    fun due(now: Long, force: Boolean = false): Long? {
        val covered = acknowledgeable ?: return null
        val previous = lastSent ?: base ?: 0
        if (covered <= previous) {
            coveredSince = null
            return null
        }
        val since = coveredSince ?: now.also { coveredSince = it }
        return covered.takeIf { force || covered - previous >= batch || now - since >= maxDelayMillis }
    }

    /** [seq] was written to the wearable. */
    fun sent(seq: Long) {
        lastSent = maxOf(lastSent ?: 0, seq)
        coveredSince = null
    }

    /** A new link: the wearable resends from its last acknowledged record. */
    fun reset() {
        next = null
        base = null
        ahead.clear()
        heldFrom = null
        lastSent = null
        coveredSince = null
    }

    private fun absorbAhead() {
        while (true) {
            val expected = next ?: return
            val entry = ahead.firstEntry() ?: return
            if (entry.key > expected) return
            ahead.pollFirstEntry()
            if (entry.value >= expected) next = entry.value + 1
        }
    }

    companion object {
        const val ACK_EVERY_RECORDS = 10
        const val ACK_MAX_DELAY_MILLIS = 1_000L
        /** Out-of-order ranges kept; more than this means the link is broken, and the wearable resends anyway. */
        private const val MAX_AHEAD_RANGES = 256
    }
}
