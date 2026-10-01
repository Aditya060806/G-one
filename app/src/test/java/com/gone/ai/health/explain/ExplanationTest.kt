package com.gone.ai.health.explain

import com.gone.ai.health.domain.AnomalyEvidence
import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.RiskScores
import com.gone.ai.health.domain.Severity
import com.gone.ai.health.domain.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseTierTest {

    /**
     * Severity maps to tier without exception. Deliberately not per-type, so no future
     * rule can quietly downgrade the urgency of a critical finding.
     */
    @Test
    fun `severity maps one to one onto tiers`() {
        assertEquals(ResponseTier.MONITOR, ResponseTier.forSeverity(Severity.LOW))
        assertEquals(ResponseTier.CONTACT_DOCTOR, ResponseTier.forSeverity(Severity.MODERATE))
        assertEquals(ResponseTier.SEEK_IMMEDIATE_CARE, ResponseTier.forSeverity(Severity.CRITICAL))
    }

    @Test
    fun `every tier carries usable guidance`() {
        ResponseTier.entries.forEach {
            assertTrue("${it.name} label", it.label.isNotBlank())
            assertTrue("${it.name} guidance too short", it.guidance.length > 40)
        }
    }

    /** The critical tier must actually tell someone to act now. */
    @Test
    fun `immediate care guidance conveys urgency and names emergency services`() {
        val g = ResponseTier.SEEK_IMMEDIATE_CARE.guidance.lowercase()
        assertTrue(g.contains("do not wait"))
        assertTrue(g.contains("emergency"))
    }
}

class ExplanationTemplatesTest {

    private fun evidence(
        type: AnomalyType,
        severity: Severity = Severity.MODERATE,
        spo2: Int? = 91,
        hr: Int? = 108,
        temp: Float? = 37.6f,
        skinTemp: Float? = 38.2f,
        duration: Int? = 12,
        trend: Trend? = Trend.FALLING,
        ambientTemp: Float? = 41f,
        humidity: Float? = 62f,
        aqi: Int? = 260,
        baselineDelta: Float? = 24f,
        motion: Boolean? = false
    ) = AnomalyEvidence(
        type = type,
        severity = severity,
        triggeredAt = 1_700_000_000_000L,
        ruleId = "test.rule",
        riskScores = RiskScores(40, 55, 35),
        heartRate = hr,
        spo2 = spo2,
        bodyTempC = temp,
        skinTempC = skinTemp,
        ambientTempC = ambientTemp,
        ambientHumidityPct = humidity,
        aqi = aqi,
        durationMinutes = duration,
        trend = trend,
        baselineDeltaPct = baselineDelta,
        motionDetected = motion,
        sampleCount = 20
    )

    /**
     * Exhaustive coverage. The `when` in render has no else branch, so this also
     * guards against a future anomaly type shipping with a blank explanation.
     */
    @Test
    fun `every anomaly type renders a complete explanation`() {
        AnomalyType.entries.forEach { type ->
            val e = ExplanationTemplates.render(evidence(type))
            assertTrue("$type headline blank", e.headline.isNotBlank())
            assertTrue("$type detail too short: '${e.detail}'", e.detail.length > 60)
            assertNotNull("$type tier", e.tier)
            assertEquals("$type rule id", "test.rule", e.ruleId)
            assertTrue("$type full text should include the tier guidance",
                e.full.contains(e.tier.guidance))
        }
    }

    /** A sentence containing "null" would destroy trust instantly. */
    @Test
    fun `no template leaks null or placeholder text when values are present`() {
        AnomalyType.entries.forEach { type ->
            val e = ExplanationTemplates.render(evidence(type))
            assertFalse("$type leaked null: ${e.detail}", e.detail.contains("null", ignoreCase = true))
            // A bare % is legitimate ("89%"); an unsubstituted format token is not.
            assertFalse(
                "$type leaked a format token: ${e.detail}",
                Regex("%[sdfx]|%\\d+\\$").containsMatchIn(e.detail)
            )
            assertFalse("$type leaked a dangling brace", e.detail.contains("{"))
        }
    }

    /**
     * The harder case: a minimal wearable reporting almost nothing. Sentences must
     * still read correctly with every optional clause omitted.
     */
    @Test
    fun `templates survive completely absent optional values`() {
        AnomalyType.entries.forEach { type ->
            val bare = AnomalyEvidence(
                type = type,
                severity = Severity.LOW,
                triggeredAt = 1_700_000_000_000L,
                ruleId = "bare",
                riskScores = RiskScores.ZERO
            )
            val e = ExplanationTemplates.render(bare)
            assertTrue("$type headline blank", e.headline.isNotBlank())
            assertTrue("$type detail blank", e.detail.isNotBlank())
            assertFalse("$type leaked null: ${e.detail}", e.detail.contains("null", ignoreCase = true))
            // No double spaces or stranded punctuation from an omitted clause.
            assertFalse("$type has a double space: '${e.detail}'", e.detail.contains("  "))
            assertFalse("$type has a stranded comma", e.detail.contains(" ,"))
            assertFalse("$type has a stranded period", e.detail.contains(" ."))
        }
    }

    @Test
    fun `severity drives the tier in the rendered output`() {
        val low = ExplanationTemplates.render(evidence(AnomalyType.FATIGUE, Severity.LOW))
        assertEquals(ResponseTier.MONITOR, low.tier)

        val critical = ExplanationTemplates.render(evidence(AnomalyType.LOW_SPO2, Severity.CRITICAL))
        assertEquals(ResponseTier.SEEK_IMMEDIATE_CARE, critical.tier)
    }

    @Test
    fun `measured values appear in the text`() {
        val e = ExplanationTemplates.render(
            evidence(AnomalyType.LOW_SPO2, Severity.CRITICAL, spo2 = 87, hr = 121)
        )
        assertTrue("SpO2 missing from '${e.detail}'", e.detail.contains("87"))
        assertTrue("HR missing from '${e.detail}'", e.detail.contains("121"))
    }

    /**
     * Locale pinning. With the default-locale overload a device set to German would
     * render 101.7 as "101,7" — a comma decimal inside a clinical reading.
     */
    @Test
    fun `body temperature always uses a decimal point regardless of locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val e = ExplanationTemplates.render(evidence(AnomalyType.FEVER, temp = 38.7f))
            assertTrue("expected Fahrenheit value in '${e.detail}'", e.detail.contains("101.7 °F"))
            assertFalse("comma decimal leaked", e.detail.contains("101,7"))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    /** Dehydration is inferred, not measured, and the text must admit that. */
    @Test
    fun `dehydration explanation discloses that hydration is not measured`() {
        val e = ExplanationTemplates.render(evidence(AnomalyType.DEHYDRATION_RISK))
        assertTrue(e.detail.lowercase().contains("cannot measure"))
    }

    /** No template may name a disease; that would be a diagnosis. */
    @Test
    fun `no template names a specific medical condition`() {
        val forbidden = listOf(
            "pneumonia", "covid", "asthma attack", "heart attack", "myocardial",
            "sepsis", "diabetes", "hypoxemia", "arrhythmia", "stroke"
        )
        AnomalyType.entries.forEach { type ->
            val text = ExplanationTemplates.render(evidence(type)).detail.lowercase()
            forbidden.forEach { word ->
                assertFalse("$type named '$word'", text.contains(word))
            }
        }
    }

    @Test
    fun `short form is compact enough for a list row`() {
        val e = ExplanationTemplates.render(evidence(AnomalyType.LOW_SPO2, Severity.CRITICAL))
        assertTrue(e.short.length < 80)
        assertTrue(e.short.contains(e.headline))
    }
}

class HealthPromptBuilderTest {

    private val evidence = AnomalyEvidence(
        type = AnomalyType.LOW_SPO2,
        severity = Severity.CRITICAL,
        triggeredAt = 1_700_000_000_000L,
        ruleId = "spo2.critical",
        riskScores = RiskScores(20, 78, 44),
        heartRate = 108,
        spo2 = 89,
        bodyTempC = 37.4f,
        durationMinutes = 12,
        trend = Trend.FALLING
    )

    private val template = ExplanationTemplates.render(evidence)

    // ── System prompt ─────────────────────────────────────────────────────────

    @Test
    fun `system prompt states every hard constraint`() {
        val p = HealthPromptBuilder.SYSTEM_PROMPT.lowercase()
        assertTrue("must forbid diagnosis", p.contains("do not diagnose"))
        assertTrue("must forbid inventing numbers", p.contains("invent"))
        assertTrue("must forbid changing severity", p.contains("severity"))
        assertTrue("must forbid advice", p.contains("advice"))
        assertTrue("must forbid false certainty", p.contains("certainty"))
        assertTrue("must give a safe fallback", p.contains("unclear"))
    }

    /**
     * Including the deterministic output gives the small model a correct answer to
     * imitate, which is the main defence against it drifting into diagnosis.
     */
    @Test
    fun `user prompt carries both the evidence json and the template as a reference`() {
        val p = HealthPromptBuilder.buildUserPrompt(evidence, template)
        assertTrue(p.contains(evidence.toJson()))
        assertTrue(p.contains(template.detail))
        assertTrue(p.lowercase().contains("do not add advice"))
    }

    // ── Validation: the defence-in-depth layer ────────────────────────────────

    @Test
    fun `accepts a compliant rewrite`() {
        val good = "The oxygen level in the blood was measured at 89%, with a heart " +
            "rate of 108 beats a minute. It has stayed low for about 12 minutes."
        assertEquals(good, HealthPromptBuilder.validate(good, evidence))
    }

    @Test
    fun `trims surrounding whitespace`() {
        val raw = "\n  Oxygen was measured at 89% and has been low for 12 minutes.  \n"
        assertEquals(raw.trim(), HealthPromptBuilder.validate(raw, evidence))
    }

    @Test
    fun `rejects output that is too short or empty`() {
        assertNull(HealthPromptBuilder.validate("", evidence))
        assertNull(HealthPromptBuilder.validate("Low oxygen.", evidence))
    }

    @Test
    fun `rejects runaway output`() {
        assertNull(HealthPromptBuilder.validate("89% oxygen. ".repeat(200), evidence))
    }

    @Test
    fun `rejects model meta commentary and refusals`() {
        listOf(
            "As an AI language model I cannot interpret medical readings for you here.",
            "I'm sorry, but I cannot help with medical questions about oxygen levels.",
            "<|im_start|>assistant the oxygen level is 89 percent right now"
        ).forEach {
            assertNull("should have rejected: $it", HealthPromptBuilder.validate(it, evidence))
        }
    }

    /** Advice is owned by the fixed tier text; the model must not add its own. */
    @Test
    fun `rejects prescriptive or diagnostic language`() {
        listOf(
            "Oxygen is 89%. You should take a tablet of something for this condition now.",
            "The reading of 89% means the diagnosis is clear and needs 500 mg of medicine.",
            "Oxygen at 89% — this is caused by a lung infection and needs treatment today."
        ).forEach {
            assertNull("should have rejected: $it", HealthPromptBuilder.validate(it, evidence))
        }
    }

    /**
     * The most dangerous failure available to the model is inventing a vital sign.
     * Any number in the output that was not actually measured causes rejection, and
     * the deterministic template stands instead.
     */
    @Test
    fun `rejects fabricated vital signs`() {
        val fabricated = "The oxygen level was measured at 72%, with a heart rate of " +
            "108 beats a minute, and has stayed low for about 12 minutes."
        assertNull("72 was never measured", HealthPromptBuilder.validate(fabricated, evidence))

        val wrongHr = "Oxygen was 89% with a heart rate of 143 beats a minute over 12 minutes."
        assertNull("143 was never measured", HealthPromptBuilder.validate(wrongHr, evidence))
    }

    @Test
    fun `permits every genuinely measured value including the risk scores`() {
        // Note "78" (the respiratory risk score) is allowed because it was computed;
        // a scale constant like "out of 100" is deliberately NOT whitelisted, since
        // keeping the number check strict is worth more than accommodating phrasing.
        val text = "Oxygen was 89% and the heart rate 108 a minute for 12 minutes. " +
            "Body temperature was 37.4 degrees. Breathing risk is scored at 78."
        assertNotNull(HealthPromptBuilder.validate(text, evidence))
    }

    @Test
    fun `rejects an unmeasured scale constant`() {
        // "out of 100" reads naturally but 100 was never measured, so the strict check
        // rejects it. Documented here so the behaviour is intentional, not a surprise.
        val text = "Oxygen was 89% for 12 minutes and breathing risk is 78 out of 100."
        assertNull(HealthPromptBuilder.validate(text, evidence))
    }

    /** Small ordinals in ordinary prose must not be mistaken for fabricated vitals. */
    @Test
    fun `ignores small numbers used as ordinary words`() {
        val text = "Oxygen was 89% for 12 minutes. There are 2 things worth noting, " +
            "and 3 of them relate to the heart rate of 108."
        assertNotNull(HealthPromptBuilder.validate(text, evidence))
    }

    @Test
    fun `accepts output with no numbers at all`() {
        val text = "The oxygen level in the blood was lower than normal and stayed " +
            "that way for several minutes, and it is still falling."
        assertNotNull(HealthPromptBuilder.validate(text, evidence))
    }

    @Test
    fun `handles evidence with almost no values without crashing`() {
        val bare = AnomalyEvidence(
            type = AnomalyType.FATIGUE,
            severity = Severity.LOW,
            triggeredAt = 1_700_000_000_000L,
            ruleId = "wellness.fatigue",
            riskScores = RiskScores.ZERO
        )
        val t = ExplanationTemplates.render(bare)
        assertTrue(HealthPromptBuilder.buildUserPrompt(bare, t).isNotBlank())
        // With nothing measured, any sizeable number in the output is fabricated.
        assertNull(HealthPromptBuilder.validate("The resting heart rate was 97 a minute.", bare))
    }
}
