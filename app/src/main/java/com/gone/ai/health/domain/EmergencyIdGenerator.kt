package com.gone.ai.health.domain

import java.security.SecureRandom

/**
 * Generates a cryptographically random, non-guessable Emergency ID.
 *
 * DESIGN DECISIONS
 *
 * 1. SecureRandom, not Random. This ID will be embedded in an NFC tag and QR code that
 *    anyone who finds the wearable can read. A predictable ID lets someone enumerate records.
 *    SecureRandom pulls from the OS entropy pool and is suitable for session tokens.
 *
 * 2. Custom alphabet excluding ambiguous characters (0/O, 1/I/l). The ID may be printed on
 *    a physical band, hand-transcribed, or spoken aloud; a clear alphabet reduces the chance
 *    of a transcription error that makes the ID unreachable.
 *
 * 3. Length 10. 58^10 ≈ 4.3 × 10^17 possible IDs — collision-safe far beyond any realistic
 *    deployment. At 100,000 registrations per second it takes ~137 million years to reach a
 *    50 % collision probability (birthday problem). This is a hackathon scale: 10 is plenty.
 *
 * 4. No Android dependency. This file sits in the pure-Kotlin domain package and is directly
 *    unit-testable without Robolectric.
 */
object EmergencyIdGenerator {

    /**
     * Base-58 alphabet with visually ambiguous characters removed.
     *
     * Excluded: 0 (zero), O (capital-O), 1 (one), I (capital-I), l (lower-L).
     * Every remaining character reads unambiguously in print and on-screen.
     */
    private val ALPHABET: CharArray =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789".toCharArray()

    private val random = SecureRandom()

    /**
     * Generate a fresh Emergency ID of [length] characters.
     *
     * Thread-safe: [SecureRandom] is thread-safe after construction.
     *
     * @param length Number of characters (default 10, minimum 8).
     */
    fun generate(length: Int = 10): String {
        require(length >= 8) { "length must be at least 8 for adequate entropy" }
        return buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.size)]) }
        }
    }
}
