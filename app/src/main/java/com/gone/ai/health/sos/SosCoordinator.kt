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
    val countdownSeconds: Int,
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
 * Runs the countdown between an alert and the SOS, gathers alerts that arrive together into one
 * message, and sends: the text first, then — if wanted — the call.
 *
 * THE COUNTDOWN. By default the wearer has [SosConfig.countdownSeconds] to tap "I'm OK" ([cancel])
 * before anything is sent; nothing is asked of them, and if they do nothing it sends. A wearable
 * knocked against a table would otherwise text their contact about nothing. Set to zero it sends
 * at once, after only [BATCH_MILLIS], so that alerts raised on the same reading go in one message
 * rather than several.
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

    /** An SOS waiting out its countdown. */
    data class Pending(val reasons: List<String>, val sendAt: Long)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private var countdown: Job? = null

    /**
     * An alert that warrants an SOS. Starts the countdown, or, if one is running, adds its reason
     * to the message already waiting. False when the automatic SOS is off.
     */
    fun raise(reason: String): Boolean {
        val cfg = config() ?: return false
        val waiting = _pending.value
        if (waiting != null) {
            if (reason !in waiting.reasons) _pending.value = waiting.copy(reasons = waiting.reasons + reason)
            return true
        }
        val wait = maxOf(cfg.countdownSeconds * 1000L, BATCH_MILLIS)
        _pending.value = Pending(listOf(reason), clock() + wait)
        countdown = scope.launch {
            delay(wait)
            val due = _pending.value ?: return@launch
            _pending.value = null
            withContext(NonCancellable) { dispatch(due.reasons) }
        }
        return true
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
        val COUNTDOWN_CHOICES = listOf(0, 15, 30, 60)
        const val DEFAULT_COUNTDOWN_SECONDS = 0
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
