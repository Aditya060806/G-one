package com.gone.ai.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryQueryTest {

    @Test
    fun `words become prefix matches`() {
        assertEquals("blood* pressure*", LibraryQuery.ftsMatch("blood pressure"))
    }

    @Test
    fun `characters FTS would read as syntax are dropped`() {
        assertEquals("HbA1c* 6* 1*", LibraryQuery.ftsMatch("HbA1c: 6.1"))
        assertEquals("covid* 19*", LibraryQuery.ftsMatch("covid-19"))
        assertEquals("a* b*", LibraryQuery.ftsMatch("a:b"))
        assertEquals("NOT* dose*", LibraryQuery.ftsMatch("\"NOT\" (dose)*"))
    }

    @Test
    fun `letters in other scripts are kept`() {
        assertEquals("रक्त* जांच*", LibraryQuery.ftsMatch("रक्त जांच"))
    }

    @Test
    fun `nothing searchable gives no query`() {
        assertNull(LibraryQuery.ftsMatch(""))
        assertNull(LibraryQuery.ftsMatch("  :- *\"  "))
    }
}
