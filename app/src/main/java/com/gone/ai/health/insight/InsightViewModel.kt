package com.gone.ai.health.insight

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.ai.repository.AIRepository
import com.gone.ai.data.library.GoneDatabase
import com.gone.ai.health.data.WellnessLogStore
import com.gone.ai.health.data.anomalyType
import com.gone.ai.health.data.severityEnum
import com.gone.ai.health.data.toDomain
import com.gone.ai.health.service.HealthMonitoringService
import com.gone.ai.voice.AppLanguage
import com.gone.ai.voice.Heard
import com.gone.ai.voice.LanguagePreferences
import com.gone.ai.voice.SpeechInput
import com.gone.ai.voice.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/**
 * "Ask your health": a typed or spoken question, read into a [HealthQuery], answered from
 * this phone's own records by [InsightEngine].
 *
 * The fixed-rule [QuestionParser] is tried first. Only if it cannot read the question is the
 * on-device model asked, and then only to choose a query from a fixed list
 * ([QueryInterpreter]); it never sees the readings and never writes the answer.
 */
class InsightViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Phase {
        data object Idle : Phase
        data object Listening : Phase
        /** [usingModel] is true while the on-device model reads an unusual question. */
        data class Working(val usingModel: Boolean) : Phase
        data class Answered(val insight: Insight, val query: HealthQuery, val readByModel: Boolean) : Phase
        data object NotUnderstood : Phase
        data class Failed(val message: String) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** What the recogniser has heard so far, while listening. */
    private val _heard = MutableStateFlow("")
    val heard: StateFlow<String> = _heard.asStateFlow()

    val language: StateFlow<AppLanguage> = LanguagePreferences.language(app)

    private val speech = SpeechOutput.getInstance(app)
    val speaking: StateFlow<Boolean> = speech.speaking
    val voiceNotice: StateFlow<String?> = speech.languageNotice

    private val input = SpeechInput(app)
    val canListen: Boolean get() = input.isAvailable

    private val db = GoneDatabase.getInstance(app)
    private val repository = AIRepository.getInstance(app)
    private var lease: AutoCloseable? = null
    private var job: Job? = null

    fun setLanguage(value: AppLanguage) = LanguagePreferences.setLanguage(getApplication(), value)

    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty()) return
        input.cancel()
        speech.stop()
        job?.cancel()
        job = viewModelScope.launch {
            try {
                var readByModel = false
                val query = QuestionParser.parse(text) ?: run {
                    _phase.value = Phase.Working(usingModel = true)
                    readByModel = true
                    interpretWithModel(text)
                }
                if (query == null) {
                    _phase.value = Phase.NotUnderstood
                    return@launch
                }
                _phase.value = Phase.Working(usingModel = false)
                val insight = answer(query, language.value)
                _phase.value = Phase.Answered(insight, query, readByModel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _phase.value = Phase.Failed(e.message ?: "Could not answer that.")
            }
        }
    }

    /** Re-answers the last question in the current language, e.g. after switching to Hindi. */
    fun refresh() {
        val answered = _phase.value as? Phase.Answered ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _phase.value = Phase.Answered(answer(answered.query, language.value), answered.query, answered.readByModel)
        }
    }

    fun listen() {
        speech.stop()
        _heard.value = ""
        _phase.value = Phase.Listening
        input.start { event ->
            when (event) {
                is Heard.Partial -> _heard.value = event.text
                is Heard.Final -> {
                    _heard.value = event.text
                    if (event.text.isBlank()) _phase.value = Phase.Idle else ask(event.text)
                }
                is Heard.Failed -> {
                    // The offline pack for this language may be missing; the next try uses the default service.
                    input.useDefaultService()
                    _phase.value = Phase.Failed(
                        if (language.value == AppLanguage.HINDI) "सुना नहीं जा सका। फिर से बोलें या लिखें।"
                        else "Didn't catch that. Try again or type the question."
                    )
                }
                else -> Unit
            }
        }
    }

    fun stopListening() = input.stop()

    fun readAloud() {
        val answered = _phase.value as? Phase.Answered ?: return
        speech.stop()
        speech.speak(answered.insight.answer)
    }

    fun stopSpeaking() = speech.stop()

    private suspend fun interpretWithModel(question: String): HealthQuery? {
        if (lease == null) lease = repository.acquire(OWNER)
        val reply = withTimeoutOrNull(MODEL_TIMEOUT_MILLIS) {
            val text = StringBuilder()
            // Stop as soon as one full line is out; the model is not asked for more.
            repository.generate(emptyList(), QueryInterpreter.prompt(question))
                .transformWhile { token ->
                    text.append(token)
                    emit(Unit)
                    text.length < MAX_REPLY_CHARS && !text.toString().substringAfter("range=", "").contains('\n')
                }
                .collect { }
            text.toString()
        } ?: return null
        return QueryInterpreter.parseModelLine(reply)
    }

    private suspend fun answer(query: HealthQuery, lang: AppLanguage): Insight = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val periods = InsightEngine.periods(query, now, zone)
        val journal = WellnessLogStore(getApplication()).logs.value
        suspend fun load(span: Pair<Long, Long>): InsightData = InsightData(
            readings = if (query.metric.isReading) {
                readings(span)
            } else emptyList(),
            alerts = if (query.metric == InsightMetric.ALERTS) {
                db.anomalyDao().between(PATIENT, span.first, span.second)
                    .map { AlertRecord(it.anomalyType(), it.severityEnum(), it.createdAt) }
            } else emptyList(),
            sleep = journal.sleep,
            stress = journal.stress
        )
        InsightEngine.answer(query, load(periods.main), periods.compare?.let { load(it) }, now, zone, lang)
    }

    /** Every reading in [span], a page at a time, up to [MAX_READINGS]. */
    private suspend fun readings(span: Pair<Long, Long>): List<com.gone.ai.health.domain.VitalsSample> {
        val out = ArrayList<com.gone.ai.health.domain.VitalsSample>()
        var from = span.first
        while (out.size < MAX_READINGS) {
            val page = db.vitalsDao().between(PATIENT, from, span.second, PAGE)
            page.mapTo(out) { it.toDomain() }
            if (page.size < PAGE) break
            from = page.last().timestamp + 1
        }
        return out
    }

    override fun onCleared() {
        input.cancel()
        speech.stop()
        lease?.close()
        lease = null
    }

    private companion object {
        const val OWNER = "ask-your-health"
        const val PATIENT = HealthMonitoringService.DEFAULT_PATIENT_ID
        const val MODEL_TIMEOUT_MILLIS = 45_000L
        const val MAX_REPLY_CHARS = 200
        /** About two weeks of continuous five-second readings; bounded in memory. */
        const val MAX_READINGS = 250_000
        const val PAGE = 20_000
    }
}
