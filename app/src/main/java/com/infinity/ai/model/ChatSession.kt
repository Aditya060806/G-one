package com.infinity.ai.model

import java.util.UUID

/**
 * Represents a saved chat conversation session with G-one.
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Consultation",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList()
)