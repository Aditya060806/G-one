package com.gone.ai.health.data

import android.content.Context
import androidx.room.withTransaction
import com.gone.ai.data.library.GoneDatabase
import com.gone.ai.health.detect.AnomalyDetector
import com.gone.ai.health.detect.BaselineDeviationRule
import com.gone.ai.health.detect.CardiovascularStrainRule
import com.gone.ai.health.detect.CriticalSpo2Rule
import com.gone.ai.health.detect.DehydrationRiskRule
import com.gone.ai.health.detect.FallDetectionRule
import com.gone.ai.health.detect.FatigueRule
import com.gone.ai.health.detect.FeverRule
import com.gone.ai.health.detect.HeatStressRule
import com.gone.ai.health.detect.HighHeartRateRule
import com.gone.ai.health.detect.LowHeartRateRule
import com.gone.ai.health.detect.RespiratoryDistressRule
import com.gone.ai.health.detect.SustainedSpo2Rule
import com.gone.ai.health.domain.AnomalyEvidence
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.RiskScores
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.Trend
import com.gone.ai.health.explain.ExplanationTemplates
import com.gone.ai.health.service.HealthMonitoringService
import com.gone.ai.health.service.MonitoringSnapshot
import com.gone.ai.health.service.MonitoringState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Seeds a day of demo history for the "try with demo data" path.
 *
 * DEMO DATA MUST OBEY THE SAME RULES AS REAL DATA.
 *
 * The first version inserted alerts whose rule ids did not exist
 * (`cardiac.tachycardia.resting_critical`), whose severities no rule can produce, and
 * whose "AI" text named conditions and gave advice ("heat exhaustion risk", "drink water
 * immediately") — exactly what `HealthPromptBuilder.validate` exists to reject. A demo
 * that shows the product doing what it promises never to do is worse than no demo.
 *
 * Now every event cites a real rule id at a severity that rule can produce, carries the
 * real deterministic template, and any AI rewrite is one that passes validation, stored
 * with the fixed tier guidance exactly as the pipeline stores it. `MockHealthDataSeederTest`
 * enforces all of that.
 *
 * Readings are provenance-tagged SIMULATED and use the engine's motion scale (about 1 g
 * at rest), so the detector and charts read them the way they read the simulator.
 */
object MockHealthDataSeeder {

    data class SeedResult(
        val readingsCount: Int,
        val activeEventsCount: Int,
        val totalEventsCount: Int
    )

    /** Profile details from onboarding. Missing fields stay neutral rather than invented. */
    data class DemoProfile(
        val name: String? = null,
        val age: Int? = null,
        val emergencyContact: String? = null
    )

    private const val DEMO_DEVICE_ID = "sim-watch-01"
    private const val MINUTE = 60_000L
    private const val READINGS_PER_DAY = 1_440

    suspend fun clearAll(context: Context): Unit = withContext(Dispatchers.IO) {
        val db = GoneDatabase.getInstance(context)
        db.withTransaction { clear(db, HealthMonitoringService.DEFAULT_PATIENT_ID) }
    }

    private suspend fun clear(db: GoneDatabase, patientId: String) {
        db.vitalsDao().clearAll(patientId)
        db.anomalyDao().clearAll(patientId)
        db.deviceDao().clearForPatient(patientId)
        db.sessionReportDao().clearAll(patientId)
        db.sessionDao().clearAll(patientId)
    }

    suspend fun seed(context: Context, profile: DemoProfile = DemoProfile()): SeedResult =
        withContext(Dispatchers.IO) {
            val db = GoneDatabase.getInstance(context)
            val patientId = HealthMonitoringService.DEFAULT_PATIENT_ID
            val now = System.currentTimeMillis()

            val readings = demoReadings(patientId, now)
            val events = demoEvents(now).map { it.toEntity(patientId, now) }

            db.withTransaction {
                // Replace, never append: seeding twice (redoing onboarding) used to
                // duplicate every alert because only readings were cleared.
                clear(db, patientId)

                db.patientDao().upsert(
                    PatientEntity(
                        id = patientId,
                        name = profile.name ?: "Demo user",
                        age = profile.age ?: 0,
                        emergencyContactPhone = profile.emergencyContact,
                        createdAt = now - 7L * 24 * 60 * MINUTE
                    )
                )
                db.deviceDao().upsert(
                    DeviceEntity(
                        id = DEMO_DEVICE_ID,
                        patientId = patientId,
                        displayName = "Simulated wearable",
                        transport = ReadingSource.SIMULATED.wireName,
                        lastSeenAt = now
                    )
                )
                db.vitalsDao().insertAll(readings)
                events.forEach { db.anomalyDao().insert(it) }
            }

            // Scores for the dashboard come from the real engine, not a hand-typed number.
            val recent = readings.takeLast(30).map { it.toDomain() }
            HealthMonitoringService.updateSnapshot(
                MonitoringSnapshot(
                    state = MonitoringState.IDLE,
                    latest = recent.lastOrNull(),
                    risk = AnomalyDetector().evaluate(recent, now = now).riskScores,
                    lastEventAt = events.maxOfOrNull { it.createdAt }
                )
            )

            SeedResult(
                readingsCount = readings.size,
                activeEventsCount = events.count { it.status == EventStatus.ACTIVE.wireName },
                totalEventsCount = events.size
            )
        }

    // ── Readings ──────────────────────────────────────────────────────────────

    /**
     * One reading a minute for the last 24 hours, with episodes that line up with
     * [demoEvents]. Offsets below are minutes before [now].
     */
    internal fun demoReadings(patientId: String, now: Long): List<VitalsReadingEntity> =
        (READINGS_PER_DAY - 1 downTo 0).map { minutesAgo ->
            val dayPhase = (READINGS_PER_DAY - 1 - minutesAgo).toDouble() / READINGS_PER_DAY
            val diurnal = sin(dayPhase * 2 * PI - PI / 2)            // low at night

            var hr = 70.0 + diurnal * 8 + sin(minutesAgo * 0.15) * 3
            var spo2 = 97.0 + sin(minutesAgo * 0.08)
            var temp = 36.6 + diurnal * 0.25 + sin(minutesAgo * 0.1) * 0.08
            // Accelerometer magnitude in g: ~1.0 is gravity alone, i.e. still.
            var motion = 1.0 + (if (diurnal > 0) 0.08 else 0.01) + abs(sin(minutesAgo * 0.2)) * 0.03
            var ambient = 26.0 + diurnal * 5
            var humidity = 55.0 - diurnal * 6
            var aqi = 70.0 + diurnal * 20

            fun episode(from: Int, to: Int): Double? =
                if (minutesAgo in from..to) sin((to - minutesAgo).toDouble() / (to - from) * PI) else null

            // Low resting heart rate overnight (event 21 h ago).
            if (minutesAgo in 1_254..1_266) { hr = 43.0; motion = 1.0 }
            // Oxygen dip (event 17 h ago).
            if (minutesAgo in 1_017..1_023) spo2 = 88.0
            // Elevated resting heart rate for a stretch (event 15 h ago).
            if (minutesAgo in 900..926) { hr = 98.0; motion = 1.02 }
            // Fever (event 12 h ago).
            episode(680, 780)?.let { e -> temp += e * 1.9; hr += e * 24; motion = 1.0 }
            // Fall: impact 10 h ago, then stillness.
            if (minutesAgo == 600) motion = 5.4
            if (minutesAgo in 596..599) { motion = 1.0; hr = 104.0 }
            // Heat exposure, 9 h 40 min to 7 h ago (heat stress, strain, dehydration events).
            // Ends before the fall so it cannot overwrite the impact reading.
            episode(420, 580)?.let { e ->
                ambient = 30.0 + e * 8
                humidity = 50.0 + e * 5
                aqi += e * 45
                hr = 96.0 + e * 36
                temp = 37.0 + e * 0.6
                motion = 1.0
            }
            // Falling oxygen under poor air, 4.8 h to 3.8 h ago.
            episode(230, 290)?.let { e -> spo2 = 97.0 - e * 9; hr += e * 18; aqi += e * 170 }
            // Fast heart rate at rest (event 35 min ago).
            episode(25, 45)?.let { e -> hr += e * 86; motion = 1.0 }
            // Drift above personal normal (event 15 min ago).
            if (minutesAgo in 10..20) hr = 84.0

            VitalsReadingEntity(
                patientId = patientId,
                timestamp = now - minutesAgo * MINUTE,
                heartRate = hr.roundToInt().coerceIn(35, 190),
                spo2 = spo2.roundToInt().coerceIn(80, 100),
                bodyTempC = ((temp * 10).roundToInt() / 10f).coerceIn(35.0f, 41.5f),
                motionMagnitudeG = ((motion * 100).roundToInt() / 100f).coerceIn(0.9f, 8.0f),
                ambientTempC = (ambient * 10).roundToInt() / 10f,
                ambientHumidityPct = ((humidity * 10).roundToInt() / 10f).coerceIn(10f, 99f),
                aqi = aqi.roundToInt().coerceAtLeast(0),
                source = ReadingSource.SIMULATED.wireName
            )
        }

    // ── Events ────────────────────────────────────────────────────────────────

    /** One demo alert: what the engine would have recorded, plus an optional rewrite. */
    internal data class DemoEvent(
        val evidence: AnomalyEvidence,
        /** Model-style rewrite of the detail, or null for a template-only alert. */
        val aiRewrite: String?,
        /** Minutes after the event it was acknowledged, or null if still active. */
        val acknowledgedAfterMinutes: Int?
    ) {
        fun toEntity(patientId: String, now: Long): AnomalyEventEntity {
            val template = ExplanationTemplates.render(evidence)
            return evidence.toEventEntity(patientId, template.full).copy(
                // Stored exactly as MonitoringPipeline stores a rewrite.
                aiExplanation = aiRewrite?.let { "$it\n\n${template.tier.guidance}" },
                status = if (acknowledgedAfterMinutes != null) EventStatus.ACKNOWLEDGED.wireName
                         else EventStatus.ACTIVE.wireName,
                acknowledgedAt = acknowledgedAfterMinutes?.let { evidence.triggeredAt + it * MINUTE }
                    ?.coerceAtMost(now)
            )
        }
    }

    internal fun demoEvents(now: Long): List<DemoEvent> {
        fun at(minutesAgo: Int) = now - minutesAgo * MINUTE

        return listOf(
            // ── Active ────────────────────────────────────────────────────────
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.HIGH_HEART_RATE, severity = Severity.CRITICAL,
                    triggeredAt = at(35), ruleId = HighHeartRateRule.id,
                    riskScores = RiskScores(12, 18, 71),
                    heartRate = 158, spo2 = 97, bodyTempC = 36.9f,
                    durationMinutes = 12, trend = Trend.RISING, motionDetected = false
                ),
                aiRewrite = "The heart has been beating 158 times a minute for about 12 minutes " +
                    "while sitting still, which is much faster than usual at rest.",
                acknowledgedAfterMinutes = null
            ),
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.BASELINE_DEVIATION, severity = Severity.LOW,
                    triggeredAt = at(15), ruleId = BaselineDeviationRule.id,
                    riskScores = RiskScores(10, 12, 24),
                    heartRate = 84, spo2 = 97, bodyTempC = 36.8f,
                    trend = Trend.STABLE, baselineDeltaPct = 21.7f, motionDetected = false
                ),
                aiRewrite = null,
                acknowledgedAfterMinutes = null
            ),
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.RESPIRATORY_DISTRESS, severity = Severity.CRITICAL,
                    triggeredAt = at(245), ruleId = RespiratoryDistressRule.id,
                    riskScores = RiskScores(8, 82, 47),
                    heartRate = 106, spo2 = 88, bodyTempC = 37.0f, aqi = 236,
                    trend = Trend.FALLING, motionDetected = false
                ),
                aiRewrite = "Blood oxygen has fallen to 88% while the air quality index outside " +
                    "is 236, and it is still going down. The lungs may be working harder than usual.",
                acknowledgedAfterMinutes = null
            ),
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.HEAT_STRESS, severity = Severity.MODERATE,
                    triggeredAt = at(495), ruleId = HeatStressRule.id,
                    riskScores = RiskScores(68, 20, 41),
                    heartRate = 112, spo2 = 96, bodyTempC = 37.5f,
                    ambientTempC = 35.0f, ambientHumidityPct = 50f, aqi = 118,
                    trend = Trend.RISING, motionDetected = false
                ),
                aiRewrite = "It is around 35 °C outside with humidity at 50%, and body temperature " +
                    "has risen to 37.5 °C with the heart beating 112 times a minute.",
                acknowledgedAfterMinutes = null
            ),

            // ── Acknowledged ──────────────────────────────────────────────────
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.FALL_DETECTED, severity = Severity.CRITICAL,
                    triggeredAt = at(598), ruleId = FallDetectionRule.id,
                    riskScores = RiskScores(0, 9, 38),
                    heartRate = 104, spo2 = 96, durationMinutes = 2,
                    fallImpactG = 5.4f, motionDetected = false
                ),
                aiRewrite = "A sudden hard movement like a fall was picked up, followed by about " +
                    "2 minutes with almost no movement.",
                acknowledgedAfterMinutes = 9
            ),
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.CARDIOVASCULAR_STRAIN, severity = Severity.MODERATE,
                    triggeredAt = at(540), ruleId = CardiovascularStrainRule.id,
                    riskScores = RiskScores(55, 37, 63),
                    heartRate = 132, spo2 = 93, bodyTempC = 37.5f,
                    trend = Trend.RISING, motionDetected = false
                ),
                aiRewrite = null,
                acknowledgedAfterMinutes = 20
            ),
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.DEHYDRATION_RISK, severity = Severity.MODERATE,
                    triggeredAt = at(450), ruleId = DehydrationRiskRule.id,
                    riskScores = RiskScores(74, 18, 52),
                    heartRate = 108, bodyTempC = 37.5f,
                    ambientTempC = 38.0f, ambientHumidityPct = 55f,
                    trend = Trend.RISING, motionDetected = false
                ),
                aiRewrite = "In this heat the heart rate has climbed to 108 a minute while body " +
                    "temperature rose to 37.5 °C, a pattern that often appears when the body is short of fluids.",
                acknowledgedAfterMinutes = 30
            ),
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.FEVER, severity = Severity.MODERATE,
                    triggeredAt = at(730), ruleId = FeverRule.id,
                    riskScores = RiskScores(38, 14, 36),
                    heartRate = 98, spo2 = 96, bodyTempC = 38.5f,
                    durationMinutes = 40, trend = Trend.RISING, motionDetected = false
                ),
                aiRewrite = "Body temperature has stayed raised at 38.5 °C for about 40 minutes.",
                acknowledgedAfterMinutes = 30
            ),
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.SUSTAINED_LOW_SPO2, severity = Severity.MODERATE,
                    triggeredAt = at(270), ruleId = SustainedSpo2Rule.id,
                    riskScores = RiskScores(6, 58, 30),
                    heartRate = 96, spo2 = 91, durationMinutes = 14,
                    trend = Trend.STABLE, motionDetected = false
                ),
                aiRewrite = "Blood oxygen has stayed at 91% for about 14 minutes, which is lower " +
                    "than normal for that long.",
                acknowledgedAfterMinutes = 25
            ),
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.FATIGUE, severity = Severity.LOW,
                    triggeredAt = at(900), ruleId = FatigueRule.id,
                    riskScores = RiskScores(4, 6, 22),
                    heartRate = 98, durationMinutes = 24, motionDetected = false
                ),
                aiRewrite = null,
                acknowledgedAfterMinutes = 60
            ),
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.LOW_HEART_RATE, severity = Severity.MODERATE,
                    triggeredAt = at(1_260), ruleId = LowHeartRateRule.id,
                    riskScores = RiskScores(0, 5, 31),
                    heartRate = 43, spo2 = 97, durationMinutes = 6, motionDetected = false
                ),
                aiRewrite = "The heart has been beating 43 times a minute for about 6 minutes " +
                    "while resting, which is slower than usual.",
                acknowledgedAfterMinutes = 40
            ),
            DemoEvent(
                AnomalyEvidence(
                    type = AnomalyType.LOW_SPO2, severity = Severity.CRITICAL,
                    triggeredAt = at(1_020), ruleId = CriticalSpo2Rule.id,
                    riskScores = RiskScores(3, 71, 26),
                    heartRate = 92, spo2 = 88, motionDetected = false
                ),
                aiRewrite = null,
                acknowledgedAfterMinutes = 5
            )
        )
    }
}
