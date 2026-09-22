package com.gone.ai.data.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

enum class EntryType(val label: String) {
    PDF_SUMMARY   ("PDF Summary"),
    OCR           ("OCR Scan"),
    SCREENSHOT    ("Screenshot"),
    QUIZ          ("Quiz"),
    NOTE          ("Note"),
    /** A document the person added themselves: a lab report, prescription, letter. */
    MEDICAL_RECORD("Medical record")
}

@Entity(tableName = "library_entries")
data class LibraryEntry(
    @PrimaryKey(autoGenerate = true)
    val id         : Long   = 0,
    val type       : EntryType,
    val title      : String,
    val content    : String,
    val sourceInfo : String = "",   // original filename or hint
    val createdAt  : Long   = System.currentTimeMillis(),
    /**
     * True when the person chose to let the chat use this entry when answering about them.
     * Added in database version 4; existing entries read as not shared.
     */
    @ColumnInfo(defaultValue = "0")
    val useInAi    : Boolean = false
)

/**
 * FTS4 virtual table — mirrors title + content for full-text search.
 * Room keeps it in sync via triggers when library_entries is modified.
 * rowid in FTS table == id in library_entries.
 */
@Fts4(contentEntity = LibraryEntry::class)
@Entity(tableName = "library_entries_fts")
data class LibraryEntryFts(
    val title  : String,
    val content: String
)
