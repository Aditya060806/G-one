package com.gone.ai.health.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyIdGeneratorTest {

    private val allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789".toSet()
    private val ambiguousChars = setOf('0', 'O', '1', 'I', 'l')

    @Test
    fun `generate produces 10 characters by default`() {
        val id = EmergencyIdGenerator.generate()
        assertEquals(10, id.length)
    }

    @Test
    fun `generate respects custom length`() {
        assertEquals(8, EmergencyIdGenerator.generate(8).length)
        assertEquals(12, EmergencyIdGenerator.generate(12).length)
        assertEquals(20, EmergencyIdGenerator.generate(20).length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `generate rejects length less than 8`() {
        EmergencyIdGenerator.generate(7)
    }

    @Test
    fun `all characters are from unambiguous alphabet`() {
        repeat(100) {
            val id = EmergencyIdGenerator.generate()
            for (ch in id) {
                assertTrue("Character '$ch' should be in allowed base-58 alphabet", ch in allowedChars)
                assertFalse("Character '$ch' must not be an ambiguous character", ch in ambiguousChars)
            }
        }
    }

    @Test
    fun `generates unique IDs across successive calls`() {
        val count = 1000
        val ids = (1..count).map { EmergencyIdGenerator.generate() }.toSet()
        assertEquals(count, ids.size)
    }
}
