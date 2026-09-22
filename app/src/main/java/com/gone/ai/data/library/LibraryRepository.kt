package com.gone.ai.data.library

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext

/**
 * LibraryRepository
 *
 * Single public API for all library operations.
 * ViewModels call save() after successful generation — fire-and-forget on IO dispatcher.
 * Never touches the AI pipeline.
 */
class LibraryRepository private constructor(context: Context) {

    private val dao = GoneDatabase.getInstance(context).libraryDao()

    companion object {
        private const val TAG = "LibraryRepository"
        @Volatile private var INSTANCE: LibraryRepository? = null

        fun getInstance(context: Context): LibraryRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Save an entry. Always call from Dispatchers.IO.
     * Auto-generates a title from the first line of content if none provided.
     */
    suspend fun save(
        type       : EntryType,
        content    : String,
        title      : String    = "",
        sourceInfo : String    = "",
        useInAi    : Boolean   = false
    ): Long = withContext(Dispatchers.IO) {
        val resolvedTitle = title.ifBlank {
            content.lines().firstOrNull { it.isNotBlank() }
                ?.take(60)
                ?.trimEnd()
                ?: type.label
        }
        val entry = LibraryEntry(
            type       = type,
            title      = resolvedTitle,
            content    = content,
            sourceInfo = sourceInfo,
            useInAi    = useInAi
        )
        val id = dao.insert(entry)
        Log.i(TAG, "Saved ${type.name} entry id=$id")
        id
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
        Log.i(TAG, "Deleted entry id=$id")
    }

    suspend fun setUseInAi(id: Long, use: Boolean) = withContext(Dispatchers.IO) {
        dao.setUseInAi(id, use)
    }

    /** Entries marked "Use in AI answers", newest first. */
    suspend fun sharedWithAi(): List<LibraryEntry> = withContext(Dispatchers.IO) { dao.sharedWithAi() }

    /** Put back an entry deleted moments ago (Undo), with its original id and date. */
    suspend fun restore(entry: LibraryEntry) = withContext(Dispatchers.IO) {
        dao.insert(entry)
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    fun getAll(): Flow<List<LibraryEntry>>              = dao.getAll()
    fun getByType(type: EntryType): Flow<List<LibraryEntry>> = dao.getByType(type)
    fun observe(id: Long): Flow<LibraryEntry?>          = dao.observeById(id)

    /**
     * Full-text search. The typed text is reduced to a safe prefix query by [LibraryQuery];
     * a query SQLite still rejects shows no results rather than crashing the screen.
     */
    fun search(raw: String): Flow<List<LibraryEntry>> {
        val match = LibraryQuery.ftsMatch(raw) ?: return dao.getAll()
        return dao.search(match).catch { e ->
            Log.w(TAG, "Search failed: ${e.javaClass.simpleName}")
            emit(emptyList())
        }
    }
}
