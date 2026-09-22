package com.gone.ai.chat

import com.gone.ai.chat.ChatSuggestions.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSuggestionsTest {

    @Test
    fun `without personal data only general questions are offered`() {
        val kinds = ChatSuggestions.choose(hasReports = false, hasRecentAlerts = false, hasRecords = false, usingHealthData = true).map { it.kind }
        assertEquals(listOf(Kind.HEART, Kind.LAB, Kind.GENERAL), kinds)
    }

    @Test
    fun `personal suggestions lead when their data exists`() {
        val kinds = ChatSuggestions.choose(hasReports = true, hasRecentAlerts = true, hasRecords = false, usingHealthData = true).map { it.kind }
        assertEquals(listOf(Kind.SESSION, Kind.ALERT, Kind.HEART, Kind.LAB), kinds)
    }

    @Test
    fun `nothing personal is suggested when the person switched health data off`() {
        val suggestions = ChatSuggestions.choose(hasReports = true, hasRecentAlerts = true, hasRecords = true, usingHealthData = false)
        assertTrue(suggestions.none { it.kind in setOf(Kind.SESSION, Kind.ALERT, Kind.RECORD) })
    }

    @Test
    fun `at most four suggestions`() {
        assertEquals(4, ChatSuggestions.choose(hasReports = true, hasRecentAlerts = true, hasRecords = true, usingHealthData = true).size)
    }
}
