package com.gone.ai.model

import java.util.UUID

/**
 * Represents a saved chat conversation session with G-one.
 *
 * [attachmentTitle] and [attachmentText] hold a document the conversation is about — a tool
 * result sent with "Ask G-one", or a Vault entry. The text goes to the model with every
 * reply in this conversation, so trimming the chat history cannot drop it.
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = DEFAULT_TITLE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val attachmentTitle: String? = null,
    val attachmentText: String? = null
) {
    companion object {
        const val DEFAULT_TITLE = "New Consultation"
    }
}
