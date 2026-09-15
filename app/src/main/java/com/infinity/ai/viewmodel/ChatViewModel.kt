package com.infinity.ai.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infinity.ai.ai.repository.AIRepository
import com.infinity.ai.ai.state.AIInferenceState
import com.infinity.ai.model.ChatMessage
import com.infinity.ai.model.ChatSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val repository = AIRepository.getInstance(app)

    // ── Multi-session chat management ──────────────────────────────────────────
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow(UUID.randomUUID().toString())
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    // ── Chat state ─────────────────────────────────────────────────────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _showSuggestions = MutableStateFlow(true)
    val showSuggestions: StateFlow<Boolean> = _showSuggestions.asStateFlow()

    // ── AI engine state (forwarded from repository) ────────────────────────────
    val aiState: StateFlow<AIInferenceState> = repository.aiState

    // ── Extraction progress (first launch only) ────────────────────────────────
    private val _extractionProgress = MutableStateFlow(0f)
    val extractionProgress: StateFlow<Float> = _extractionProgress.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    // ── Generation job ─────────────────────────────────────────────────────────
    private var generationJob: Job? = null
    private var messageIdCounter = 0L
    private fun nextId() = ++messageIdCounter

    // Bug fix: track whether stop was user-initiated so onCompletion doesn't
    // replace a valid partial response with an error message.
    private var userStoppedGeneration = false

    init {
        initializeAI()
        loadInitialSessions()
    }

    // ── Session Persistence & Management ───────────────────────────────────────

    private fun loadInitialSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = loadSessionsFromDisk()
            if (loaded.isNotEmpty()) {
                _sessions.value = loaded
                val active = loaded.maxByOrNull { it.updatedAt } ?: loaded.first()
                _currentSessionId.value = active.id
                _messages.value = active.messages
                _showSuggestions.value = active.messages.isEmpty()
                val maxId = active.messages.maxOfOrNull { it.id } ?: 0L
                if (maxId > messageIdCounter) messageIdCounter = maxId
            } else {
                val initialSession = ChatSession(
                    id = _currentSessionId.value,
                    title = "New Consultation",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    messages = emptyList()
                )
                _sessions.value = listOf(initialSession)
                saveSessionsToDisk()
            }
        }
    }

    private fun loadSessionsFromDisk(): List<ChatSession> {
        return try {
            val file = File(getApplication<Application>().filesDir, "chat_sessions.json")
            if (!file.exists()) return emptyList()
            val jsonStr = file.readText()
            val array = JSONArray(jsonStr)
            val list = mutableListOf<ChatSession>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val title = obj.optString("title", "New Consultation")
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                val updatedAt = obj.optLong("updatedAt", createdAt)
                val msgsArray = obj.optJSONArray("messages") ?: JSONArray()
                val msgs = mutableListOf<ChatMessage>()
                for (j in 0 until msgsArray.length()) {
                    val mObj = msgsArray.getJSONObject(j)
                    msgs.add(
                        ChatMessage(
                            id = mObj.getLong("id"),
                            text = mObj.getString("text"),
                            isUser = mObj.getBoolean("isUser")
                        )
                    )
                }
                list.add(ChatSession(id, title, createdAt, updatedAt, msgs))
            }
            list.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chat sessions", e)
            emptyList()
        }
    }

    private fun saveSessionsToDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val array = JSONArray()
                _sessions.value.forEach { session ->
                    val obj = JSONObject().apply {
                        put("id", session.id)
                        put("title", session.title)
                        put("createdAt", session.createdAt)
                        put("updatedAt", session.updatedAt)
                        val msgsArray = JSONArray()
                        session.messages.forEach { m ->
                            msgsArray.put(JSONObject().apply {
                                put("id", m.id)
                                put("text", m.text)
                                put("isUser", m.isUser)
                            })
                        }
                        put("messages", msgsArray)
                    }
                    array.put(obj)
                }
                val file = File(getApplication<Application>().filesDir, "chat_sessions.json")
                file.writeText(array.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save chat sessions", e)
            }
        }
    }

    private fun syncCurrentSession() {
        val currentId = _currentSessionId.value
        val currentMsgs = _messages.value
        val firstUserMsg = currentMsgs.firstOrNull { it.isUser }?.text
        _sessions.value = _sessions.value.map { session ->
            if (session.id == currentId) {
                val newTitle = if ((session.title == "New Consultation" || session.title == "New Chat") && !firstUserMsg.isNullOrBlank()) {
                    if (firstUserMsg.length > 32) firstUserMsg.take(32).trim() + "…" else firstUserMsg
                } else {
                    session.title
                }
                session.copy(
                    title = newTitle,
                    updatedAt = System.currentTimeMillis(),
                    messages = currentMsgs
                )
            } else session
        }
        saveSessionsToDisk()
    }

    fun selectSession(sessionId: String) {
        if (sessionId == _currentSessionId.value) return
        stopGeneration()
        val session = _sessions.value.find { it.id == sessionId } ?: return
        _currentSessionId.value = sessionId
        _messages.value = session.messages
        _showSuggestions.value = session.messages.isEmpty()
        _input.value = ""
        val maxId = session.messages.maxOfOrNull { it.id } ?: 0L
        if (maxId > messageIdCounter) messageIdCounter = maxId
    }

    fun createNewChat() {
        stopGeneration()
        val newSession = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "New Consultation",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            messages = emptyList()
        )
        _sessions.value = listOf(newSession) + _sessions.value
        _currentSessionId.value = newSession.id
        _messages.value = emptyList()
        _showSuggestions.value = true
        _input.value = ""
        saveSessionsToDisk()
    }

    fun deleteSession(sessionId: String) {
        val wasCurrent = sessionId == _currentSessionId.value
        if (wasCurrent) stopGeneration()
        val updated = _sessions.value.filterNot { it.id == sessionId }
        _sessions.value = updated
        if (wasCurrent) {
            if (updated.isNotEmpty()) {
                val next = updated.first()
                _currentSessionId.value = next.id
                _messages.value = next.messages
                _showSuggestions.value = next.messages.isEmpty()
                _input.value = ""
                val maxId = next.messages.maxOfOrNull { it.id } ?: 0L
                if (maxId > messageIdCounter) messageIdCounter = maxId
            } else {
                createNewChat()
                return
            }
        }
        saveSessionsToDisk()
    }

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

    fun sendMessage() {
        val text = _input.value.trim()
        if (text.isBlank()) return
        if (aiState.value is AIInferenceState.Thinking ||
            aiState.value is AIInferenceState.Responding) return

        if (aiState.value is AIInferenceState.Loading ||
            aiState.value is AIInferenceState.Error) {
            val userMsg = ChatMessage(nextId(), text, isUser = true)
            val errMsg  = ChatMessage(nextId(),
                if (aiState.value is AIInferenceState.Loading)
                    "Model is still loading, please wait a moment and try again."
                else
                    "AI engine error. Please restart the app.",
                isUser = false)
            _messages.value = _messages.value + userMsg + errMsg
            _input.value = ""
            _showSuggestions.value = false
            syncCurrentSession()
            return
        }

        _showSuggestions.value = false
        _input.value = ""

        val userMsg = ChatMessage(nextId(), text, isUser = true)
        _messages.value = _messages.value + userMsg
        syncCurrentSession()

        generateReply(text)
    }

    fun startFromSuggestion(prompt: String) {
        _showSuggestions.value = false
        val welcome = ChatMessage(nextId(), "Hi, I'm G-one. What would you like to understand?", isUser = false)
        val userMsg = ChatMessage(nextId(), prompt, isUser = true)
        _messages.value = listOf(welcome, userMsg)
        syncCurrentSession()
        if (aiState.value is AIInferenceState.Loading ||
            aiState.value is AIInferenceState.Error) {
            val errMsg = ChatMessage(nextId(),
                if (aiState.value is AIInferenceState.Loading)
                    "Model is still loading, please wait a moment and try again."
                else
                    "AI engine error. Please restart the app.",
                isUser = false)
            _messages.value = _messages.value + errMsg
            syncCurrentSession()
            return
        }
        generateReply(prompt)
    }

    fun stopGeneration() {
        userStoppedGeneration = true
        // Bug fix: stop C++ thread FIRST, then cancel the coroutine job.
        // Previously cancel() fired awaitClose (which called stopGeneration) then
        // repository.stop() called it again — harmless but wrong order semantically.
        // More importantly: stopping C++ first reduces the window where a new
        // generation could start while the old thread is still running.
        repository.stop()
        generationJob?.cancel()
    }

    fun retry(aiMsgId: Long, prompt: String) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) return
        if (aiState.value is AIInferenceState.Thinking ||
            aiState.value is AIInferenceState.Responding) return

        stopGeneration()
        _messages.value = _messages.value.filterNot { it.id == aiMsgId }
        syncCurrentSession()
        generateReply(trimmedPrompt)
    }

    fun clearChat() {
        stopGeneration()
        _messages.value = emptyList()
        _input.value = ""
        _showSuggestions.value = true
        syncCurrentSession()
    }

    // ── Streaming generation ───────────────────────────────────────────────────

    private fun generateReply(userInput: String) {
        val aiMsgId = nextId()
        val placeholder = ChatMessage(aiMsgId, "", isUser = false)
        _messages.value = _messages.value + placeholder

        // Stop previous generation before starting a new one
        userStoppedGeneration = false
        repository.stop()
        generationJob?.cancel()

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            // Bug fix: drop TWO trailing entries, not one.
            //
            // At this point _messages is [...prior, userMsg, placeholder].
            // PromptFormatter renders every history entry as its own turn AND then
            // appends `userInput` as a fresh user turn. Dropping only the
            // placeholder left userMsg in history, so every prompt contained two
            // identical consecutive <|im_start|>user blocks — wasted context and
            // a confused model.
            //
            // dropLast(2) removes the placeholder and the user message that
            // `userInput` already represents. Correct for both entry points:
            //   sendMessage()        [...prior, userMsg, placeholder] -> [...prior]
            //   startFromSuggestion() [welcome, userMsg, placeholder] -> [welcome]
            val historyForPrompt = _messages.value
                .dropLast(2)
                .takeLast(20)

            try {
                repository.generate(historyForPrompt, userInput)
                    .catch { e ->
                        Log.e(TAG, "Generation failed: ${e.message}", e)
                        updateMessage(aiMsgId, "Sorry, I encountered an error: ${e.message}")
                    }
                    .onCompletion { cause ->
                        if (cause != null) {
                            Log.e(TAG, "Generation cancelled or failed", cause)
                        }
                        // Bug fix: only replace blank message with error if the stop was NOT
                        // user-initiated. A user-stopped generation may have a valid partial
                        // response that should be preserved.
                        if (!userStoppedGeneration) {
                            val current = _messages.value.find { it.id == aiMsgId }
                            if (current?.text.isNullOrBlank()) {
                                updateMessage(aiMsgId, "I couldn't generate a response. Please try again.")
                            }
                        }
                        syncCurrentSession()
                    }
                    .collect { token -> appendToken(aiMsgId, token) }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during generation", e)
                updateMessage(aiMsgId, "An unexpected error occurred. Please try again.")
                syncCurrentSession()
            }
        }
    }

    private fun appendToken(msgId: Long, token: String) {
        // Bug fix: removed `if (updated !== current)` reference-equality guard.
        // List.map{} always returns a new object so the check was always true —
        // a misleading no-op. Just assign directly.
        _messages.value = _messages.value.map { msg ->
            if (msg.id == msgId) msg.copy(text = msg.text + token) else msg
        }
    }

    private fun updateMessage(msgId: Long, text: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == msgId) msg.copy(text = text) else msg
        }
    }

    // ── Lifecycle cleanup ──────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        // Cancel only OUR generation. Do NOT unload the model.
        //
        // AIRepository is a process-wide singleton shared with the health
        // monitoring service. Unloading here would free the model out from under
        // active monitoring the moment this screen was destroyed. Model lifecycle
        // is owned by HealthMonitoringService — see AIRepository.shutdown().
        stopGeneration()
    }
}
