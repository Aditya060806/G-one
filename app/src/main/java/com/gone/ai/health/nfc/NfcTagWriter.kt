package com.gone.ai.health.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
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
        val ndef = Ndef.get(tag)
            ?: return WriteResult.Failure("Tag does not support NDEF. Use an NTAG213, NTAG215, or NTAG216 tag.")

        return try {
            ndef.connect()

            if (!ndef.isWritable) {
                return WriteResult.Failure("This tag is locked (read-only) and cannot be written.")
            }

            val fullText = payload.toOfflineText()
            val vcard = payload.toVCard()

            val textRecord = NdefRecord.createTextRecord("en", fullText)
            val vcardRecord = NdefRecord.createMime("text/vcard", vcard.toByteArray(Charsets.UTF_8))

            // 1. Attempt dual record (Readable text + 1-tap vCard) for larger tags (NTAG215/216)
            var message = NdefMessage(arrayOf(textRecord, vcardRecord))

            // 2. If too large, write full text record only
            if (message.byteArrayLength > ndef.maxSize) {
                message = NdefMessage(arrayOf(textRecord))
            }

            // 3. If still too large (e.g. tiny NTAG213 tag), use compact text
            if (message.byteArrayLength > ndef.maxSize) {
                val compactText = payload.toCompactOfflineText()
                val compactRecord = NdefRecord.createTextRecord("en", compactText)
                message = NdefMessage(arrayOf(compactRecord))
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

    /**
     * Write a URL NDEF record to [tag].
     *
     * @param tag   The discovered NFC tag.
     * @param url   The URL to encode (e.g. "https://g-one.app/e/7xK92pQ4Lm").
     * @return [WriteResult.Success] on success; [WriteResult.Failure] with a short
     *         human-readable reason on any error.
     */
    fun write(tag: Tag, url: String): WriteResult {
        val ndef = Ndef.get(tag)
            ?: return WriteResult.Failure("Tag does not support NDEF. Use an NTAG213, NTAG215, or NTAG216 tag.")

        return try {
            val urlRecord = NdefRecord.createUri(url)
            val message   = NdefMessage(arrayOf(urlRecord))

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
}
