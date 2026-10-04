package com.gone.ai.health.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gone.ai.health.disaster.DisasterGuidance
import com.gone.ai.health.domain.Temperature
import com.gone.ai.health.environment.AreaAlert
import java.text.DateFormat
import java.util.Date

/**
 * "Your area": the city's weather, India's air quality index, hints from the forecast, and
 * official NDMA alerts with what to do. Optional; uses the city chosen in the Air quality card.
 */
@Composable
internal fun AreaCard(vm: CompanionViewModel, dark: Boolean, now: Long) {
    val state by vm.area.collectAsState()
    val aqiState by vm.aqi.collectAsState()
    val saved = state.saved
    val place = aqiState.saved.place
    var about by rememberSaveable { mutableStateOf(false) }
    var showOthers by rememberSaveable { mutableStateOf(false) }

    CompanionPanel("Your area", dark) {
        CompanionPill(if (saved.enabled) "Weather · India AQI · NDMA alerts" else "Optional · off", dark)
        if (!saved.enabled) {
            Text("Official disaster alerts for your district, your city's weather for the heat rules, and India's air quality index.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { about = true }, modifier = Modifier.fillMaxWidth()) { Text("Set up area alerts") }
        } else {
            Text(place?.label ?: "Choose your city in the Air quality card first.", style = MaterialTheme.typography.bodyMedium)

            saved.weather?.let { w ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Temperature.fahrenheitText(w.temperatureC, 0), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Column {
                        w.apparentC?.let { Text("Feels like ${Temperature.fahrenheitText(it, 0)}", style = MaterialTheme.typography.bodyMedium) }
                        Text(listOfNotNull(w.humidityPct?.let { "Humidity ${it.toInt()}%" }, w.uvIndex?.let { "UV ${"%.0f".format(it)}" })
                            .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("City weather · ${time(w.observedAt)}${if (w.isStale(now)) " · stale" else ""}. The heat rules use it only when the wearable has no temperature sensor of its own.",
                    style = MaterialTheme.typography.bodySmall)
            }
            saved.indiaAqi?.let { a ->
                Text("India AQI ${a.value} · ${a.category}", style = MaterialTheme.typography.titleMedium)
                Text("Mostly ${a.dominant}. CPCB method on modelled city concentrations, not a monitoring station.",
                    style = MaterialTheme.typography.bodySmall)
            }
            saved.forecastFlags().forEach { flag ->
                Text("Forecast: ${flag.text}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            if (saved.forecastFlags().isNotEmpty()) {
                Text("From the weather forecast using IMD's terms; not an official warning.", style = MaterialTheme.typography.bodySmall)
            }

            val active = saved.activeAlerts(now)
            val mine = active.filter { it.forYourArea }
            val others = active.filterNot { it.forYourArea }
            Text(
                when {
                    saved.alertsCheckedAt == null -> "Official alerts: not checked yet"
                    mine.isEmpty() -> "No active official alert names your district"
                    else -> "${mine.size} official alert${if (mine.size == 1) "" else "s"} for your district"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            mine.forEach { AlertRow(it, dark, now) }
            if (others.isNotEmpty()) {
                TextButton(onClick = { showOthers = !showOthers }) {
                    Text(if (showOthers) "Hide other alerts in your state" else "${others.size} other alert${if (others.size == 1) "" else "s"} in your state")
                    Icon(Icons.Default.ExpandMore, null)
                }
                AnimatedVisibility(showOthers) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { others.take(12).forEach { AlertRow(it, dark, now) } }
                }
            }
            saved.alertsCheckedAt?.let { Text("Checked ${time(it)}. Source: NDMA SACHET. Alerts keep showing offline until they expire.", style = MaterialTheme.typography.labelSmall) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = companionAccent(dark))
            state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.refreshArea(true) }, enabled = !state.loading && place != null, modifier = Modifier.weight(1f)) { Text("Check now") }
                OutlinedButton(onClick = { about = true }, modifier = Modifier.weight(1f)) { Text("Settings") }
            }
        }
    }

    if (about) AlertDialog(
        onDismissRequest = { about = false },
        title = { Text("Your area") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Uses the city chosen for air quality. Its coordinates go to Open-Meteo for weather and pollutant levels. " +
                    "For official alerts the phone downloads your whole state's public feed from NDMA's SACHET system and picks out the alerts that name your city or district itself, so your location is not sent to SACHET.")
                Text("New alerts for your district at Moderate or above arrive as notifications, checked about every 30 minutes. Monitoring and SOS never depend on this.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Weather, India AQI and official alerts", Modifier.weight(1f))
                    Switch(checked = saved.enabled, onCheckedChange = vm::setAreaEnabled)
                }
                if (place == null) Text("Choose a city in the Air quality card first.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { about = false }) { Text("Done") } }
    )
}

@Composable
private fun AlertRow(alert: AreaAlert, dark: Boolean, now: Long) {
    var open by rememberSaveable(alert.identifier) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = companionAccent(dark).copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${alert.event} · ${alert.severity.ifBlank { "Alert" }}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(alert.headline, style = MaterialTheme.typography.bodyMedium)
            alert.localHeadline?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(listOfNotNull(alert.issuer.takeIf { it.isNotBlank() }, alert.expires?.let { "until ${time(it)}" }, alert.areaDesc.takeIf { it.isNotBlank() })
                .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide what to do" else "What to do") }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (alert.instruction.isNotBlank()) Text("From the alert: ${alert.instruction}", style = MaterialTheme.typography.bodyMedium)
                    DisasterGuidance.forHazard(alert.hazard).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Text("Guidance: ${DisasterGuidance.SOURCE}. Call 112 in an emergency.", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun time(at: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
