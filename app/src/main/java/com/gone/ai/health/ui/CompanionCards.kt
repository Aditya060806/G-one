package com.gone.ai.health.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gone.ai.health.domain.*
import com.gone.ai.health.environment.aqiCategory
import com.gone.ai.ui.theme.ModernCardDark
import com.gone.ai.ui.theme.ModernCardLight
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@Composable
internal fun CompanionCards(darkTheme: Boolean, vm: CompanionViewModel = viewModel()) {
    val lifecycle = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycle, vm) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    now = System.currentTimeMillis()
                    vm.refreshAqi()
                    delay(60_000)
                }
            } finally { vm.pauseAqi() }
        }
    }
    AirQualityCard(vm, darkTheme, now)
    Spacer(Modifier.height(14.dp))
    WellnessCard(vm, darkTheme, now)
}

@Composable
private fun CompanionPanel(title: String, dark: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = if (dark) ModernCardDark else ModernCardLight) {
        Column(Modifier.animateContentSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(42.dp).background(companionAccent(dark).copy(alpha = 0.10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(when (title) { "Sleep" -> Icons.Default.NightsStay; "Stress" -> Icons.Default.SelfImprovement; else -> Icons.Default.Air }, null, tint = companionAccent(dark))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun AirQualityCard(vm: CompanionViewModel, dark: Boolean, now: Long) {
    val state by vm.aqi.collectAsState()
    val saved = state.saved
    var configure by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    var details by rememberSaveable { mutableStateOf(false) }
    CompanionPanel("Air quality", dark) {
        val reading = saved.reading.takeIf { saved.enabled }
        CompanionPill(when { !saved.enabled -> "Optional · off"; reading == null -> "No saved reading"; reading.isStale(now) -> "Cached · stale"; else -> "Cached · regional estimate" }, dark)
        AqiDial(reading?.value, dark)
        Text(reading?.let { aqiCategory(it.value) } ?: if (saved.enabled) "Waiting for your city’s AQI" else "Know the air around you", style = MaterialTheme.typography.titleMedium)
        Text(if (saved.enabled) saved.place?.label ?: "Choose your city to begin." else "Optional city estimates. Your health monitoring stays offline.", style = MaterialTheme.typography.bodyMedium)
        if (reading != null) {
            Text("Model time · ${formatAqiTime(reading.observedAt)}", style = MaterialTheme.typography.bodySmall)
            if (reading.isStale(now)) Text("Current conditions may differ from this saved value.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.loading && saved.enabled) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = companionAccent(dark))
            Text(if (reading == null) "Fetching the first estimate…" else "Refreshing · saved reading stays visible", style = MaterialTheme.typography.labelSmall)
        }
        state.problem?.takeIf { saved.enabled }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!saved.enabled) {
            Button(onClick = { configure = true }, modifier = Modifier.fillMaxWidth()) { Text("Set up air quality") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.refreshAqi(true) }, enabled = !state.loading && saved.place != null, modifier = Modifier.weight(1f)) { Text("Refresh") }
                OutlinedButton(onClick = { configure = true }, modifier = Modifier.weight(1f)) { Text("Settings") }
            }
            TextButton(onClick = { details = !details }) {
                Text(if (details) "Hide reading details" else "Reading details & sources")
                Icon(Icons.Default.ExpandMore, null)
            }
            AnimatedVisibility(details) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    reading?.let { Text("Last fetched: ${formatAqiTime(it.fetchedAt)}", style = MaterialTheme.typography.bodySmall) }
                    Text("Modeled US EPA AQI, not India’s National AQI or an on-body measurement.", style = MaterialTheme.typography.bodySmall)
                    if (reading != null && !reading.isStale(now) && reading.value > 100) Text("Check local air-quality guidance before outdoor activity, especially if you are sensitive to air pollution.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { uri.openUri("https://www.airnow.gov/aqi/aqi-basics/") }) { Text("About the US AQI scale") }
                }
            }
            TextButton(onClick = { uri.openUri("https://open-meteo.com/en/docs/air-quality-api") }) { Text("Open-Meteo / CAMS", style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (configure) {
        var query by rememberSaveable { mutableStateOf("") }
        val cities by vm.cities.collectAsState()
        AlertDialog(onDismissRequest = { configure = false }, title = { Text("Optional air quality") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("When enabled, city searches and your selected city’s coordinates are sent to Open-Meteo. The provider also receives your IP address. No health readings or GPS location are sent.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable online AQI", Modifier.weight(1f))
                        Switch(checked = saved.enabled, onCheckedChange = vm::setAqiEnabled)
                    }
                    if (saved.enabled) {
                        saved.place?.let { Text("Selected: ${it.label}") }
                        OutlinedTextField(query, { query = it.take(100) }, label = { Text("City, country") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { vm.searchCity(query) }, enabled = !cities.busy && query.trim().length >= 2) { Text(if (cities.busy) "Searching…" else "Search cities") }
                        cities.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        cities.results.forEach { place ->
                            TextButton(onClick = { vm.selectCity(place); configure = false }) { Text(place.label) }
                        }
                        Text("Location search: GeoNames via Open-Meteo. Automatic updates run every 15 minutes while the dashboard is open. Failed updates keep the saved value.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (saved.place != null) TextButton(onClick = vm::forgetAqi) { Text("Remove city and saved AQI") }
                }
            }, confirmButton = { TextButton(onClick = { configure = false }) { Text("Done") } })
    }
}

private fun formatAqiTime(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
private fun durationText(minutes: Int) = "${minutes / 60}h ${minutes % 60}m"
private fun dateTimestamp(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
private fun WellnessCard(vm: CompanionViewModel, dark: Boolean, now: Long) {
    val logs by vm.journal.logs.collectAsState()
    var sleepDialog by rememberSaveable { mutableStateOf(false) }
    var stressDialog by rememberSaveable { mutableStateOf(false) }
    var historyDialog by rememberSaveable { mutableStateOf(false) }
    var clearDialog by rememberSaveable { mutableStateOf(false) }
    val today = remember(now) { LocalDate.now() }
    val weekSleep = logs.sleep.filter { it.wakeDate in today.minusDays(6)..today }
    val weekStress = logs.stress.filter { it.date in today.minusDays(6)..today }
    CompanionPanel("Sleep", dark) {
        CompanionPill("Self-reported · offline", dark)
        val average = sleepAverageLastWeek(logs.sleep, today)
        Text(average?.let { durationText(it) } ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(if (average == null) "Build your sleep picture, one night at a time." else "Average sleep · ${weekSleep.size} of 7 days logged", style = MaterialTheme.typography.bodyMedium)
        WellnessWeekBars(weekSleep.associate { it.wakeDate to it.sleepMinutes / 60f }, today, "hours", 12f, dark)
        Button(onClick = { sleepDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Log or edit sleep") }
        Text("Your sleep diary · not measured sleep stages", style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(14.dp))
    CompanionPanel("Stress", dark) {
        CompanionPill("Self-reported · offline", dark)
        val todayStress = logs.stress.firstOrNull { it.date == today }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(todayStress?.let { "${it.level}/5" } ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(todayStress?.let { stressLabel(it.level) } ?: "How do you feel?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        StressLevelVisual(todayStress?.level, dark)
        Spacer(Modifier.height(2.dp))
        Text("Your week", style = MaterialTheme.typography.titleSmall)
        WellnessWeekBars(weekStress.associate { it.date to it.level.toFloat() }, today, "level / 5", 5f, dark)
        Button(onClick = { stressDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Log or edit stress") }
        Text("Your check-in · not an AI diagnosis or SOS trigger", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { historyDialog = true }) { Text("Manage sleep & stress history") }
    }
    if (sleepDialog) WellnessEntryDialog(true, vm, today) { sleepDialog = false }
    if (stressDialog) WellnessEntryDialog(false, vm, today) { stressDialog = false }
    if (historyDialog) AlertDialog(onDismissRequest = { historyDialog = false }, title = { Text("Your local entries") }, text = {
        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Up to 365 entries of each type stay on this phone. Saving an existing date replaces that entry.") }
            item { Text("Sleep", style = MaterialTheme.typography.titleMedium) }
            if (logs.sleep.isEmpty()) item { Text("No sleep entries yet.") }
            items(logs.sleep, key = { "sleep-${it.wakeDate}" }) { log ->
                Column {
                    Text("${log.wakeDate}: ${durationText(log.sleepMinutes)} · quality ${log.quality}/5")
                    TextButton(onClick = { vm.journal.deleteSleep(log.wakeDate) }) { Text("Delete sleep ${log.wakeDate}") }
                }
            }
            item { Text("Stress", style = MaterialTheme.typography.titleMedium) }
            if (logs.stress.isEmpty()) item { Text("No stress entries yet.") }
            items(logs.stress, key = { "stress-${it.date}" }) { log ->
                Column {
                    Text("${log.date}: ${log.level}/5 · ${stressLabel(log.level)}")
                    TextButton(onClick = { vm.journal.deleteStress(log.date) }) { Text("Delete stress ${log.date}") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { historyDialog = false }) { Text("Done") } }, dismissButton = {
        TextButton(onClick = { clearDialog = true }, enabled = logs.sleep.isNotEmpty() || logs.stress.isNotEmpty()) { Text("Clear all entries") }
    })
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("Delete sleep and stress entries?") }, text = { Text("This removes all saved sleep and stress check-ins. It cannot be undone.") },
        confirmButton = { TextButton(onClick = { vm.journal.clear(); clearDialog = false }) { Text("Delete entries") } }, dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Cancel") } })
}

private fun stressLabel(level: Int) = listOf("Very low", "Low", "Moderate", "High", "Very high")[level - 1]

@Composable
private fun WellnessEntryDialog(sleep: Boolean, vm: CompanionViewModel, today: LocalDate, dismiss: () -> Unit) {
    val logs by vm.journal.logs.collectAsState()
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var bed by rememberSaveable { mutableStateOf("23:00") }
    var wake by rememberSaveable { mutableStateOf("07:00") }
    var awake by rememberSaveable { mutableStateOf("0") }
    var rating by rememberSaveable { mutableFloatStateOf(3f) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(date) {
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@LaunchedEffect
        if (sleep) logs.sleep.firstOrNull { it.wakeDate == parsed }?.let {
            bed = String.format(Locale.US, "%02d:%02d", it.bedtimeMinute / 60, it.bedtimeMinute % 60)
            wake = String.format(Locale.US, "%02d:%02d", it.wakeMinute / 60, it.wakeMinute % 60)
            awake = it.awakeMinutes.toString(); rating = it.quality.toFloat()
        } else logs.stress.firstOrNull { it.date == parsed }?.let { rating = it.level.toFloat() }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (sleep) "Log your sleep" else "Stress check-in") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(date, { date = it.take(10) }, label = { Text(if (sleep) "Wake-up date (YYYY-MM-DD)" else "Date (YYYY-MM-DD)") }, singleLine = true)
            if (sleep) {
                Text("Use 24-hour times. An earlier bedtime clock time means sleep on the same day; a later bedtime means the previous night.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(bed, { bed = it.take(5) }, label = { Text("Bedtime (HH:mm)") }, singleLine = true)
                OutlinedTextField(wake, { wake = it.take(5) }, label = { Text("Wake time (HH:mm)") }, singleLine = true)
                OutlinedTextField(awake, { awake = it.take(4) }, label = { Text("Minutes awake during this period") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Sleep quality: ${rating.roundToInt()}/5 (1 poor, 5 very good)")
            } else Text("Stress: ${rating.roundToInt()}/5 · ${stressLabel(rating.roundToInt())}")
            Slider(value = rating, onValueChange = { rating = it }, valueRange = 1f..5f, steps = 3, modifier = Modifier.semantics { contentDescription = if (sleep) "Self-reported sleep quality from 1 to 5" else "Self-reported stress from 1 to 5" })
            Text("Saving the same date updates its existing entry. Only you report these values.", style = MaterialTheme.typography.bodySmall)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            try {
                val parsed = try { LocalDate.parse(date) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid date as YYYY-MM-DD.") }
                if (sleep) vm.journal.save(SleepLog(parsed, parseClockMinute(bed), parseClockMinute(wake), awake.toIntOrNull() ?: throw IllegalArgumentException("Enter awake minutes as a whole number."), rating.roundToInt()))
                else vm.journal.save(StressLog(parsed, rating.roundToInt()))
                dismiss()
            } catch (e: IllegalArgumentException) { problem = e.message ?: "Check your entry." }
        }) { Text("Save locally") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
