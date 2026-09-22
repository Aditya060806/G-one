package com.gone.ai.health.ui

import com.gone.ai.health.data.VitalsReadingEntity
import com.gone.ai.health.source.SampleAggregator

/**
 * The readings the live graphs and tiles show: only those since the graphs last started over,
 * which happens whenever monitoring or a session starts. Each run then visibly begins from
 * nothing, instead of carrying on from wherever the previous run stopped. Trails and the
 * 2h / 2d / 30d trends are history, and keep showing everything.
 *
 * Stored readings carry the start of their five-second bucket, so the first reading of a run
 * can be stamped a few seconds before the run began; the cut-off is moved back to the start of
 * that bucket to keep it. With no start recorded yet, every reading is shown.
 */
internal fun liveReadings(history: List<VitalsReadingEntity>, chartsFrom: Long?): List<VitalsReadingEntity> {
    if (chartsFrom == null) return history
    val bucket = SampleAggregator.DEFAULT_BUCKET_MILLIS
    val from = Math.floorDiv(chartsFrom, bucket) * bucket
    return history.filter { it.timestamp >= from }
}
