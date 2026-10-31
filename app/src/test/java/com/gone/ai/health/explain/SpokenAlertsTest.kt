package com.gone.ai.health.explain

import com.gone.ai.health.domain.AnomalyType
import com.gone.ai.health.domain.Severity
import com.gone.ai.voice.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenAlertsTest {

    @Test fun `english fall alert`() {
        assertEquals(
            "Urgent alert: Possible fall. Please check on the wearer now.",
            SpokenAlerts.sentence(AnomalyType.FALL_DETECTED, Severity.CRITICAL, AppLanguage.ENGLISH)
        )
    }

    @Test fun `hindi fall alert`() {
        assertEquals(
            "तत्काल चेतावनी: संभावित गिरावट. कृपया अभी व्यक्ति की जाँच करें।",
            SpokenAlerts.sentence(AnomalyType.FALL_DETECTED, Severity.CRITICAL, AppLanguage.HINDI)
        )
    }

    @Test fun `every type has hindi words`() {
        AnomalyType.entries.forEach { type ->
            val label = SpokenAlerts.label(type, AppLanguage.HINDI)
            assertTrue("$type has no Devanagari", label.any { it in 'ऀ'..'ॿ' })
        }
    }
}
