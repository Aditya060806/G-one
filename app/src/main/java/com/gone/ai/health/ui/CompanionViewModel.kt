package com.gone.ai.health.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gone.ai.health.data.WellnessLogStore
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
    fun pauseAqi() {
        refreshJob?.cancel()
        searchJob?.cancel()
        searchState.value = searchState.value.copy(busy = false)
    }
}
