package com.gone.ai.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.health.data.WellnessLogStore
import com.gone.ai.health.data.toDomain
import com.gone.ai.health.environment.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CitySearchState(val busy: Boolean = false, val results: List<AqiPlace> = emptyList(), val problem: String? = null)

class CompanionViewModel(app: Application) : AndroidViewModel(app) {
    private val air = AirQualityRepository(AndroidAqiStore(app), OpenMeteoClient())
    val aqi = air.state
    val journal = WellnessLogStore(app)
    val area = AreaAwareness.repository(app).state
    private val estimate = MutableStateFlow<com.gone.ai.ml.SleepEstimate?>(null)
    /** Last night's sleep from the wearable's movement, by the on-device sleep model; null when it cannot say. */
    val sleepEstimate = estimate.asStateFlow()
    private var areaJob: Job? = null
    private val searchState = MutableStateFlow(CitySearchState())
    val cities = searchState.asStateFlow()
    private var searchJob: Job? = null
    private var refreshJob: Job? = null

    fun setAqiEnabled(enabled: Boolean) {
        searchJob?.cancel(); refreshJob?.cancel()
        searchState.value = CitySearchState()
        air.enable(enabled)
        if (enabled) refreshAqi()
    }
    fun forgetAqi() {
        searchJob?.cancel(); refreshJob?.cancel()
        air.forget(); searchState.value = CitySearchState()
    }
    fun searchCity(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            searchState.value = CitySearchState(problem = "Enter at least two letters.")
            return
        }
        searchJob = viewModelScope.launch {
            searchState.value = CitySearchState(busy = true)
            try {
                val result = air.search(query)
                searchState.value = CitySearchState(results = result, problem = if (result.isEmpty()) "No matching cities. Try the city and country." else null)
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                searchState.value = CitySearchState(problem = "Search timed out. Try again when connected.")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { searchState.value = CitySearchState(problem = "City search is unavailable. Check your connection and retry.") }
        }
    }
    fun selectCity(place: AqiPlace) {
        searchJob?.cancel(); refreshJob?.cancel()
        air.select(place); searchState.value = CitySearchState()
        refreshAqi(true)
    }
    fun refreshAqi(force: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { air.refresh(force) }
    }
    /**
     * Reads last night's stored wearable readings (6 pm to noon) and runs the sleep model over
     * them. Simulated readings are left out: they are not anyone's night.
     */
    fun refreshSleepEstimate() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            estimate.value = runCatching {
                val app = getApplication<Application>()
                val info = com.gone.ai.ml.ModelRegistry.info(app, "sleep") ?: return@runCatching null
                val zone = java.time.ZoneId.systemDefault()
                val noon = java.time.LocalDate.now().atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                val evening = noon - 18 * 3_600_000L
                val now = System.currentTimeMillis()
                val until = minOf(noon, now)
                val readings = com.gone.ai.data.library.GoneDatabase.getInstance(app).vitalsDao()
                    .windowSince(com.gone.ai.health.service.HealthMonitoringService.DEFAULT_PATIENT_ID, evening, 20_000)
                    .filter { it.source == com.gone.ai.health.data.ReadingSource.BLE.wireName && it.timestamp <= until }
                    .map { it.toDomain() }
                if (readings.isEmpty()) return@runCatching null
                com.gone.ai.ml.ModelRegistry.open(app, "sleep")?.use { scorer ->
                    com.gone.ai.ml.SleepEstimator.estimate(readings, scorer, info.threshold ?: 0.5f)
                }
            }.getOrNull()
        }
    }

    fun setAreaEnabled(enabled: Boolean) {
        areaJob?.cancel()
        AreaAwareness.enable(getApplication(), enabled)
        if (enabled) refreshArea(true)
    }
    fun refreshArea(force: Boolean = false) {
        if (areaJob?.isActive == true || !area.value.saved.enabled) return
        areaJob = viewModelScope.launch { AreaAwareness.refreshAndNotify(getApplication(), force) }
    }
    fun pauseAqi() {
        refreshJob?.cancel()
        areaJob?.cancel()
        searchJob?.cancel()
        searchState.value = searchState.value.copy(busy = false)
    }
}
