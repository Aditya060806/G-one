package com.gone.ai.health.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import com.gone.ai.health.domain.EmergencyPayload

/**
 * Writes an NDEF message to an NFC tag.
 *
 * OFFLINE GUARANTEE
 *
 * In [writeOfflineMedicalId], the emergency record is written directly into the passive
 * tag chip memory as an NDEF Text Record + vCard MIME record.
 * Reading text/vCard records requires a compatible NFC reader application. Generic phone
 * background tag handling is most interoperable with an HTTPS URI; that needs internet.
 *
 * THREAD SAFETY
 *
 * [write] and [writeOfflineMedicalId] are blocking (NDEF.connect() is a synchronous I/O call).
 * Always call on a background thread (Dispatchers.IO). The ViewModel does this.
 */
class NfcTagWriter {

    sealed class WriteResult {
        object Success : WriteResult()
        data class Failure(val reason: String) : WriteResult()
    }

    /**
     * Writes the complete, self-contained offline Emergency Medical Record directly
     * to the NFC tag chip memory.
     *
     * GUARANTEES:
     * - ZERO internet or cellular data required to read.
     * - Compatible NFC reader software is required for offline text/vCard records.
     * - Primary record is NDEF RTD_TEXT ("en") formatted for immediate clinical readability.
     * - If space allows (e.g. NTAG215 / NTAG216), includes a vCard record for instant 1-tap
     *   emergency contact dialing and saving to contacts.
     * - If tag is capacity constrained (e.g. NTAG213, ~144 bytes), automatically falls back
     *   to compact text representation so it never exceeds tag memory.
     */
    fun writeOfflineMedicalId(tag: Tag, payload: EmergencyPayload): WriteResult {
        // A blank tag has no NDEF structure yet, so Ndef.get returns null for it; formatting
        // writes that structure and the record together.
        val ndef = Ndef.get(tag) ?: return formatAndWrite(tag, offlineCandidates(payload))

        return try {
            ndef.connect()

            if (!ndef.isWritable) {
                return WriteResult.Failure("This tag is locked (read-only) and cannot be written.")
            }

            // Largest first: text and a one-tap vCard, then text alone, then compact text for a
            // small tag such as an NTAG213.
            val candidates = offlineCandidates(payload)
            val message = candidates.firstOrNull { it.byteArrayLength <= ndef.maxSize } ?: candidates.last()

            if (ndef.maxSize < message.byteArrayLength) {
                return WriteResult.Failure(
                    "Message is ${message.byteArrayLength} bytes but the tag only holds " +
                    "${ndef.maxSize} bytes. Use a larger tag (NTAG215 or NTAG216)."
                )
            }

            ndef.writeNdefMessage(message)
            if (ndef.ndefMessage?.toByteArray()?.contentEquals(message.toByteArray()) == true)
                WriteResult.Success
            else WriteResult.Failure("Write could not be verified. Keep the tag still and try again.")

        } catch (e: android.nfc.FormatException) {
            WriteResult.Failure("Tag format error: ${e.message ?: "unknown"}. Try formatting the tag first.")
        } catch (e: Exception) {
            WriteResult.Failure(e.message ?: "Unknown NFC error. Move the tag away and try again.")
        } finally {
            runCatching { ndef.close() }
        }
    }

    /**
     * Write a URL NDEF record to [tag].
     *
     * @param tag   The discovered NFC tag.
     * @param url   The URL to encode (e.g. "https://g-one.app/e/7xK92pQ4Lm").
     * @return [WriteResult.Success] on success; [WriteResult.Failure] with a short
     *         human-readable reason on any error.
     */
    fun write(tag: Tag, url: String): WriteResult {
        val message = NdefMessage(arrayOf(NdefRecord.createUri(url)))

        // A tag out of the packet is usually blank: it holds no NDEF structure yet, so
        // Ndef.get returns null for it. Formatting writes that structure and the message
        // together. Without this, a perfectly good NTAG213 was refused as unsupported.
        val ndef = Ndef.get(tag) ?: return formatAndWrite(tag, listOf(message))

        return try {
            ndef.connect()

            if (!ndef.isWritable) {
                return WriteResult.Failure("This tag is locked (read-only) and cannot be written.")
            }
            if (ndef.maxSize < message.byteArrayLength) {
                return WriteResult.Failure(
                    "Message is ${message.byteArrayLength} bytes but the tag only holds " +
                    "${ndef.maxSize} bytes. Use a larger tag (NTAG215 or NTAG216)."
                )
            }

            ndef.writeNdefMessage(message)
            if (ndef.ndefMessage?.toByteArray()?.contentEquals(message.toByteArray()) == true)
                WriteResult.Success
            else WriteResult.Failure("Write could not be verified. Keep the tag still and try again.")

        } catch (e: android.nfc.FormatException) {
            WriteResult.Failure("Tag format error: ${e.message ?: "unknown"}. Try formatting the tag first.")
        } catch (e: Exception) {
            WriteResult.Failure(e.message ?: "Unknown NFC error. Move the tag away and try again.")
        } finally {
            runCatching { ndef.close() }
        }
    }

    /** The offline record in the forms it can take, largest first. */
    private fun offlineCandidates(payload: EmergencyPayload): List<NdefMessage> {
        val text = NdefRecord.createTextRecord("en", payload.toOfflineText())
        val vcard = NdefRecord.createMime("text/vcard", payload.toVCard().toByteArray(Charsets.UTF_8))
        val compact = NdefRecord.createTextRecord("en", payload.toCompactOfflineText())
        return listOf(
            NdefMessage(arrayOf(text, vcard)),
            NdefMessage(arrayOf(text)),
            NdefMessage(arrayOf(compact))
        )
    }

    /**
     * Writes to a tag that carries no NDEF structure yet, which is how most tags ship.
     *
     * Formatting and writing are one operation here, and the tag's capacity is not readable
     * beforehand, so the candidates are tried largest first until one fits.
     */
    private fun formatAndWrite(tag: Tag, candidates: List<NdefMessage>): WriteResult {
        val formatable = NdefFormatable.get(tag)
            ?: return WriteResult.Failure(
                "This tag cannot hold a record. Use an NTAG213, NTAG215 or NTAG216 tag."
            )

        return try {
            formatable.connect()
            var lastError: String? = null
            for (message in candidates) {
                // A failed format leaves the tag as it was, so the next candidate can try.
                try {
                    formatable.format(message)
                    return WriteResult.Success
                } catch (e: Exception) {
                    lastError = e.message
                }
            }
            WriteResult.Failure(
                lastError?.let { "The tag could not be prepared: $it" }
                    ?: "The tag could not be prepared. Hold it still against the phone and try again."
            )
        } catch (e: Exception) {
            WriteResult.Failure(e.message ?: "Unknown NFC error. Move the tag away and try again.")
        } finally {
            runCatching { formatable.close() }
        }
    }
}
