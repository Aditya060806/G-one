package com.gone.ai.health.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TemperatureTest {
    @Test
    fun `converts Celsius storage values to Fahrenheit display values`() {
        assertEquals(98.6f, Temperature.fahrenheit(37f), 0.001f)
        assertEquals("98.6 °F", Temperature.fahrenheitText(37f))
        assertEquals("99 °F", Temperature.fahrenheitText(37.2222f, decimals = 0))
    }
}
