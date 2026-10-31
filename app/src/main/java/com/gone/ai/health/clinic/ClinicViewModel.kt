package com.gone.ai.health.clinic

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.data.UserProfile
import com.gone.ai.data.library.GoneDatabase
import com.gone.ai.health.data.EmergencyRepository
import com.gone.ai.health.data.WellnessLogStore
import com.gone.ai.health.data.anomalyType
import com.gone.ai.health.data.severityEnum
import com.gone.ai.health.data.toDomain
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.health.insight.AlertRecord
import com.gone.ai.health.nfc.QrCodeGenerator
import com.gone.ai.health.service.HealthMonitoringService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Clinic Mode: the last days of the wearer's records as a summary for a doctor, shown on
 * screen and as a QR code of plain text that any phone camera can read without an app or
 * the internet.
 */
class ClinicViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val loading: Boolean = true,
        val days: Int = DEFAULT_DAYS,
        val include: ClinicInclude = ClinicInclude(),
        val summary: ClinicSummary? = null,
        val qrText: String = "",
        val qr: Bitmap? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val db = GoneDatabase.getInstance(app)
    private val emergency = EmergencyRepository(
        dao = db.emergencyDao(),
        vitalsDao = db.vitalsDao(),
        anomalyDao = db.anomalyDao(),
        patientId = PATIENT
    )

    /** Loaded once per period; toggles only rebuild the text. */
    private var input: ClinicInput? = null

    init { load(DEFAULT_DAYS) }

    fun setDays(days: Int) {
        if (days == _state.value.days && input != null) return
        load(days)
    }

    fun setInclude(include: ClinicInclude) {
        _state.value = _state.value.copy(include = include)
        rebuild()
    }

    private fun load(days: Int) {
        _state.value = _state.value.copy(loading = true, days = days)
        viewModelScope.launch {
            input = withContext(Dispatchers.IO) { read(days) }
            rebuild()
        }
    }

    private fun rebuild() {
        val data = input ?: return
        val include = _state.value.include
        viewModelScope.launch {
            val (summary, text, qr) = withContext(Dispatchers.Default) {
                val summary = ClinicSummaryBuilder.build(data, include, System.currentTimeMillis(), ZoneId.systemDefault())
                val text = ClinicSummaryBuilder.toQrText(summary)
                Triple(summary, text, QrCodeGenerator.encode(text, QR_SIZE_PX))
            }
            _state.value = _state.value.copy(loading = false, summary = summary, qrText = text, qr = qr)
        }
    }

    private suspend fun read(days: Int): ClinicInput {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        val from = now - days * DAY
        val zone = ZoneId.systemDefault()
        val fromDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val profile = emergency.ensureProfile()
        val user = UserProfile.load(app)
        val journal = WellnessLogStore(app).logs.value

        val readings = ArrayList<VitalsSample>()
        var cursor = from
        while (readings.size < MAX_READINGS) {
            val page = db.vitalsDao().between(PATIENT, cursor, now, PAGE)
            page.mapTo(readings) { it.toDomain() }
            if (page.size < PAGE) break
            cursor = page.last().timestamp + 1
        }

        return ClinicInput(
            name = user.name,
            age = user.age,
            bloodGroup = profile.bloodGroup,
            allergies = profile.allergies,
            conditions = profile.chronicConditions,
            medications = profile.medications,
            implants = profile.implantedDevices,
            readings = readings,
            alerts = db.anomalyDao().between(PATIENT, from, now).map { AlertRecord(it.anomalyType(), it.severityEnum(), it.createdAt) },
            sleep = journal.sleep.filter { !it.wakeDate.isBefore(fromDate) },
            stress = journal.stress.filter { !it.date.isBefore(fromDate) },
            from = from,
            to = now
        )
    }

    companion object {
        const val DEFAULT_DAYS = 7
        val DAY_CHOICES = listOf(3, 7, 14, 30)
        private const val DAY = 24L * 60 * 60 * 1000
        private const val PATIENT = HealthMonitoringService.DEFAULT_PATIENT_ID
        private const val QR_SIZE_PX = 900
        private const val MAX_READINGS = 300_000
        private const val PAGE = 20_000
    }
}
