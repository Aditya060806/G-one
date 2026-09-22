package com.gone.ai.health.domain

import org.junit.Assert.*
import org.junit.Test

class EmergencyCapabilityTest {
    @Test fun `capabilities use independent 256 bit keys`() {
        val key = EmergencyCapability.newWriteKey()
        assertTrue(key.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(key, EmergencyCapability.readId(key))
        assertNotEquals(key, EmergencyCapability.newWriteKey())
        assertEquals(EmergencyCapability.readId(key), EmergencyCapability.readId(key))
    }
    @Test(expected = IllegalArgumentException::class) fun `legacy short ids cannot authorize publishing`() {
        EmergencyCapability.readId("short-id")
    }
    @Test fun `digest matches web protocol UTF8 hex key vector`() {
        assertEquals("60e05bd1b195af2f94112fa7197a5c88289058840ce7c6df9693756bc6250f55", EmergencyCapability.readId("0".repeat(64)))
    }
}
