package com.gone.ai.health.domain

import java.security.MessageDigest
import java.security.SecureRandom

/** Public read ID cannot be used to derive the separate private write key. */
object EmergencyCapability {
    fun newWriteKey(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.hex()
    fun readId(writeKey: String): String {
        require(writeKey.matches(Regex("[a-f0-9]{64}")))
        return MessageDigest.getInstance("SHA-256").digest(writeKey.toByteArray(Charsets.UTF_8)).hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
