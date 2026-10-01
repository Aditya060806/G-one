package com.gone.ai.health.data

import com.gone.ai.health.TestVitals
import com.gone.ai.health.detect.AnomalyRules
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.explain.ExplanationTemplates
import com.gone.ai.health.explain.HealthPromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demo data is shown to people as if it were the product working. It must obey the same
 * rules the real pipeline obeys, or the demo contradicts every safety claim the app makes.
 */
class MockHealthDataSeederTest {

    private val now = TestVitals.T0
    private val events = MockHealthDataSeeder.demoEvents(now)

    /** Which type each rule produces. Kept complete by the first test. */
    private val typeByRule = mapOf(
        "spo2.critical"        to AnomalyType.LOW_SPO2,
        "spo2.sustained"       to AnomalyType.SUSTAINED_LOW_SPO2,
        "hr.high"              to AnomalyType.HIGH_HEART_RATE,
        "hr.low"               to AnomalyType.LOW_HEART_RATE,
        "temp.fever"           to AnomalyType.FEVER,
        "temp.skinHigh"        to AnomalyType.HIGH_SKIN_TEMPERATURE,
        "env.heatStress"       to AnomalyType.HEAT_STRESS,
        "env.dehydrationRisk"  to AnomalyType.DEHYDRATION_RISK,
        "resp.distress"        to AnomalyType.RESPIRATORY_DISTRESS,
        "cardio.strain"        to AnomalyType.CARDIOVASCULAR_STRAIN,
        "motion.fall"          to AnomalyType.FALL_DETECTED,
        "wellness.fatigue"     to AnomalyType.FATIGUE,
        "baseline.deviation"   to AnomalyType.BASELINE_DEVIATION,
        "emg.sustainedHigh"    to AnomalyType.SUSTAINED_MUSCLE_ACTIVITY
    )

    /** Severities each rule can actually emit, read from AnomalyRules. */
    private val severitiesByRule = mapOf(
        "spo2.critical"        to setOf(Severity.CRITICAL),
        "spo2.sustained"       to setOf(Severity.MODERATE),
        "hr.high"              to setOf(Severity.MODERATE, Severity.CRITICAL),
        "hr.low"               to setOf(Severity.MODERATE, Severity.CRITICAL),
        "temp.fever"           to setOf(Severity.MODERATE, Severity.CRITICAL),
        "temp.skinHigh"        to setOf(Severity.MODERATE, Severity.CRITICAL),
        "env.heatStress"       to setOf(Severity.LOW, Severity.MODERATE, Severity.CRITICAL),
        "env.dehydrationRisk"  to setOf(Severity.LOW, Severity.MODERATE),
        "resp.distress"        to setOf(Severity.LOW, Severity.MODERATE, Severity.CRITICAL),
        "cardio.strain"        to setOf(Severity.LOW, Severity.MODERATE),
        "motion.fall"          to setOf(Severity.CRITICAL),
        "wellness.fatigue"     to setOf(Severity.LOW),
        "baseline.deviation"   to setOf(Severity.LOW, Severity.MODERATE),
        "emg.sustainedHigh"    to setOf(Severity.LOW, Severity.MODERATE)
    )

    @Test
    fun `the rule tables in this test cover every real rule`() {
        val realIds = AnomalyRules.ALL.map { it.id }.toSet()
        assertEquals(realIds, typeByRule.keys)
        assertEquals(realIds, severitiesByRule.keys)
    }

    @Test
    fun `every demo event cites a real rule that produces its type`() {
        events.forEach { e ->
            val expectedType = typeByRule[e.evidence.ruleId]
            assertTrue("unknown rule id ${e.evidence.ruleId}", expectedType != null)
            assertEquals("rule ${e.evidence.ruleId}", expectedType, e.evidence.type)
        }
    }

    @Test
    fun `demo severities are ones the rule can produce`() {
        events.forEach { e ->
            val allowed = severitiesByRule.getValue(e.evidence.ruleId)
            assertTrue(
                "${e.evidence.ruleId} cannot produce ${e.evidence.severity}",
                e.evidence.severity in allowed
            )
        }
    }

    @Test
    fun `demo alerts carry the real deterministic explanation`() {
        events.forEach { e ->
            val entity = e.toEntity("p", now)
            assertEquals(ExplanationTemplates.render(e.evidence).full, entity.templateExplanation)
            assertEquals(e.evidence.toJson(), entity.evidenceJson)
            assertEquals(e.evidence.riskScores, entity.riskScores())
        }
    }

    /** The whole point: demo "AI" text must be text the validator would have allowed. */
    @Test
    fun `demo AI rewrites pass the same validation as model output`() {
        val rewrites = events.filter { it.aiRewrite != null }
        assertTrue("the demo should show some rewritten alerts", rewrites.isNotEmpty())

        rewrites.forEach { e ->
            val rewrite = e.aiRewrite!!
            assertEquals(
                "rejected by validation: \"$rewrite\"",
                rewrite, HealthPromptBuilder.validate(rewrite, e.evidence)
            )
            val stored = e.toEntity("p", now).aiExplanation!!
            val tier = ExplanationTemplates.render(e.evidence).tier
            assertEquals("$rewrite\n\n${tier.guidance}", stored)
        }
    }

    @Test
    fun `template-only demo alerts have no AI text`() {
        events.filter { it.aiRewrite == null }.forEach { assertNull(it.toEntity("p", now).aiExplanation) }
    }

    @Test
    fun `acknowledgement never lies in the future`() {
        events.forEach { e ->
            val entity = e.toEntity("p", now)
            entity.acknowledgedAt?.let { assertTrue(it in entity.createdAt..now) }
            assertEquals(e.acknowledgedAfterMinutes == null, entity.statusEnum() == EventStatus.ACTIVE)
        }
    }

    /** Readings use the engine's motion scale, so charts and rules read them correctly. */
    @Test
    fun `demo readings are simulated and use the engine motion scale`() {
        val readings = MockHealthDataSeeder.demoReadings("p", now)
        val t = AnomalyThresholds.DEFAULT

        assertEquals(1_440, readings.size)
        assertTrue(readings.all { it.source == ReadingSource.SIMULATED.wireName })
        assertTrue("chronological", readings.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })

        val motions = readings.mapNotNull { it.motionMagnitudeG }
        assertTrue("motion is in g, ~1.0 at rest", motions.all { it >= 0.9f })
        assertEquals("exactly one impact", 1, motions.count { it >= t.fallImpactG })
        assertTrue(
            "most of the day reads as at rest",
            motions.count { it <= VitalsSample.REST_MOTION_G } > readings.size / 2
        )
    }
}
