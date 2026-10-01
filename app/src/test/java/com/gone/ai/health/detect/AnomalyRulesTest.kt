package com.gone.ai.health.detect

import com.gone.ai.health.TestVitals
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.PatientBaseline
import com.gone.ai.health.domain.RiskScores
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule is tested in isolation, both for firing and — just as importantly — for
 * staying silent. A health monitor that over-alerts trains its user to ignore it,
 * which is a worse failure than one that under-alerts, so the negative cases matter.
 */
class AnomalyRulesTest {

    private val t = AnomalyThresholds.DEFAULT

    private fun ctx(
        samples: List<VitalsSample>,
        baseline: PatientBaseline = PatientBaseline.EMPTY
    ) = DetectionContext(
        window = VitalsWindow.of(samples),
        baseline = baseline,
        thresholds = t,
        riskScores = RiskScores.ZERO,
        now = samples.lastOrNull()?.timestamp ?: TestVitals.T0
    )

    // ── SpO2 ──────────────────────────────────────────────────────────────────

    @Test
    fun `critical spo2 fires immediately with no duration requirement`() {
        val c = CriticalSpo2Rule.evaluate(ctx(listOf(TestVitals.sample(spo2 = 88))))
        assertNotNull(c)
        assertEquals(AnomalyType.LOW_SPO2, c!!.type)
        assertEquals(Severity.CRITICAL, c.severity)
        assertEquals("spo2.critical", c.evidence.ruleId)
        assertEquals(88, c.evidence.spo2)
    }

    @Test
    fun `critical spo2 stays silent above the threshold`() {
        assertNull(CriticalSpo2Rule.evaluate(ctx(listOf(TestVitals.sample(spo2 = 91)))))
        assertNull(CriticalSpo2Rule.evaluate(ctx(listOf(TestVitals.sample(spo2 = 97)))))
    }

    @Test
    fun `critical spo2 stays silent when the reading is missing`() {
        assertNull(CriticalSpo2Rule.evaluate(ctx(listOf(TestVitals.sample(hr = 80)))))
    }

    /** The exact case PS26181 names: SpO2 below 92% for 10+ minutes. */
    @Test
    fun `sustained spo2 fires after ten minutes below the threshold`() {
        val samples = TestVitals.series(11, intervalMinutes = 1.0, spo2From = 91)
        val c = SustainedSpo2Rule.evaluate(ctx(samples))
        assertNotNull(c)
        assertEquals(AnomalyType.SUSTAINED_LOW_SPO2, c!!.type)
        assertEquals(Severity.MODERATE, c.severity)
        assertEquals(10, c.evidence.durationMinutes)
    }

    @Test
    fun `sustained spo2 stays silent before the duration is met`() {
        val samples = TestVitals.series(6, intervalMinutes = 1.0, spo2From = 91)
        assertNull(SustainedSpo2Rule.evaluate(ctx(samples)))
    }

    /** Avoids two alerts for one reading; the critical rule owns that range. */
    @Test
    fun `sustained spo2 defers to the critical rule`() {
        val samples = TestVitals.series(11, intervalMinutes = 1.0, spo2From = 87)
        assertNull(SustainedSpo2Rule.evaluate(ctx(samples)))
    }

    // ── Heart rate ────────────────────────────────────────────────────────────

    @Test
    fun `high heart rate fires at rest`() {
        val c = HighHeartRateRule.evaluate(
            ctx(TestVitals.series(6, hrFrom = 135, motion = TestVitals.REST))
        )
        assertNotNull(c)
        assertEquals(Severity.MODERATE, c!!.severity)
    }

    /**
     * Motion awareness. Without it, every brisk walk produces an alert and the user
     * stops reading them.
     */
    @Test
    fun `high heart rate is ignored during movement at the moderate threshold`() {
        assertNull(
            HighHeartRateRule.evaluate(
                ctx(TestVitals.series(6, hrFrom = 135, motion = TestVitals.MOVING))
            )
        )
    }

    /** Genuinely dangerous rates still fire regardless of activity. */
    @Test
    fun `critical heart rate fires even during movement`() {
        val c = HighHeartRateRule.evaluate(
            ctx(TestVitals.series(6, hrFrom = 155, motion = TestVitals.MOVING))
        )
        assertNotNull(c)
        assertEquals(Severity.CRITICAL, c!!.severity)
    }

    @Test
    fun `low heart rate needs rest and persistence`() {
        val resting = TestVitals.series(5, hrFrom = 44, motion = TestVitals.REST)
        val c = LowHeartRateRule.evaluate(ctx(resting))
        assertNotNull(c)
        assertEquals(AnomalyType.LOW_HEART_RATE, c!!.type)
        assertEquals(Severity.MODERATE, c.severity)

        // A single low sample is almost always optical noise.
        assertNull(LowHeartRateRule.evaluate(ctx(TestVitals.series(2, hrFrom = 44))))
        // And a low reading mid-motion is an artifact.
        assertNull(
            LowHeartRateRule.evaluate(ctx(TestVitals.series(5, hrFrom = 44, motion = TestVitals.MOVING)))
        )
    }

    @Test
    fun `very low heart rate escalates to critical`() {
        val c = LowHeartRateRule.evaluate(ctx(TestVitals.series(5, hrFrom = 35)))
        assertEquals(Severity.CRITICAL, c!!.severity)
    }

    // ── Temperature ───────────────────────────────────────────────────────────

    @Test
    fun `fever tiers on temperature`() {
        assertNull(FeverRule.evaluate(ctx(listOf(TestVitals.sample(temp = 37.2f)))))

        val moderate = FeverRule.evaluate(ctx(listOf(TestVitals.sample(temp = 38.4f))))
        assertEquals(Severity.MODERATE, moderate!!.severity)

        val critical = FeverRule.evaluate(ctx(listOf(TestVitals.sample(temp = 39.8f))))
        assertEquals(Severity.CRITICAL, critical!!.severity)
    }

    // ── Environment-coupled ───────────────────────────────────────────────────

    /**
     * Heat plus a physiological response. Environmental heat on its own is a weather
     * forecast, not a health event.
     */
    @Test
    fun `heat stress requires both hot conditions and a body response`() {
        // Humidity omitted so the rule falls back to air temperature and the tier is
        // deterministic rather than dependent on the heat-index curve.
        val hotAndResponding = TestVitals.series(
            8, hrFrom = 88, tempFrom = 37.8f, ambientTemp = 41f, motion = TestVitals.REST
        )
        val c = HeatStressRule.evaluate(ctx(hotAndResponding))
        assertNotNull(c)
        assertEquals(AnomalyType.HEAT_STRESS, c!!.type)
        assertEquals(Severity.MODERATE, c.severity)
    }

    @Test
    fun `heat stress stays silent in hot conditions with no body response`() {
        val hotButCoping = TestVitals.series(
            8, hrFrom = 74, tempFrom = 36.7f, ambientTemp = 41f, motion = TestVitals.REST
        )
        assertNull(HeatStressRule.evaluate(ctx(hotButCoping)))
    }

    @Test
    fun `heat stress stays silent when it is not hot`() {
        val feverishButCool = TestVitals.series(
            8, hrFrom = 110, tempFrom = 38.5f, ambientTemp = 22f
        )
        assertNull(HeatStressRule.evaluate(ctx(feverishButCool)))
    }

    @Test
    fun `heat stress stays silent with no environmental data at all`() {
        assertNull(HeatStressRule.evaluate(ctx(TestVitals.series(8, hrFrom = 110, tempFrom = 38.5f))))
    }

    @Test
    fun `dehydration risk needs heat plus rising heart rate and rising temperature`() {
        val ramping = TestVitals.series(
            10, hrFrom = 80, hrTo = 104, tempFrom = 37.0f, tempTo = 37.9f,
            ambientTemp = 42f, motion = TestVitals.REST
        )
        val c = DehydrationRiskRule.evaluate(ctx(ramping))
        assertNotNull(c)
        assertEquals(AnomalyType.DEHYDRATION_RISK, c!!.type)
        // Capped below CRITICAL on purpose: hydration is inferred, never measured.
        assertTrue(c.severity.rank < Severity.CRITICAL.rank)
    }

    @Test
    fun `dehydration risk stays silent when vitals are flat`() {
        val flat = TestVitals.series(
            10, hrFrom = 90, tempFrom = 37.2f, ambientTemp = 42f, motion = TestVitals.REST
        )
        assertNull(DehydrationRiskRule.evaluate(ctx(flat)))
    }

    @Test
    fun `respiratory distress fires on poor air with reduced oxygen`() {
        val c = RespiratoryDistressRule.evaluate(
            ctx(TestVitals.series(8, hrFrom = 90, spo2From = 93, aqi = 350))
        )
        assertNotNull(c)
        assertEquals(AnomalyType.RESPIRATORY_DISTRESS, c!!.type)
        assertEquals(Severity.MODERATE, c.severity)
    }

    @Test
    fun `respiratory distress fires on the falling-oxygen rising-HR coupling`() {
        val c = RespiratoryDistressRule.evaluate(
            ctx(TestVitals.series(10, hrFrom = 88, hrTo = 108, spo2From = 96, spo2To = 92))
        )
        assertNotNull(c)
    }

    @Test
    fun `respiratory distress stays silent on good air and stable oxygen`() {
        assertNull(
            RespiratoryDistressRule.evaluate(
                ctx(TestVitals.series(10, hrFrom = 80, spo2From = 97, aqi = 60))
            )
        )
    }

    /** Cross-signal correlation is the point; neither signal alone should fire it. */
    @Test
    fun `cardiovascular strain requires the cross-signal pattern at rest`() {
        val coupled = TestVitals.series(
            10, hrFrom = 100, hrTo = 122, spo2From = 97, spo2To = 91, motion = TestVitals.REST
        )
        val c = CardiovascularStrainRule.evaluate(ctx(coupled))
        assertNotNull(c)
        assertEquals(AnomalyType.CARDIOVASCULAR_STRAIN, c!!.type)
        assertEquals(Severity.MODERATE, c.severity)
    }

    @Test
    fun `cardiovascular strain stays silent when only heart rate rises`() {
        assertNull(
            CardiovascularStrainRule.evaluate(
                ctx(TestVitals.series(10, hrFrom = 100, hrTo = 122, spo2From = 97))
            )
        )
    }

    @Test
    fun `cardiovascular strain stays silent during movement`() {
        assertNull(
            CardiovascularStrainRule.evaluate(
                ctx(
                    TestVitals.series(
                        10, hrFrom = 100, hrTo = 122, spo2From = 97, spo2To = 91,
                        motion = TestVitals.MOVING
                    )
                )
            )
        )
    }

    @Test
    fun `cardiovascular strain stays silent inside the normal resting band`() {
        // Rising, falling, at rest — but the HR never leaves normal territory.
        assertNull(
            CardiovascularStrainRule.evaluate(
                ctx(TestVitals.series(10, hrFrom = 62, hrTo = 78, spo2From = 98, spo2To = 95))
            )
        )
    }

    // ── Motion ────────────────────────────────────────────────────────────────

    /** A very high impact raises the alert on that packet, without waiting for stillness. */
    @Test
    fun `fall detection raises a critical alert immediately on a high impact`() {
        val samples = listOf(
            TestVitals.sample(0.0, hr = 84, motion = 1.05f),
            TestVitals.sample(0.2, hr = 84, motion = 5.4f)
        )
        val c = FallDetectionRule.evaluate(ctx(samples))
        assertNotNull(c)
        assertEquals(AnomalyType.FALL_DETECTED, c!!.type)
        assertEquals(Severity.CRITICAL, c.severity)
        assertEquals(5.4f, c.evidence.fallImpactG)
    }

    /** Only the newest packet can trigger, so an old impact cannot repeat later. */
    @Test
    fun `fall detection does not repeat an earlier impact on a later motion packet`() {
        val samples = listOf(
            TestVitals.sample(0.0, hr = 84, motion = 1.05f),
            TestVitals.sample(0.2, hr = 84, motion = 5.4f),
            TestVitals.sample(0.4, hr = 96, motion = 1.5f),
            TestVitals.sample(0.6, hr = 96, motion = 1.6f)
        )
        assertNull(FallDetectionRule.evaluate(ctx(samples)))
    }

    @Test
    fun `fall detection stays silent on ordinary movement`() {
        assertNull(FallDetectionRule.evaluate(ctx(TestVitals.series(10, hrFrom = 90, motion = 1.4f))))
    }

    @Test
    fun `smaller knocks followed by stillness do not raise a fall`() {
        for (peak in listOf(2.5f, 3.4f, 4.99f)) {
            val samples = listOf(TestVitals.sample(0.0, motion = peak), TestVitals.sample(1.0, motion = 1f))
            assertNull(FallDetectionRule.evaluate(ctx(samples)))
        }
    }

    @Test
    fun `missing motion after a high impact is not proof of immobility`() {
        val samples = listOf(TestVitals.sample(0.0, motion = 5f), TestVitals.sample(1.0).copy(motionMagnitudeG = null))
        assertNull(FallDetectionRule.evaluate(ctx(samples)))
    }

    @Test
    fun `impact alone raises immediately and a later still packet does not repeat it`() {
        val impact = TestVitals.sample(0.0, motion = 5.4f)
        assertEquals(Severity.CRITICAL, FallDetectionRule.evaluate(ctx(listOf(impact)))?.severity)
        assertNull(FallDetectionRule.evaluate(ctx(listOf(impact, TestVitals.sample(0.25, motion = 1.0f)))))
        assertNull(FallDetectionRule.evaluate(ctx(listOf(impact, TestVitals.sample(0.5, motion = 1.0f)))))
    }

    /**
     * One fall must be reported once. Without a lookback the impact stayed in the
     * 30-minute window and fired again as a "new" fall when the cooldown expired.
     */
    @Test
    fun `fall detection ignores an impact older than the lookback`() {
        val samples = mutableListOf<VitalsSample>()
        samples += TestVitals.sample(0.0, hr = 84, motion = 5.4f)      // impact
        for (i in 1..14) {
            samples += TestVitals.sample(i * 0.5, hr = 88, motion = 0.99f)   // until minute 7
        }
        assertNull(FallDetectionRule.evaluate(ctx(samples)))
    }

    @Test
    fun `fatigue is informational only`() {
        val samples = TestVitals.series(25, intervalMinutes = 1.0, hrFrom = 100, motion = TestVitals.REST)
        val c = FatigueRule.evaluate(ctx(samples))
        assertNotNull(c)
        assertEquals(AnomalyType.FATIGUE, c!!.type)
        assertEquals(Severity.LOW, c.severity)
    }

    /** Once tachycardia would fire on its own, fatigue must step aside. */
    @Test
    fun `fatigue defers to the tachycardia rule`() {
        val samples = TestVitals.series(25, intervalMinutes = 1.0, hrFrom = 135, motion = TestVitals.REST)
        assertNull(FatigueRule.evaluate(ctx(samples)))
    }

    // ── Baseline deviation, the early-warning rule ────────────────────────────

    /**
     * The signal no fixed threshold can see: everything is inside the healthy range,
     * but well away from this person's own normal.
     */
    @Test
    fun `baseline deviation fires while all vitals remain nominally normal`() {
        val samples = TestVitals.series(10, hrFrom = 86, spo2From = 96, motion = TestVitals.REST)
        val baseline = PatientBaseline(restingHeartRate = 68f, typicalSpo2 = 97f, sampleCount = 120)

        // Confirm the premise: no fixed-threshold rule sees anything here.
        assertNull(HighHeartRateRule.evaluate(ctx(samples, baseline)))
        assertNull(CriticalSpo2Rule.evaluate(ctx(samples, baseline)))
        assertNull(SustainedSpo2Rule.evaluate(ctx(samples, baseline)))

        val c = BaselineDeviationRule.evaluate(ctx(samples, baseline))
        assertNotNull("the early-warning rule should catch this", c)
        assertEquals(AnomalyType.BASELINE_DEVIATION, c!!.type)
        assertTrue("delta should be recorded", c.evidence.baselineDeltaPct != null)
        assertTrue(c.evidence.baselineDeltaPct!! > 20f)
    }

    @Test
    fun `baseline deviation escalates on a large departure`() {
        val samples = TestVitals.series(10, hrFrom = 105, spo2From = 96, motion = TestVitals.REST)
        val baseline = PatientBaseline(restingHeartRate = 65f, sampleCount = 120)
        val c = BaselineDeviationRule.evaluate(ctx(samples, baseline))
        assertEquals(Severity.MODERATE, c!!.severity)
    }

    /** A baseline built from a handful of readings is noise, not a reference. */
    @Test
    fun `baseline deviation refuses to act on an unreliable baseline`() {
        val samples = TestVitals.series(10, hrFrom = 90, motion = TestVitals.REST)
        val thin = PatientBaseline(restingHeartRate = 65f, sampleCount = 4)
        assertNull(BaselineDeviationRule.evaluate(ctx(samples, thin)))
    }

    @Test
    fun `baseline deviation refuses to compare during movement`() {
        val samples = TestVitals.series(10, hrFrom = 90, motion = TestVitals.MOVING)
        val baseline = PatientBaseline(restingHeartRate = 65f, sampleCount = 120)
        assertNull(BaselineDeviationRule.evaluate(ctx(samples, baseline)))
    }

    @Test
    fun `baseline deviation also catches an oxygen drop from personal normal`() {
        val samples = TestVitals.series(10, hrFrom = 68, spo2From = 93, motion = TestVitals.REST)
        val baseline = PatientBaseline(restingHeartRate = 68f, typicalSpo2 = 98f, sampleCount = 120)
        val c = BaselineDeviationRule.evaluate(ctx(samples, baseline))
        assertNotNull(c)
    }

    @Test
    fun `baseline deviation stays silent when the patient is at their own normal`() {
        val samples = TestVitals.series(10, hrFrom = 69, spo2From = 97, motion = TestVitals.REST)
        val baseline = PatientBaseline(restingHeartRate = 68f, typicalSpo2 = 97f, sampleCount = 120)
        assertNull(BaselineDeviationRule.evaluate(ctx(samples, baseline)))
    }

    // ── Registry ──────────────────────────────────────────────────────────────

    @Test
    fun `rule ids are unique and stable`() {
        val ids = AnomalyRules.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(14, AnomalyRules.ALL.size)
    }

    /** Every rule must tolerate a window with nothing in it. */
    @Test
    fun `no rule throws on an empty window`() {
        val empty = DetectionContext(
            window = VitalsWindow.EMPTY,
            baseline = PatientBaseline.EMPTY,
            thresholds = t,
            riskScores = RiskScores.ZERO,
            now = TestVitals.T0
        )
        AnomalyRules.ALL.forEach { rule ->
            assertNull("${rule.id} fired on an empty window", rule.evaluate(empty))
        }
    }

    // ── Muscle activity (EMG) ─────────────────────────────────────────────────

    /** Readings every 5 s, the storage cadence, with the given EMG envelope values. */
    private fun emgSeries(vararg levels: Int, motion: Float = TestVitals.REST) =
        levels.mapIndexed { i, level ->
            VitalsSample(
                timestamp = TestVitals.T0 + i * 5_000L,
                emgMean = level,
                emgMax = level,
                motionMagnitudeG = motion
            )
        }

    @Test
    fun `sustained high muscle activity at rest raises a LOW finding`() {
        val c = SustainedMuscleActivityRule.evaluate(ctx(emgSeries(300, 2900, 3000, 2950)))
        assertNotNull(c)
        assertEquals(AnomalyType.SUSTAINED_MUSCLE_ACTIVITY, c!!.type)
        assertEquals(Severity.LOW, c.severity)
        assertEquals(2950, c.evidence.emgLevel)
    }

    @Test
    fun `very high muscle activity held at rest raises MODERATE and never CRITICAL`() {
        val c = SustainedMuscleActivityRule.evaluate(ctx(emgSeries(3000, 3700, 3800, 3750, 4000)))
        assertEquals(Severity.MODERATE, c!!.severity)
    }

    @Test
    fun `a brief contraction is not sustained`() {
        // Two high readings: under the three-sample, ten-second requirement.
        assertNull(SustainedMuscleActivityRule.evaluate(ctx(emgSeries(300, 300, 3000, 3100))))
    }

    @Test
    fun `a relaxed muscle stays silent`() {
        assertNull(SustainedMuscleActivityRule.evaluate(ctx(emgSeries(250, 300, 280, 310, 260))))
    }

    /** Exercise is supposed to show a high envelope. */
    @Test
    fun `high muscle activity while moving stays silent`() {
        assertNull(
            SustainedMuscleActivityRule.evaluate(ctx(emgSeries(3000, 3100, 3050, 3000, motion = TestVitals.MOVING)))
        )
    }

    /** A detached electrode pins the ADC at its rail. That is a sensor problem, not a muscle. */
    @Test
    fun `an input pinned at the rail never reads as muscle activity`() {
        assertNull(SustainedMuscleActivityRule.evaluate(ctx(emgSeries(4095, 4095, 4095, 4095))))
        assertNull(SustainedMuscleActivityRule.evaluate(ctx(emgSeries(3000, 3000, 3000, 4095))))
    }

    @Test
    fun `a rail reading breaks a streak rather than extending it`() {
        assertNull(SustainedMuscleActivityRule.evaluate(ctx(emgSeries(3000, 4095, 3000, 3000))))
    }

    /** Only the muscle finding carries an EMG number, so other prompts cannot quote one. */
    @Test
    fun `other findings do not carry the EMG level`() {
        val samples = listOf(TestVitals.sample(spo2 = 88).copy(emgMean = 2000, emgMax = 2000))
        assertNull(CriticalSpo2Rule.evaluate(ctx(samples))!!.evidence.emgLevel)
    }

    /**
     * The user chose to treat a skin sensor as skin, not core. A skin reading of any value
     * must never produce a fever, whatever else is in the sample.
     */
    @Test
    fun `skin temperature never triggers the fever rule`() {
        val samples = (0 until 10).map {
            TestVitals.sample(atMinute = it.toDouble(), hr = 80).copy(skinTempC = 41.5f)
        }
        assertNull(FeverRule.evaluate(ctx(samples)))
        val detector = AnomalyDetector(t)
        val result = detector.evaluate(samples, now = samples.last().timestamp)
        assertTrue(result.candidates.none { it.type == AnomalyType.FEVER || it.type == AnomalyType.HEAT_STRESS })
        assertTrue(result.candidates.any { it.type == AnomalyType.HIGH_SKIN_TEMPERATURE })
    }

    @Test
    fun `high skin temperature alerts from the latest wearable packet`() {
        val moderate = HighSkinTemperatureRule.evaluate(
            ctx(listOf(TestVitals.sample(0.0).copy(skinTempC = 37.5f)))
        )
        assertEquals(AnomalyType.HIGH_SKIN_TEMPERATURE, moderate?.type)
        assertEquals(Severity.MODERATE, moderate?.severity)
        assertEquals(37.5f, moderate?.evidence?.skinTempC)

        val critical = HighSkinTemperatureRule.evaluate(
            ctx(listOf(TestVitals.sample(0.0).copy(skinTempC = 39.0f)))
        )
        assertEquals(Severity.CRITICAL, critical?.severity)
    }

    @Test
    fun `normal skin temperature stays silent`() {
        assertNull(
            HighSkinTemperatureRule.evaluate(
                ctx(listOf(TestVitals.sample(0.0).copy(skinTempC = 35.5f)))
            )
        )
    }

    /** And a sample where every field is absent. */
    @Test
    fun `no rule throws when all fields are missing`() {
        val blank = ctx(listOf(VitalsSample(timestamp = TestVitals.T0)))
        AnomalyRules.ALL.forEach { rule ->
            rule.evaluate(blank)   // must not throw
        }
    }
}
