package com.gone.ai.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.ai.prompts.PromptFormatter
import com.gone.ai.ai.repository.AIRepository
import com.gone.ai.ai.state.AIInferenceState
import com.gone.ai.chat.ChatPrompt
import com.gone.ai.chat.ChatSessionStore
import com.gone.ai.chat.ChatSuggestions
import com.gone.ai.chat.ReplyProgress
import com.gone.ai.health.context.HealthContextSource
import com.gone.ai.model.ChatMessage
import com.gone.ai.model.ChatSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val WELCOME_TEXT = "Hi, I'm G-one. What would you like to understand?"
    }

    /** A document a conversation is about. */
    data class Attachment(val title: String, val text: String)

    private val repository = AIRepository.getInstance(app)
    private val store = ChatSessionStore.getInstance(app)
    private val healthContext = HealthContextSource.getInstance(app)

    /** "Use my health data": whether replies may draw on readings, reports, alerts and shared records. */
    val useHealthData: StateFlow<Boolean> = healthContext.enabled

    private val _suggestions = MutableStateFlow(
        ChatSuggestions.choose(hasReports = false, hasRecentAlerts = false, hasRecords = false, usingHealthData = false)
    )
    /** Suggestions for an empty chat, personal ones only when their data exists. */
    val suggestions: StateFlow<List<ChatSuggestions.Suggestion>> = _suggestions.asStateFlow()

    /** Keeps the model loaded while chat exists. Closed in [onCleared]. */
    private val modelLease = repository.acquire("chat")

    // ── Multi-session chat management ──────────────────────────────────────────
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow(UUID.randomUUID().toString())
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    /** The document the open conversation is about, if any. */
    val attachment: StateFlow<Attachment?> =
        combine(_sessions, _currentSessionId) { sessions, id ->
            sessions.firstOrNull { it.id == id }?.let { session ->
                val text = session.attachmentText
                if (text.isNullOrBlank()) null else Attachment(session.attachmentTitle ?: "Document", text)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── Chat state ─────────────────────────────────────────────────────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _showSuggestions = MutableStateFlow(true)
    val showSuggestions: StateFlow<Boolean> = _showSuggestions.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /**
     * A message for a snackbar, such as a history file that could not be read. Held until
     * [noticeShown], so one raised before the screen is listening is not lost.
     */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun noticeShown() {
        _notice.value = null
    }

    // ── AI state ───────────────────────────────────────────────────────────────

    /** Engine-wide state, for status displays such as Settings. */
    val engineState: StateFlow<AIInferenceState> = repository.aiState

    private sealed interface Reply {
        data object None : Reply
        data object Waiting : Reply
        data class Streaming(val text: String) : Reply
    }

    private val _reply = MutableStateFlow<Reply>(Reply.None)

    private val _latestReply = MutableStateFlow<ReplyProgress?>(null)
    /** The newest reply and whether it has finished, for voice chat, which speaks it. */
    val latestReply: StateFlow<ReplyProgress?> = _latestReply.asStateFlow()

    /**
     * State of THIS conversation's reply, merged with model loading and load failures.
     *
     * Deliberately not the raw engine state. The engine also serves health-alert
     * explanations and the document tools, so its Thinking/Responding says nothing about
     * chat: a background explanation used to disable the send button, and the voice
     * screen displayed whichever request's text happened to be streaming.
     */
    val aiState: StateFlow<AIInferenceState> =
        combine(repository.aiState, _reply) { engine, reply ->
            when (reply) {
                is Reply.Streaming -> AIInferenceState.Responding(reply.text)
                Reply.Waiting ->
                    if (engine is AIInferenceState.Loading) engine else AIInferenceState.Thinking
                Reply.None -> when (engine) {
                    is AIInferenceState.Loading, is AIInferenceState.Error -> engine
                    else -> AIInferenceState.Idle
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AIInferenceState.Idle)

    // ── Extraction progress (first launch only) ────────────────────────────────
    private val _extractionProgress = MutableStateFlow(0f)
    val extractionProgress: StateFlow<Float> = _extractionProgress.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    // ── Generation ─────────────────────────────────────────────────────────────
    private var generationJob: Job? = null

    /** Set when the reply in flight was stopped on purpose (stop button or superseded). */
    private var activeStopFlag: AtomicBoolean? = null

    /** Identifies the newest reply, so a superseded one cannot write into the UI. */
    private val generationCounter = AtomicLong(0)

    private val messageIdCounter = AtomicLong(0)
    private fun nextId() = messageIdCounter.incrementAndGet()

    private val isReplying: Boolean get() = generationJob?.isActive == true

    /** Completes once saved conversations are loaded, so an early action cannot race it. */
    private val sessionsLoaded: Job

    init {
        initializeAI()
        sessionsLoaded = loadInitialSessions()
    }

    // ── Session Persistence & Management ───────────────────────────────────────

    private fun loadInitialSessions(): Job = viewModelScope.launch(Dispatchers.IO) {
        when (val result = store.load()) {
            is ChatSessionStore.LoadResult.Loaded -> {
                val loaded = result.sessions
                _sessions.value = loaded
                val active = loaded.maxByOrNull { it.updatedAt } ?: loaded.first()
                _currentSessionId.value = active.id
                _messages.value = active.messages
                _showSuggestions.value = active.messages.isEmpty()
                bumpIdCounter(loaded.flatMap { it.messages })
            }
            ChatSessionStore.LoadResult.Empty -> startFirstSession()
            is ChatSessionStore.LoadResult.Corrupt -> {
                startFirstSession()
                _notice.value =
                    "Your earlier chats could not be read. A copy was kept on the phone; new chats start fresh."
            }
        }
    }

    private fun startFirstSession() {
        val now = System.currentTimeMillis()
        _sessions.value = listOf(ChatSession(id = _currentSessionId.value, createdAt = now, updatedAt = now))
        saveSessions()
    }

    /** Message ids must stay unique across every session, not just the open one. */
    private fun bumpIdCounter(messages: List<ChatMessage>) {
        val maxId = messages.maxOfOrNull { it.id } ?: return
        messageIdCounter.updateAndGet { maxOf(it, maxId) }
    }

    private fun saveSessions() = store.save(_sessions.value)

    private fun syncCurrentSession() {
        val currentId = _currentSessionId.value
        val currentMsgs = _messages.value
        val firstUserMsg = currentMsgs.firstOrNull { it.isUser }?.text
        _sessions.update { sessions ->
            sessions.map { session ->
                if (session.id == currentId) {
                    val untitled = session.title == ChatSession.DEFAULT_TITLE || session.title == "New Chat"
                    val newTitle = if (untitled && !firstUserMsg.isNullOrBlank()) {
                        if (firstUserMsg.length > 32) firstUserMsg.take(32).trim() + "…" else firstUserMsg
                    } else {
                        session.title
                    }
                    session.copy(title = newTitle, updatedAt = System.currentTimeMillis(), messages = currentMsgs)
                } else session
            }
        }
        saveSessions()
    }

    /**
     * Stop the open conversation's reply and store it as it stands before switching away.
     * A reply stopped before its first token leaves an empty bubble, which is dropped.
     */
    private fun leaveCurrentSession() {
        stopGeneration()
        _messages.update { list -> list.filterNot { !it.isUser && it.text.isBlank() } }
        syncCurrentSession()
    }

    fun selectSession(sessionId: String) {
        if (sessionId == _currentSessionId.value) return
        leaveCurrentSession()
        val session = _sessions.value.find { it.id == sessionId } ?: return
        _currentSessionId.value = sessionId
        _messages.value = session.messages
        _showSuggestions.value = session.messages.isEmpty() && session.attachmentText == null
        _input.value = ""
    }

    fun createNewChat() {
        leaveCurrentSession()
        val newSession = ChatSession()
        _sessions.update { listOf(newSession) + it }
        _currentSessionId.value = newSession.id
        _messages.value = emptyList()
        _showSuggestions.value = true
        _input.value = ""
        saveSessions()
    }

    /**
     * Start a new conversation about a document: a tool result, a Vault entry, a report.
     *
     * The document is kept on the session rather than posted as a message, so it reaches
     * the model with every reply and trimming old messages never drops it.
     */
    fun startConversationAbout(title: String, text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            sessionsLoaded.join()
            leaveCurrentSession()
            val cleanTitle = title.trim().ifEmpty { "Document" }
            val intro = ChatMessage(nextId(), "I have \"$cleanTitle\" open. What would you like to know about it?", isUser = false)
            val session = ChatSession(
                title = "About: " + cleanTitle.take(40),
                messages = listOf(intro),
                attachmentTitle = cleanTitle,
                attachmentText = content.take(ChatPrompt.MAX_ATTACHMENT_CHARS)
            )
            _sessions.update { listOf(session) + it }
            _currentSessionId.value = session.id
            _messages.value = session.messages
            _showSuggestions.value = false
            _input.value = ""
            saveSessions()
        }
    }

    fun deleteSession(sessionId: String) {
        val wasCurrent = sessionId == _currentSessionId.value
        if (wasCurrent) stopGeneration()
        _sessions.update { sessions -> sessions.filterNot { it.id == sessionId } }
        val remaining = _sessions.value
        if (wasCurrent) {
            if (remaining.isNotEmpty()) {
                val next = remaining.first()
                _currentSessionId.value = next.id
                _messages.value = next.messages
                _showSuggestions.value = next.messages.isEmpty() && next.attachmentText == null
                _input.value = ""
            } else {
                createNewChat()
                return
            }
        }
        saveSessions()
    }

    // ── Personal context ───────────────────────────────────────────────────────

    fun setUseHealthData(enabled: Boolean) {
        healthContext.setEnabled(enabled)
        refreshSuggestions()
    }

    fun refreshSuggestions() {
        viewModelScope.launch(Dispatchers.IO) {
            val available = runCatching { healthContext.availability() }.getOrNull() ?: return@launch
            _suggestions.value = ChatSuggestions.choose(
                available.hasReports, available.hasRecentAlerts, available.hasRecords, useHealthData.value
            )
        }
    }

    /**
     * The personal facts exactly as the next reply would receive them, fitted to their token
     * budget, or null when the person has switched health data off.
     */
    suspend fun healthContextPreview(): String? =
        if (!useHealthData.value) null else fitted(healthContext.build(), ChatPrompt.HEALTH_CONTEXT_TOKEN_BUDGET)

    // ── AI Initialization ──────────────────────────────────────────────────────

    private fun initializeAI() {
        viewModelScope.launch(Dispatchers.IO) {
            _isExtracting.value = !repository.isModelOnDisk()
            repository.initialize { progress ->
                _extractionProgress.value = progress
            }
            _isExtracting.value = false
        }
    }

    // ── Input handling ─────────────────────────────────────────────────────────

    fun onInputChange(value: String) {
        _input.value = value
    }

    /**
     * Send the typed message.
     *
     * No longer refuses while the model loads or after a load failure: the request waits
     * for the load, and a failure is shown in the reply bubble. Previously one failure
     * told the user to restart the app even though the next attempt would have worked.
     */
    fun sendMessage() {
        val text = _input.value.trim()
        if (text.isBlank() || isReplying) return
        _input.value = ""
        submitUserMessage(text)
    }

    /** Suggestion chips appear only on an empty conversation, which opens with a greeting. */
    fun startFromSuggestion(prompt: String) {
        val text = prompt.trim()
        if (text.isBlank() || isReplying) return
        if (_messages.value.isEmpty()) {
            _messages.value = listOf(ChatMessage(nextId(), WELCOME_TEXT, isUser = false))
        }
        submitUserMessage(text)
    }

    /**
     * Voice input APPENDS to the open conversation.
     *
     * It used to go through the suggestion path, which replaced the whole message list —
     * speaking a question erased the chat history.
     */
    fun sendVoiceMessage(transcript: String): Long? {
        val text = transcript.trim()
        if (text.isBlank()) return null
        stopGeneration()
        return submitUserMessage(text)
    }

    /** Returns the id of the reply message it starts. */
    private fun submitUserMessage(text: String): Long {
        _showSuggestions.value = false
        val history = _messages.value
        val userId = nextId()
        val placeholderId = nextId()
        _messages.update {
            it + ChatMessage(userId, text, isUser = true) + ChatMessage(placeholderId, "", isUser = false)
        }
        syncCurrentSession()
        startReply(text, history, placeholderId)
        return placeholderId
    }

    fun stopGeneration() {
        activeStopFlag?.set(true)
        generationJob?.cancel()
        generationJob = null
        _reply.value = Reply.None
    }

    /** Regenerate one assistant message in place, from the conversation that preceded it. */
    fun retry(aiMsgId: Long, prompt: String) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank() || isReplying) return

        val current = _messages.value
        val index = current.indexOfFirst { it.id == aiMsgId }
        if (index < 0) return
        val promptIndex = (index - 1).takeIf { it >= 0 && current[it].isUser }
        val history = current.subList(0, promptIndex ?: index).toList()

        updateMessage(aiMsgId, "")
        startReply(trimmedPrompt, history, aiMsgId)
    }

    fun clearChat() {
        stopGeneration()
        _messages.value = emptyList()
        _input.value = ""
        _showSuggestions.value = true
        syncCurrentSession()
    }

    // ── Streaming generation ───────────────────────────────────────────────────

    /**
     * Stream a reply to [userInput] into the message [targetId].
     *
     * @param history the conversation BEFORE the user's message. The formatter appends
     *   [userInput] as the final user turn itself; passing it in history too used to put
     *   the same question in the prompt twice.
     */
    private fun startReply(userInput: String, history: List<ChatMessage>, targetId: Long) {
        stopGeneration()

        val generation = generationCounter.incrementAndGet()
        val stoppedOnPurpose = AtomicBoolean(false)
        activeStopFlag = stoppedOnPurpose
        _reply.value = Reply.Waiting
        _latestReply.value = ReplyProgress(targetId, "", finished = false)
        val sessionAttachment = attachment.value

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            val reply = StringBuilder()
            fun isCurrent() = generationCounter.get() == generation

            try {
                val systemPrompt = systemPromptFor(sessionAttachment)
                repository.generate(history, userInput, systemPrompt).collect { token ->
                    reply.append(token)
                    if (isCurrent()) {
                        val text = reply.toString()
                        updateMessage(targetId, text)
                        _reply.value = Reply.Streaming(text)
                        _latestReply.update { if (it?.messageId == targetId) it.copy(text = text) else it }
                    }
                }
                if (reply.isBlank()) {
                    updateMessage(targetId, "I couldn't generate a response. Please try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed", e)
                if (reply.isBlank()) {
                    val reason = e.message ?: "unknown error"
                    updateMessage(targetId, "Sorry, I couldn't respond: $reason")
                }
                // With partial text, keep what the user already read.
            } finally {
                // A reply stopped before its first token leaves nothing worth keeping.
                if (stoppedOnPurpose.get() && reply.isBlank()) removeMessage(targetId)
                if (isCurrent()) _reply.value = Reply.None
                val finalText = _messages.value.firstOrNull { it.id == targetId }?.text.orEmpty()
                _latestReply.update { if (it?.messageId == targetId) ReplyProgress(targetId, finalText, finished = true) else it }
                syncCurrentSession()
            }
        }
    }

    /**
     * The system prompt for a reply: personal facts when allowed, and the conversation's
     * document, each fitted to its token budget. The facts are read fresh for every reply, so
     * a session that just ended or a record just shared is already known.
     */
    private suspend fun systemPromptFor(attachment: Attachment?): String {
        val health = if (useHealthData.value) {
            runCatching { healthContext.build() }.getOrNull()?.let { fitted(it, ChatPrompt.HEALTH_CONTEXT_TOKEN_BUDGET) }
        } else null
        val document = attachment?.let { fitted(it.text, ChatPrompt.ATTACHMENT_TOKEN_BUDGET) }
        return ChatPrompt.system(attachment?.title, document, health)
    }

    /**
     * [text] cut to [budget] tokens. Counting needs the tokenizer, so the model is loaded
     * first; if loading fails, [AIRepository.generate] reports it, and the text is used as is.
     */
    private suspend fun fitted(text: String, budget: Int): String {
        repository.initialize()
        return withContext(Dispatchers.IO) {
            PromptFormatter.truncateToTokenBudget(text, budget, repository::countTokensNow).text
        }
    }

    private fun updateMessage(msgId: Long, text: String) {
        _messages.update { list ->
            list.map { msg -> if (msg.id == msgId) msg.copy(text = text) else msg }
        }
    }

    private fun removeMessage(msgId: Long) {
        _messages.update { list -> list.filterNot { it.id == msgId } }
    }

    // ── Lifecycle cleanup ──────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopGeneration()
        // Releases this screen's claim only. The model stays loaded while any other
        // component — monitoring, a document tool — still holds a lease.
        modelLease.close()
    }
}
