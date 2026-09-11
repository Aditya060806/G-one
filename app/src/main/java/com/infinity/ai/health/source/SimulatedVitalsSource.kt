package com.infinity.ai.health.source

import com.infinity.ai.health.data.ReadingSource
import com.infinity.ai.health.domain.VitalsSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * The Phase 1 default source: no hardware required.
 *
 * WHY THIS IS A FIRST-CLASS COMPONENT AND NOT A TEST STUB
 *
 * The wearable does not exist yet, but the safety-critical spine — persistence,
 * detection, explanation, alerting — has to be finished and verified before hardware
 * arrives. This source lets the entire pipeline run and be demonstrated today, and
 * it makes anomalies reproducible on demand, which no real sensor can do. When the
 * HM-10 BLE implementation lands it is a peer of this class, not a replacement:
 * keeping the simulator shipping means regressions in the detection engine stay
 * catchable forever.
 *
 * Timing uses plain `delay`, so `runTest` drives it on virtual time — a 4-hour
 * deterioration scenario is exercised in milliseconds with no real waiting.
 */
class SimulatedVitalsSource(
    scenario: VitalsScenario = VitalsScenario.HEALTHY_BASELINE,
    private val intervalMillis: Long = 1_000L,
    private val startAt: Long = System.currentTimeMillis(),
    private val seed: Long = 42L,
    /**
     * Samples to emit before completing. `null` loops the scenario indefinitely,
     * holding its final state — which is what the live monitoring service wants.
     */
    private val sampleLimit: Int? = null,
    /** Emit the first sample immediately instead of waiting one interval. */
    private val emitImmediately: Boolean = true
) : VitalsSource {

    val generator = VitalsScenarioGenerator(
        scenario       = scenario,
        startAt        = startAt,
        intervalMillis = intervalMillis,
        seed           = seed
    )

    override val descriptor = SourceDescriptor(
        id          = "simulated-${scenario.name.lowercase()}",
        displayName = "Simulated · ${scenario.displayName}",
        transport   = ReadingSource.SIMULATED
    )

    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Idle)
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    override fun stream(): Flow<VitalsSample> = flow {
        _status.value = SourceStatus.Starting
        var index = 0
        _status.value = SourceStatus.Streaming
        while (sampleLimit == null || index < sampleLimit) {
            if (index > 0 || !emitImmediately) delay(intervalMillis)
            emit(generator.sampleAt(index))
            index++
        }
    }.onCompletion { cause ->
        // Distinguish "collector went away" from "the source broke". Cancellation is
        // normal shutdown; anything else is a genuine failure and must not be
        // reported to the UI as a clean stop.
        _status.value = when (cause) {
            null -> SourceStatus.Stopped
            is kotlinx.coroutines.CancellationException -> SourceStatus.Stopped
            else -> SourceStatus.Failed(cause.message ?: cause::class.simpleName ?: "unknown")
        }
    }
}
