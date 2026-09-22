package com.gone.ai.chat

import com.gone.ai.model.ChatMessage
import com.gone.ai.model.ChatSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionStoreTest {

    @get:Rule val folder = TemporaryFolder()

    /** A readable stand-in for JSON, which the unit-test android.jar cannot run. */
    private object LineCodec : ChatSessionCodec {
        var failEncode = false
        override fun encode(sessions: List<ChatSession>): String {
            check(!failEncode) { "disk full" }
            return sessions.joinToString("\n") { "${it.id}|${it.title}|${it.updatedAt}|${it.messages.size}" }
        }
        override fun decode(text: String): List<ChatSession> = text.lines().filter { it.isNotBlank() }.map { line ->
            val f = line.split('|')
            require(f.size == 4) { "unreadable line: $line" }
            ChatSession(
                id = f[0], title = f[1], updatedAt = f[2].toLong(),
                messages = List(f[3].toInt()) { ChatMessage(it.toLong(), "m$it", isUser = it % 2 == 0) }
            )
        }
    }

    private fun session(id: String, title: String, updatedAt: Long, messages: Int = 0) =
        ChatSession(id = id, title = title, updatedAt = updatedAt,
            messages = List(messages) { ChatMessage(it.toLong(), "m$it", isUser = it % 2 == 0) })

    // The store's writer runs forever, so it lives in backgroundScope; runCurrent() lets it
    // write (advanceUntilIdle() skips background work).
    private fun TestScope.store(file: File) =
        ChatSessionStore(file, LineCodec, backgroundScope, clock = { 42L }).also { LineCodec.failEncode = false }

    @Test
    fun `a missing file loads as empty`() = runTest(StandardTestDispatcher()) {
        assertEquals(ChatSessionStore.LoadResult.Empty, store(File(folder.root, "chat.json")).load())
    }

    @Test
    fun `saves in quick succession write the newest snapshot`() = runTest(StandardTestDispatcher()) {
        val file = File(folder.root, "chat.json")
        val store = store(file)
        store.save(listOf(session("a", "first", 1)))
        store.save(listOf(session("a", "second", 2, messages = 3)))
        runCurrent()

        val loaded = store.load() as ChatSessionStore.LoadResult.Loaded
        assertEquals("second", loaded.sessions.single().title)
        assertEquals(3, loaded.sessions.single().messages.size)
    }

    @Test
    fun `a failed write keeps the previous copy`() = runTest(StandardTestDispatcher()) {
        val file = File(folder.root, "chat.json")
        val store = store(file)
        store.save(listOf(session("a", "kept", 1)))
        runCurrent()

        LineCodec.failEncode = true
        store.save(listOf(session("a", "lost", 2)))
        runCurrent()
        LineCodec.failEncode = false

        val loaded = store.load() as ChatSessionStore.LoadResult.Loaded
        assertEquals("kept", loaded.sessions.single().title)
    }

    @Test
    fun `a write interrupted before it finished does not replace the last good copy`() = runTest(StandardTestDispatcher()) {
        val file = File(folder.root, "chat.json")
        val store = store(file)
        store.save(listOf(session("a", "good", 1)))
        runCurrent()
        // What a crash mid-write leaves behind: a half-written temporary file.
        File(folder.root, "chat.json.new").writeText("half a li")

        val loaded = store.load() as ChatSessionStore.LoadResult.Loaded
        assertEquals("good", loaded.sessions.single().title)
    }

    @Test
    fun `an unreadable file is kept aside instead of being overwritten`() = runTest(StandardTestDispatcher()) {
        val file = File(folder.root, "chat.json")
        file.writeText("not a session")
        val store = store(file)

        val result = store.load()
        assertTrue(result is ChatSessionStore.LoadResult.Corrupt)
        val kept = (result as ChatSessionStore.LoadResult.Corrupt).keptAs!!
        assertEquals("chat.unreadable-42.json", kept.name)
        assertEquals("not a session", kept.readText())
        assertFalse(file.exists())

        // Starting over writes a new file and leaves the unreadable one alone.
        store.save(listOf(session("b", "fresh", 3)))
        runCurrent()
        assertEquals("not a session", kept.readText())
        assertEquals("fresh", (store.load() as ChatSessionStore.LoadResult.Loaded).sessions.single().title)
    }
}
