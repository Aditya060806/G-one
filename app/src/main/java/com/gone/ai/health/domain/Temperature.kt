package com.gone.ai.health.domain

import java.util.Locale

/** Celsius remains the storage/protocol unit; presentation is Fahrenheit. */
object Temperature {
    fun fahrenheit(celsius: Float): Float = celsius * 9f / 5f + 32f

    fun fahrenheitText(celsius: Float, decimals: Int = 1): String =
        String.format(Locale.US, "%.${decimals}f °F", fahrenheit(celsius))

    fun fahrenheitNumber(celsius: Float, decimals: Int = 1): String =
        String.format(Locale.US, "%.${decimals}f", fahrenheit(celsius))
}
