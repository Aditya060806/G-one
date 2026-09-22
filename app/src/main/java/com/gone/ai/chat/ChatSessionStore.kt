package com.gone.ai.chat

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import com.gone.ai.model.ChatMessage
import com.gone.ai.model.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Chat history on disk, written safely.
 *
 * The chat screen used to launch a new coroutine for every save, each calling
 * `File.writeText`. Two saves could overlap, an older snapshot could land after a newer
 * one, and a crash mid-write left a truncated file. Loading then failed, returned an empty
 * list, and the next save overwrote the whole history with one empty conversation.
 *
 * Now:
 *  - saves are queued and written one at a time, newest snapshot wins;
 *  - every write goes to a temporary file that replaces the old one only when complete
 *    ([AtomicFile]);
 *  - a file that cannot be read is renamed aside, never overwritten, so it can be recovered.
 *
 * Writes run in this store's own scope, so leaving the chat screen does not drop the last save.
 */
class ChatSessionStore internal constructor(
    private val file: File,
    private val codec: ChatSessionCodec = JsonChatSessionCodec,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis
) {

    sealed interface LoadResult {
        data class Loaded(val sessions: List<ChatSession>) : LoadResult
        data object Empty : LoadResult
        /** The file could not be read. It was renamed to [keptAs] and left for recovery. */
        data class Corrupt(val keptAs: File?) : LoadResult
    }

    private val lock = Mutex()
    private val pending = MutableStateFlow<List<ChatSession>?>(null)

    init {
        // A StateFlow keeps only the newest value, so saves that arrive during a write
        // collapse into one write of the latest snapshot.
        scope.launch {
            pending.filterNotNull().collect { snapshot -> lock.withLock { write(snapshot) } }
        }
    }

    suspend fun load(): LoadResult = lock.withLock {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return@withLock LoadResult.Empty
        try {
            val text = atomic.readFully().toString(Charsets.UTF_8)
            if (text.isBlank()) return@withLock LoadResult.Empty
            val sessions = codec.decode(text)
            if (sessions.isEmpty()) LoadResult.Empty else LoadResult.Loaded(sessions)
        } catch (e: Exception) {
            Log.e(TAG, "Chat history could not be read; keeping the file aside", e)
            val kept = File(file.parentFile, "${file.nameWithoutExtension}.unreadable-${clock()}.json")
            LoadResult.Corrupt(if (file.renameTo(kept)) kept else null)
        }
    }

    /** Queue [sessions] to be written. Returns immediately. */
    fun save(sessions: List<ChatSession>) {
        pending.value = sessions
    }

    private fun write(sessions: List<ChatSession>) {
        val atomic = AtomicFile(file)
        val out = try {
            atomic.startWrite()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open chat history for writing", e)
            return
        }
        try {
            out.write(codec.encode(sessions).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            Log.e(TAG, "Could not write chat history; the previous copy is kept", e)
            atomic.failWrite(out)
        }
    }

    companion object {
        private const val TAG = "ChatSessionStore"
        const val FILE_NAME = "chat_sessions.json"

        @Volatile private var INSTANCE: ChatSessionStore? = null

        fun getInstance(context: Context): ChatSessionStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatSessionStore(File(context.applicationContext.filesDir, FILE_NAME))
                    .also { INSTANCE = it }
            }
    }
}

/** Turns sessions into file text and back. Throws on text it cannot read. */
interface ChatSessionCodec {
    fun encode(sessions: List<ChatSession>): String
    fun decode(text: String): List<ChatSession>
}

/** The existing on-disk format, so current histories load unchanged. */
object JsonChatSessionCodec : ChatSessionCodec {

    override fun encode(sessions: List<ChatSession>): String {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(JSONObject().apply {
                put("id", session.id)
                put("title", session.title)
                put("createdAt", session.createdAt)
                put("updatedAt", session.updatedAt)
                session.attachmentTitle?.let { put("attachmentTitle", it) }
                session.attachmentText?.let { put("attachmentText", it) }
                put("messages", JSONArray().apply {
                    session.messages.forEach { m ->
                        put(JSONObject().apply {
                            put("id", m.id)
                            put("text", m.text)
                            put("isUser", m.isUser)
                        })
                    }
                })
            })
        }
        return array.toString()
    }

    override fun decode(text: String): List<ChatSession> {
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            val messages = obj.optJSONArray("messages") ?: JSONArray()
            ChatSession(
                id = obj.getString("id"),
                title = obj.optString("title", ChatSession.DEFAULT_TITLE),
                createdAt = createdAt,
                updatedAt = obj.optLong("updatedAt", createdAt),
                messages = (0 until messages.length()).map { j ->
                    val m = messages.getJSONObject(j)
                    ChatMessage(id = m.getLong("id"), text = m.getString("text"), isUser = m.getBoolean("isUser"))
                },
                attachmentTitle = obj.optString("attachmentTitle").takeIf { obj.has("attachmentTitle") },
                attachmentText = obj.optString("attachmentText").takeIf { obj.has("attachmentText") }
            )
        }.sortedByDescending { it.updatedAt }
    }
}
