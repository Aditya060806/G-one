package com.gone.ai.chat

/**
 * The newest chat reply: which message it is, its text so far, and whether it has finished.
 *
 * Voice chat speaks from this. Unlike the chat's display state, the finished text is kept
 * until the next reply starts, so a listener that falls behind still sees how it ended.
 */
data class ReplyProgress(val messageId: Long, val text: String, val finished: Boolean)
