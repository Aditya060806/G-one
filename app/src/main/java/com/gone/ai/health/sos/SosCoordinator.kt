package com.gone.ai.health.sos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the SOS needs to send, read fresh each time: settings can change during a countdown. */
data class SosConfig(
    val number: String,
    val name: String?,
    /** How long the wearer has to stop an SOS raised by a fall alone; 0 sends it at once. */
    val fallCancelSeconds: Int,
    val callAfter: Boolean
)

/** The outcome of one attempt to text or to call. */
enum class SosResult(val sent: Boolean, val words: String) {
    SENT(true, "sent"),
    HANDED_OVER(true, "handed to the phone; no confirmation came back"),
    CALL_PLACED(true, "call started"),
    NO_PERMISSION(false, "G-one is not allowed to send texts or make calls"),
    NO_SERVICE(false, "no mobile signal, or airplane mode is on"),
    CANNOT_SEND(false, "this phone could not send it")
}

/** What actually texts and calls. An interface so the countdown logic runs in plain JVM tests. */
interface SosSender {
    suspend fun sendSms(number: String, text: String): SosResult
    fun placeCall(number: String): SosResult
}

/** One SOS, as it went out, for the record shown in Settings. */
data class SosRecord(
    val at: Long,
    val number: String,
    val reasons: List<String>,
    val sms: SosResult,
    val call: SosResult?,
    val test: Boolean = false
)

/**
 * Gathers alerts that arrive together into one message and sends it: the text first, then — if
 * wanted — the call.
 *
 * A FALL CAN BE STOPPED; NOTHING ELSE WAITS. A fall is one hard impact, and a wrist knocked
 * against a table makes the same impact. So an SOS raised by a fall alone waits
 * [SosConfig.fallCancelSeconds] with an alarm and an "I'm OK" button ([cancel]); nothing is asked
 * of the wearer, and if they do nothing it sends. Low oxygen, heart-rate and every other alert
 * are measured over time and go after only [BATCH_MILLIS], which exists so that alerts raised on
 * the same reading go in one message rather than several. If one of those arrives while a fall
 * is waiting, the wait ends and both go together.
 *
 * Once sending has begun it runs to the end even if monitoring stops: a message half-sent is
 * worse than one sent.
 */
class SosCoordinator(
    private val scope: CoroutineScope,
    /** Null when the automatic SOS is off, or there is no usable number. */
    private val config: () -> SosConfig?,
    private val sender: SosSender,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRecord: (SosRecord) -> Unit = {}
) {

    /**
     * An SOS waiting to go.
     *
     * @param cancellable true while it is a fall's "I'm OK" window, which the wearer is shown;
     *   false for the short gathering pause, which they are not.
     * @param willCall whether a call follows the text, so the countdown can say so.
     */
    data class Pending(
        val reasons: List<String>,
        val sendAt: Long,
        val cancellable: Boolean = false,
        val willCall: Boolean = false
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private var countdown: Job? = null

    /**
     * An alert that warrants an SOS. Starts the wait, or, if one is running, adds its reason to
     * the message already waiting. False when the automatic SOS is off.
     *
     * @param fall true for a fall, which the wearer may stop; see the class comment.
     */
    fun raise(reason: String, fall: Boolean = false): Boolean {
        val cfg = config() ?: return false
        val wait = maxOf(if (fall) cfg.fallCancelSeconds * 1000L else 0L, BATCH_MILLIS)
        val now = clock()
        val waiting = _pending.value
        if (waiting != null) {
            val sendAt = minOf(waiting.sendAt, now + wait)
            _pending.value = waiting.copy(
                reasons = if (reason in waiting.reasons) waiting.reasons else waiting.reasons + reason,
                sendAt = sendAt,
                // An alert that cannot wait ends a fall's window.
                cancellable = waiting.cancellable && wait > BATCH_MILLIS
            )
            if (sendAt < waiting.sendAt) sendAfter(sendAt - now)
            return true
        }
        _pending.value = Pending(listOf(reason), now + wait, cancellable = wait > BATCH_MILLIS, willCall = cfg.callAfter)
        sendAfter(wait)
        return true
    }

    private fun sendAfter(wait: Long) {
        countdown?.cancel()
        countdown = scope.launch {
            delay(wait)
            val due = _pending.value ?: return@launch
            _pending.value = null
            withContext(NonCancellable) { dispatch(due.reasons) }
        }
    }

    /** "I'm OK": nothing is sent. False when no SOS was waiting. */
    fun cancel(): Boolean {
        val had = _pending.value != null
        countdown?.cancel()
        countdown = null
        _pending.value = null
        return had
    }

    /**
     * Sends a waiting SOS at once. For when Android ends monitoring in the middle of a countdown:
     * losing the SOS would be worse than sending it early. (The wearer stopping monitoring is an
     * "I'm OK" and goes through [cancel] instead.)
     */
    suspend fun sendPendingNow() {
        val due = _pending.value ?: return
        countdown?.cancel()
        countdown = null
        _pending.value = null
        dispatch(due.reasons)
    }

    /** Texts, then calls. Settings are read again: the SOS may have been turned off meanwhile. */
    private suspend fun dispatch(reasons: List<String>) {
        val cfg = config() ?: return
        val text = SosMessage.compose(cfg.name, reasons, clock())
        val sms = try { sender.sendSms(cfg.number, text) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { SosResult.CANNOT_SEND }
        var call: SosResult? = null
        if (cfg.callAfter) {
            // After the text, so the contact knows why the phone is ringing.
            delay(CALL_AFTER_MILLIS)
            call = runCatching { sender.placeCall(cfg.number) }.getOrDefault(SosResult.CANNOT_SEND)
        }
        onRecord(SosRecord(clock(), cfg.number, reasons, sms, call))
    }

    companion object {
        /** Alerts raised on one reading arrive within milliseconds: this gathers them. */
        const val BATCH_MILLIS = 1_500L
        /** Between the text and the call, so the text arrives first. */
        const val CALL_AFTER_MILLIS = 5_000L
        /** Seconds a fall's SOS can be stopped in; 0 sends it at once like every other alert. */
        val FALL_CANCEL_CHOICES = listOf(0, 10, 20, 30)

        /**
         * Send at once, with no chance to stop it. The wearer asked for a hard impact to text
         * the contact immediately; the cost is that a hard knock against a table does too.
         * Settings offers 10, 20 or 30 seconds for anyone who wants the chance to cancel.
         */
        const val DEFAULT_FALL_CANCEL_SECONDS = 0
    }
}

/**
 * From Settings: a text marked as a test, straight away, with no countdown and no call. Works
 * whether or not the automatic SOS is on, so the number and the permission can be checked first.
 */
suspend fun sendTestSos(sender: SosSender, number: String, name: String?, now: Long): SosRecord {
    val sms = sender.sendSms(number, SosMessage.compose(name, emptyList(), now, test = true))
    return SosRecord(now, number, emptyList(), sms, call = null, test = true)
}
