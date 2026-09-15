package com.infinity.ai.health.data

import android.content.Context
import com.infinity.ai.data.library.GoneDatabase
import com.infinity.ai.health.domain.AnomalyEvidence
import com.infinity.ai.health.domain.AnomalyType
import com.infinity.ai.health.domain.RiskScores
import com.infinity.ai.health.domain.Severity
import com.infinity.ai.health.domain.Trend
import com.infinity.ai.health.domain.VitalsSample
import com.infinity.ai.health.service.HealthMonitoringService
import com.infinity.ai.health.service.MonitoringSnapshot
import com.infinity.ai.health.service.MonitoringState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * Utility to seed heavy, clinically realistic mock health data for demonstration and testing.
 *
 * Populates:
 * - Primary Patient and connected BLE Wearable records
 * - Over 1,400 chronologically sequenced vitals readings spanning 24 hours
 * - Diverse historical and active anomaly events with AI-authored explanations
 * - Up-to-date monitoring snapshot reflecting current state
 */
object MockHealthDataSeeder {

    data class SeedResult(
        val readingsCount: Int,
        val activeEventsCount: Int,
        val totalEventsCount: Int
    )

    suspend fun clearAll(context: Context): Unit = withContext(Dispatchers.IO) {
        val db = GoneDatabase.getInstance(context)
        val patientId = HealthMonitoringService.DEFAULT_PATIENT_ID
        db.vitalsDao().clearAll(patientId)
        db.anomalyDao().clearAll(patientId)
        db.deviceDao().clearForPatient(patientId)
    }

    suspend fun seed(context: Context): SeedResult = withContext(Dispatchers.IO) {
        val db = GoneDatabase.getInstance(context)
        val patientDao = db.patientDao()
        val deviceDao = db.deviceDao()
        val vitalsDao = db.vitalsDao()
        val anomalyDao = db.anomalyDao()

        val patientId = HealthMonitoringService.DEFAULT_PATIENT_ID
        val now = System.currentTimeMillis()

        // 1. Ensure Patient Record
        val patient = PatientEntity(
            id = patientId,
            name = "Alex Morgan",
            age = 44,
            sex = "Male",
            chronicConditions = "Mild Exercise-Induced Asthma, Borderline Hypertension",
            emergencyContactName = "Sarah Morgan (Spouse)",
            emergencyContactPhone = "+1 (555) 234-5678",
            createdAt = now - (7L * 24 * 60 * 60 * 1000)
        )
        patientDao.upsert(patient)

        // 2. Ensure Device Record
        val device = DeviceEntity(
            id = "sim-watch-01",
            patientId = patientId,
            displayName = "G-one BioPulse Watch",
            transport = "BLE",
            lastSeenAt = now,
            batteryPercent = 86,
            firmwareVersion = "v2.5.0-prod"
        )
        deviceDao.upsert(device)

        // 3. Clear old readings for a clean seed or keep existing?
        // We trim old readings to avoid collision and insert full 24h curve.
        vitalsDao.deleteOlderThan(patientId, now + 1000)

        // 4. Generate 1,440 Vitals Readings spanning the last 24h (1 per minute)
        val totalPoints = 1440
        val readings = ArrayList<VitalsReadingEntity>(totalPoints)

        for (i in (totalPoints - 1) downTo 0) {
            val t = now - (i * 60_000L)
            val progress = (totalPoints - 1 - i).toDouble() / totalPoints // 0.0 to 1.0 across 24h
            val diurnalWave = sin(progress * 2 * PI - PI / 2) // low at night, high afternoon

            // Base physiological parameters
            var hr = (72 + diurnalWave * 10 + sin(i * 0.15) * 3).toInt()
            var spo2 = (98 + sin(i * 0.08) * 1).toInt().coerceIn(94, 100)
            var temp = (36.6f + (diurnalWave * 0.3f).toFloat() + (sin(i * 0.1) * 0.1f).toFloat())
            var motion = (0.04f + (if (diurnalWave > 0) 0.12f else 0.01f) + (sin(i * 0.2) * 0.02f).toFloat()).coerceAtLeast(0.01f)
            var ambTemp = (22f + (diurnalWave * 6f).toFloat())
            var humidity = (50f - (diurnalWave * 8f).toFloat())
            var aqi = (35 + (diurnalWave * 15).toInt()).coerceAtLeast(15)

            // Episode A: Fever episode ~14h to ~11h ago (indices around 600 to 780)
            if (i in 660..840) {
                val epi = sin(((i - 660).toDouble() / 180.0) * PI).toFloat()
                temp += epi * 1.8f // climbs up to 38.6°C
                hr += (epi * 24).toInt() // HR responds to fever
                motion = 0.02f // resting in bed
            }

            // Episode B: Heat Stress episode ~10h to ~7h ago (indices around 420 to 600)
            if (i in 420..600) {
                val epi = sin(((i - 420).toDouble() / 180.0) * PI).toFloat()
                ambTemp += epi * 16.0f // outdoor temp 39-41°C
                humidity += epi * 25.0f // humidity 75%
                aqi += (epi * 65).toInt() // AQI 100+
                hr += (epi * 32).toInt() // HR up to 125 bpm
                temp += epi * 1.2f
                motion = 0.45f + epi * 0.3f // high activity
            }

            // Episode C: Hypoxia / Respiratory strain episode ~5h to ~3.5h ago (indices around 210 to 300)
            if (i in 210..300) {
                val epi = sin(((i - 210).toDouble() / 90.0) * PI).toFloat()
                spo2 = (98 - (epi * 9)).toInt().coerceIn(87, 99) // drops to 89%
                hr += (epi * 20).toInt()
                aqi += (epi * 30).toInt()
            }

            // Episode D: Tachycardia surge at rest ~45m to ~25m ago (indices around 25 to 45)
            if (i in 25..45) {
                val epi = sin(((i - 25).toDouble() / 20.0) * PI).toFloat()
                hr += (epi * 75).toInt() // spikes to 155-160 bpm
                motion = 0.02f // stationary!
            }

            // Clamp vitals to legal clinical ranges
            hr = hr.coerceIn(45, 185)
            spo2 = spo2.coerceIn(85, 100)
            temp = ((temp * 10).toInt() / 10f).coerceIn(35.2f, 41.5f)
            motion = ((motion * 100).toInt() / 100f).coerceIn(0.01f, 3.5f)
            ambTemp = ((ambTemp * 10).toInt() / 10f)
            humidity = ((humidity * 10).toInt() / 10f).coerceIn(15f, 99f)

            readings.add(
                VitalsReadingEntity(
                    patientId = patientId,
                    timestamp = t,
                    heartRate = hr,
                    spo2 = spo2,
                    bodyTempC = temp,
                    motionMagnitudeG = motion,
                    ambientTempC = ambTemp,
                    ambientHumidityPct = humidity,
                    aqi = aqi,
                    source = ReadingSource.SIMULATED.wireName
                )
            )
        }

        vitalsDao.insertAll(readings)

        // 5. Generate Anomaly Events (Active & Acknowledged)
        val events = listOf(
            // ── ACTIVE EVENTS (Needing attention) ───────────────────────────
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.HIGH_HEART_RATE.wireName,
                severity = Severity.CRITICAL.wireName,
                riskHeat = 15,
                riskRespiratory = 22,
                riskCardiovascular = 88,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.HIGH_HEART_RATE,
                    severity = Severity.CRITICAL,
                    triggeredAt = now - (35 * 60_000L),
                    ruleId = "cardiac.tachycardia.resting_critical",
                    riskScores = RiskScores(15, 22, 88),
                    heartRate = 158,
                    spo2 = 97,
                    bodyTempC = 36.8f,
                    motionDetected = false,
                    durationMinutes = 12,
                    trend = Trend.RISING
                ).toJson(),
                templateExplanation = "Resting heart rate reached 158 bpm with minimal motion (0.02g) for over 10 minutes.",
                aiExplanation = "Urgent: Significant resting tachycardia detected. Your heart rate climbed to 158 bpm without physical exertion. Please sit in a relaxed position, take slow deep breaths, and hydrate. If palpitations or dizziness accompany this, seek medical evaluation.",
                status = EventStatus.ACTIVE.wireName,
                createdAt = now - (35 * 60_000L)
            ),
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.RESPIRATORY_DISTRESS.wireName,
                severity = Severity.CRITICAL.wireName,
                riskHeat = 18,
                riskRespiratory = 85,
                riskCardiovascular = 64,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.RESPIRATORY_DISTRESS,
                    severity = Severity.CRITICAL,
                    triggeredAt = now - (3 * 3600_000L + 40 * 60_000L),
                    ruleId = "resp.hypoxia.sustained_deep",
                    riskScores = RiskScores(18, 85, 64),
                    heartRate = 104,
                    spo2 = 89,
                    bodyTempC = 37.1f,
                    aqi = 78,
                    durationMinutes = 15,
                    trend = Trend.FALLING
                ).toJson(),
                templateExplanation = "Blood oxygen dropped below critical threshold to 89% SpO2 accompanied by compensatory tachycardia.",
                aiExplanation = "Critical respiratory event: Sustained low blood oxygen (89% SpO2) detected with elevated pulse (104 bpm). Move to fresh air, remain in an upright seated posture, and monitor your breathing. If symptoms persist, seek emergency medical assistance.",
                status = EventStatus.ACTIVE.wireName,
                createdAt = now - (3 * 3600_000L + 40 * 60_000L)
            ),
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.HEAT_STRESS.wireName,
                severity = Severity.MODERATE.wireName,
                riskHeat = 76,
                riskRespiratory = 38,
                riskCardiovascular = 54,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.HEAT_STRESS,
                    severity = Severity.MODERATE,
                    triggeredAt = now - (8 * 3600_000L + 15 * 60_000L),
                    ruleId = "enviro.heat_strain.high_ambient",
                    riskScores = RiskScores(76, 38, 54),
                    heartRate = 126,
                    spo2 = 96,
                    bodyTempC = 38.3f,
                    ambientTempC = 40.2f,
                    ambientHumidityPct = 74.0f,
                    aqi = 102,
                    durationMinutes = 45,
                    trend = Trend.RISING
                ).toJson(),
                templateExplanation = "High ambient heat index (40.2°C, 74% humidity) combined with elevated body core temperature (38.3°C).",
                aiExplanation = "High heat strain warning: Severe ambient thermal conditions combined with rising body temperature (38.3°C) and elevated pulse (126 bpm) indicate heat exhaustion risk. Seek shelter in an air-conditioned room and drink water immediately.",
                status = EventStatus.ACTIVE.wireName,
                createdAt = now - (8 * 3600_000L + 15 * 60_000L)
            ),
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.BASELINE_DEVIATION.wireName,
                severity = Severity.LOW.wireName,
                riskHeat = 14,
                riskRespiratory = 18,
                riskCardiovascular = 32,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.BASELINE_DEVIATION,
                    severity = Severity.LOW,
                    triggeredAt = now - (15 * 60_000L),
                    ruleId = "baseline.cardiac_drift.mild",
                    riskScores = RiskScores(14, 18, 32),
                    heartRate = 84,
                    spo2 = 98,
                    bodyTempC = 36.9f,
                    baselineDeltaPct = 16.5f,
                    trend = Trend.STABLE
                ).toJson(),
                templateExplanation = "Resting cardiovascular metrics are 16.5% above your established 7-day personal baseline.",
                aiExplanation = "Mild physiological deviation: Your resting vital profile is slightly elevated compared to your typical baseline. This is common following periods of thermal or metabolic stress.",
                status = EventStatus.ACTIVE.wireName,
                createdAt = now - (15 * 60_000L)
            ),

            // ── ACKNOWLEDGED / HISTORICAL EVENTS ────────────────────────────
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.FALL_DETECTED.wireName,
                severity = Severity.CRITICAL.wireName,
                riskHeat = 0,
                riskRespiratory = 10,
                riskCardiovascular = 45,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.FALL_DETECTED,
                    severity = Severity.CRITICAL,
                    triggeredAt = now - (10 * 3600_000L),
                    ruleId = "motion.fall.impact_then_inactivity",
                    riskScores = RiskScores(0, 10, 45),
                    heartRate = 118,
                    bodyTempC = 36.8f,
                    motionDetected = false,
                    durationMinutes = 2
                ).toJson(),
                templateExplanation = "High impact acceleration (>2.8g) followed by immediate immobility detected.",
                aiExplanation = "Fall detected: A sudden impact force followed by a period of stillness was recorded. Emergency alert protocol was initiated and confirmed by user.",
                status = EventStatus.ACKNOWLEDGED.wireName,
                createdAt = now - (10 * 3600_000L),
                acknowledgedAt = now - (9 * 3600_000L + 50 * 60_000L)
            ),
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.FEVER.wireName,
                severity = Severity.MODERATE.wireName,
                riskHeat = 45,
                riskRespiratory = 20,
                riskCardiovascular = 40,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.FEVER,
                    severity = Severity.MODERATE,
                    triggeredAt = now - (12 * 3600_000L),
                    ruleId = "temp.fever.moderate",
                    riskScores = RiskScores(45, 20, 40),
                    heartRate = 98,
                    bodyTempC = 38.6f,
                    trend = Trend.RISING
                ).toJson(),
                templateExplanation = "Core body temperature sustained above 38.5°C threshold.",
                aiExplanation = "Moderate fever registered: Temperature peaked at 38.6°C with mild heart rate elevation. Rest and fluid intake recommended.",
                status = EventStatus.ACKNOWLEDGED.wireName,
                createdAt = now - (12 * 3600_000L),
                acknowledgedAt = now - (11 * 3600_000L + 30 * 60_000L)
            ),
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.CARDIOVASCULAR_STRAIN.wireName,
                severity = Severity.MODERATE.wireName,
                riskHeat = 30,
                riskRespiratory = 40,
                riskCardiovascular = 68,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.CARDIOVASCULAR_STRAIN,
                    severity = Severity.MODERATE,
                    triggeredAt = now - (9 * 3600_000L),
                    ruleId = "cardiac.strain.sustained_workload",
                    riskScores = RiskScores(30, 40, 68),
                    heartRate = 132,
                    bodyTempC = 37.9f,
                    durationMinutes = 30
                ).toJson(),
                templateExplanation = "Prolonged cardiovascular load with pulse sustained above 130 bpm.",
                aiExplanation = "Cardiovascular workload sustained above normal activity ranges for 30 consecutive minutes.",
                status = EventStatus.ACKNOWLEDGED.wireName,
                createdAt = now - (9 * 3600_000L),
                acknowledgedAt = now - (8 * 3600_000L + 45 * 60_000L)
            ),
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.DEHYDRATION_RISK.wireName,
                severity = Severity.MODERATE.wireName,
                riskHeat = 65,
                riskRespiratory = 25,
                riskCardiovascular = 55,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.DEHYDRATION_RISK,
                    severity = Severity.MODERATE,
                    triggeredAt = now - (7 * 3600_000L),
                    ruleId = "enviro.dehydration.combined_heat_activity",
                    riskScores = RiskScores(65, 25, 55),
                    heartRate = 115,
                    bodyTempC = 37.6f,
                    ambientTempC = 38.0f
                ).toJson(),
                templateExplanation = "Elevated thermal exposure and elevated resting heart rate suggest cumulative fluid loss.",
                aiExplanation = "Dehydration warning: Elevated heart rate paired with prolonged exposure to 38°C ambient heat.",
                status = EventStatus.ACKNOWLEDGED.wireName,
                createdAt = now - (7 * 3600_000L),
                acknowledgedAt = now - (6 * 3600_000L + 15 * 60_000L)
            ),
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.SUSTAINED_LOW_SPO2.wireName,
                severity = Severity.MODERATE.wireName,
                riskHeat = 10,
                riskRespiratory = 72,
                riskCardiovascular = 45,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.SUSTAINED_LOW_SPO2,
                    severity = Severity.MODERATE,
                    triggeredAt = now - (4 * 3600_000L + 30 * 60_000L),
                    ruleId = "resp.spo2.sustained_suboptimal",
                    riskScores = RiskScores(10, 72, 45),
                    heartRate = 96,
                    spo2 = 91,
                    durationMinutes = 20
                ).toJson(),
                templateExplanation = "Blood oxygen saturation held below 92% for 20 consecutive minutes.",
                aiExplanation = "Suboptimal blood oxygenation (91% SpO2) observed across multiple continuous sample intervals.",
                status = EventStatus.ACKNOWLEDGED.wireName,
                createdAt = now - (4 * 3600_000L + 30 * 60_000L),
                acknowledgedAt = now - (4 * 3600_000L)
            ),
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.FATIGUE.wireName,
                severity = Severity.LOW.wireName,
                riskHeat = 15,
                riskRespiratory = 20,
                riskCardiovascular = 25,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.FATIGUE,
                    severity = Severity.LOW,
                    triggeredAt = now - (15 * 3600_000L),
                    ruleId = "wellness.fatigue.dampened_hrv",
                    riskScores = RiskScores(15, 20, 25),
                    heartRate = 78,
                    bodyTempC = 36.5f
                ).toJson(),
                templateExplanation = "Motion pattern and pulse dynamics indicate physiological fatigue.",
                aiExplanation = "Fatigue markers identified from reduced movement velocity and elevated baseline pulse.",
                status = EventStatus.ACKNOWLEDGED.wireName,
                createdAt = now - (15 * 3600_000L),
                acknowledgedAt = now - (14 * 3600_000L + 30 * 60_000L)
            ),
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.LOW_HEART_RATE.wireName,
                severity = Severity.LOW.wireName,
                riskHeat = 5,
                riskRespiratory = 10,
                riskCardiovascular = 20,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.LOW_HEART_RATE,
                    severity = Severity.LOW,
                    triggeredAt = now - (21 * 3600_000L),
                    ruleId = "cardiac.bradycardia.nocturnal_mild",
                    riskScores = RiskScores(5, 10, 20),
                    heartRate = 48,
                    spo2 = 98,
                    bodyTempC = 36.3f,
                    motionDetected = false
                ).toJson(),
                templateExplanation = "Resting pulse dipped to 48 bpm during deep sleep window.",
                aiExplanation = "Nocturnal bradycardia noted during resting sleep cycle. Consistent with restful deep sleep recovery.",
                status = EventStatus.ACKNOWLEDGED.wireName,
                createdAt = now - (21 * 3600_000L),
                acknowledgedAt = now - (20 * 3600_000L + 15 * 60_000L)
            ),
            AnomalyEventEntity(
                patientId = patientId,
                eventType = AnomalyType.LOW_SPO2.wireName,
                severity = Severity.LOW.wireName,
                riskHeat = 5,
                riskRespiratory = 40,
                riskCardiovascular = 15,
                evidenceJson = AnomalyEvidence(
                    type = AnomalyType.LOW_SPO2,
                    severity = Severity.LOW,
                    triggeredAt = now - (17 * 3600_000L),
                    ruleId = "resp.spo2.transient_dip",
                    riskScores = RiskScores(5, 40, 15),
                    heartRate = 74,
                    spo2 = 93
                ).toJson(),
                templateExplanation = "Transient desaturation event (93% SpO2) resolved within 2 minutes.",
                aiExplanation = "Brief dip in oxygen saturation noted and quickly recovered to baseline levels.",
                status = EventStatus.ACKNOWLEDGED.wireName,
                createdAt = now - (17 * 3600_000L),
                acknowledgedAt = now - (16 * 3600_000L + 50 * 60_000L)
            )
        )

        for (event in events) {
            anomalyDao.insert(event)
        }

        // 6. Update HealthMonitoringService snapshot for immediate live dashboard display
        val latestReading = readings.last()
        val mockSnapshot = MonitoringSnapshot(
            state = MonitoringState.IDLE,
            latest = VitalsSample(
                timestamp = latestReading.timestamp,
                heartRate = latestReading.heartRate,
                spo2 = latestReading.spo2,
                bodyTempC = latestReading.bodyTempC,
                motionMagnitudeG = latestReading.motionMagnitudeG,
                ambientTempC = latestReading.ambientTempC,
                ambientHumidityPct = latestReading.ambientHumidityPct,
                aqi = latestReading.aqi
            ),
            risk = RiskScores(heat = 15, respiratory = 22, cardiovascular = 28),
            samplesProcessed = readings.size.toLong(),
            eventsRaised = events.size.toLong(),
            lastEventAt = now - (15 * 60_000L),
            baselineReady = true
        )
        HealthMonitoringService.updateSnapshot(mockSnapshot)

        val activeCount = events.count { it.status == EventStatus.ACTIVE.wireName }
        SeedResult(
            readingsCount = readings.size,
            activeEventsCount = activeCount,
            totalEventsCount = events.size
        )
    }
}
