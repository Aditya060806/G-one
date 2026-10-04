package com.gone.ai.ui.screens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gone.ai.health.detect.DetectionContext
import com.gone.ai.health.detect.HeartRateContextRule
import com.gone.ai.health.detect.ModelSignals
import com.gone.ai.health.detect.VitalsWindow
import com.gone.ai.health.domain.AnomalyThresholds
import com.gone.ai.health.domain.PatientBaseline
import com.gone.ai.health.domain.RiskScores
import com.gone.ai.health.domain.VitalsSample
import com.gone.ai.ml.FallStream
import com.gone.ai.ml.FallWindow
import com.gone.ai.ml.HrContextModel
import com.gone.ai.ml.ModelInfo
import com.gone.ai.ml.ModelRegistry
import com.gone.ai.ml.Scorer
import com.gone.ai.ml.TfliteScorer
import com.gone.ai.ui.theme.ModernCardDark
import com.gone.ai.ui.theme.ModernCardLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The small trained models the app runs, with what they were tested on and how they did, timed on
 * this phone, and something to try with each. Everything here runs on the phone, offline.
 */
@Composable
fun ModelLabScreen(isDarkTheme: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val models by produceState<Map<String, ModelInfo>>(emptyMap()) {
        value = withContext(Dispatchers.IO) { ModelRegistry.all(context) }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Model Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
        }
        Text("The small trained models G-one runs on this phone. Each one only produces a score; a fixed, " +
            "named rule decides what the score means, and the language model only rewords alerts afterwards.",
            style = MaterialTheme.typography.bodyMedium)
        if (models.isEmpty()) Text("No models found in this build.", color = MaterialTheme.colorScheme.error)
        models["fall"]?.let { info ->
            ModelPanel(info, "Fall detector", "Tells a fall from a knock in 4 s of wrist movement.", isDarkTheme) { scorer ->
                FallTryIt(info, scorer)
            }
        }
        models["hr_context"]?.let { info ->
            ModelPanel(info, "Heart rate in context", "Says what heart rate the last 5 minutes of movement would explain.", isDarkTheme) { scorer ->
                HrContextTryIt(scorer)
            }
        }
        models["sleep"]?.let { info ->
            ModelPanel(info, "Sleep or awake", "Estimates, every 30 s, whether the wearer is asleep, from wrist movement.", isDarkTheme) { scorer ->
                SleepTryIt(info, scorer)
            }
        }
        Text("Test figures are from people the model never saw while training (see ml/reports in the project). " +
            "Snapdragon timings come from Qualcomm AI Hub's hosted phones; this phone's timing is measured here.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModelPanel(
    info: ModelInfo,
    title: String,
    purpose: String,
    dark: Boolean,
    tryIt: @Composable (Scorer) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scorer by produceState<TfliteScorer?>(null, info.id) { value = withContext(Dispatchers.IO) { ModelRegistry.open(context, info.id) } }
    // The value is captured: read through the delegate at dispose time it would be the NEXT
    // scorer, and the model would be closed the moment it finished loading.
    val loaded = scorer
    DisposableEffect(loaded) { onDispose { loaded?.close() } }
    var micros by remember { mutableStateOf<Long?>(null) }

    Surface(shape = RoundedCornerShape(22.dp), color = if (dark) ModernCardDark else ModernCardLight, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Text(purpose, style = MaterialTheme.typography.bodyMedium)
            Text("${info.id}_v${info.version} · ${info.parameters} parameters · ${"%.1f".format(info.bytes / 1024f)} KB · ${info.quantisation}",
                style = MaterialTheme.typography.bodySmall)
            Text("Learned from ${info.trainedOn}. Tested ${info.tested}.", style = MaterialTheme.typography.bodySmall)
            info.metrics.forEach { (key, value) -> MetricRow(metricLabel(key), metricText(key, value)) }
            info.threshold?.let { MetricRow("Score at which its rule acts", "%.2f".format(it)) }
            if (info.snapdragon.isEmpty()) {
                Text("Snapdragon timing: not profiled yet (run ml/aihub/profile.py).", style = MaterialTheme.typography.bodySmall)
            } else info.snapdragon.forEach {
                MetricRow("${it.device} (${it.chipset})", "${formatMicros(it.inferenceMicros)} on ${it.runsOn}")
            }
            when {
                scorer == null -> Text("Model not loaded (missing, or it failed its checksum). Its rule stays silent.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> {
                    OutlinedButton(onClick = {
                        val s = scorer ?: return@OutlinedButton
                        scope.launch { micros = withContext(Dispatchers.Default) { runCatching { timeIt(s) }.getOrNull() } }
                    }) { Text("Time it on this phone") }
                    micros?.let { MetricRow("On this phone (median of 200 runs, CPU)", "${formatMicros(it)} a run") }
                    Text("Checksum matches the manifest.", style = MaterialTheme.typography.labelSmall)
                    tryIt(scorer!!)
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun metricLabel(key: String) = when (key) {
    "sensitivity" -> "Falls caught (people held out)"
    "specificity" -> "Daily-activity recordings with no alarm"
    "f1" -> "F1"
    "false_alarms_per_hour" -> "Alarms per hour of hard daily activities"
    "rule_5g_sensitivity" -> "Today's 5 g rule: falls caught"
    "rule_5g_specificity" -> "Today's 5 g rule: no alarm"
    "unseen_dataset_sensitivity" -> "Dataset never seen: falls caught"
    "unseen_dataset_specificity" -> "Dataset never seen: no alarm"
    "mean_error_bpm" -> "Expected-rate error"
    "resting_rate_error_bpm" -> "Same, guessing the resting rate"
    "straight_line_error_bpm" -> "Same, a straight-line fit"
    "share_inside_band" -> "Measured rate inside the band (aim 80 %)"
    "false_alarms_per_24h" -> "Rule alarms per 24 h, healthy people"
    "made_up_rise_25_detected" -> "Made-up +25 bpm rise caught"
    "made_up_rise_40_detected" -> "Made-up +40 bpm rise caught"
    "accuracy" -> "Epochs scored right"
    "wake_specificity" -> "Awake epochs recognised"
    "sleep_sensitivity" -> "Asleep epochs recognised"
    "kappa" -> "Cohen's kappa"
    "total_sleep_error_min" -> "Total sleep error a night"
    else -> key.replace('_', ' ')
}

private fun metricText(key: String, value: Double) = when {
    key.endsWith("_bpm") -> "%.1f bpm".format(value)
    key.endsWith("_min") -> "%.0f min".format(value)
    key.contains("per_") || key == "kappa" || key == "f1" -> "%.2f".format(value)
    else -> "%.1f %%".format(value * 100)
}

private fun formatMicros(us: Long) = if (us >= 1000) "%.2f ms".format(us / 1000.0) else "$us µs"

private fun timeIt(scorer: Scorer): Long {
    val size = (scorer as? TfliteScorer)?.inputSize ?: return 0
    val input = FloatArray(size) { 0.1f }
    repeat(20) { scorer.score(input) }
    val runs = LongArray(200) {
        val start = System.nanoTime()
        scorer.score(input)
        (System.nanoTime() - start) / 1000
    }
    return runs.sorted()[runs.size / 2]
}

// ── Fall: replay and live ────────────────────────────────────────────────────

private class FallSample(val source: String, val fall: Boolean, val x: FloatArray, val y: FloatArray, val z: FloatArray, val impact: Int)

private fun readFallSamples(context: Context): List<FallSample> = runCatching {
    val lines = context.assets.open("ml/samples/fall_samples.txt").bufferedReader().readLines()
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val out = mutableListOf<FallSample>()
    var i = 0
    while (i < lines.size) {
        val w = lines[i].split(' ')
        if (w[0] != "case") { i++; continue }
        val n = lines[i + 2].split(' ')[1].toInt()
        val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
        for (k in 0 until n) { val v = lines[i + 3 + k].split(' '); x[k] = v[0].toFloat(); y[k] = v[1].toFloat(); z[k] = v[2].toFloat() }
        out += FallSample(w[1].substringAfter('/').substringBefore("_2016").substringBefore("_2017"), w[3] == "1", x, y, z, w[5].toInt())
        val rows = lines[i + 3 + n].split(' ')[1].toInt()
        i += 3 + n + rows + 2
    }
    out
}.getOrDefault(emptyList())

@Composable
private fun FallTryIt(info: ModelInfo, scorer: Scorer) {
    val context = LocalContext.current
    val samples = remember { readFallSamples(context) }
    val threshold = info.threshold ?: 0.5f
    var chosen by remember { mutableIntStateOf(0) }
    var live by remember { mutableStateOf(false) }

    Text("Try it: recorded movements", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text("UMAFall recordings (CC BY 4.0). They were also in the training data; the figures above are the held-out ones.",
        style = MaterialTheme.typography.labelSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        samples.forEachIndexed { k, s -> FilterChip(selected = k == chosen, onClick = { chosen = k }, label = { Text(if (s.fall) "Fall ${k + 1}" else "Activity ${k + 1}") }) }
    }
    samples.getOrNull(chosen)?.let { s ->
        val mag = FloatArray(s.x.size) { FallWindow.magnitude(s.x[it], s.y[it], s.z[it]) }
        val score = remember(s, scorer) { runCatching { scorer.score(FallWindow.features(s.x, s.y, s.z, s.impact))[0] }.getOrDefault(Float.NaN) }
        LinePlot(listOf(mag), listOf(MaterialTheme.colorScheme.primary), marker = s.impact, description = "Movement magnitude of ${s.source}")
        Text(s.source.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
        MetricRow("Peak at the impact", "%.1f g".format(mag[s.impact]))
        MetricRow("Model score (threshold %.2f)".format(threshold), "%.2f → %s".format(score, if (score >= threshold) "fall" else "not a fall"))
        MetricRow("Today's 5 g rule", if (mag[s.impact] >= 5f) "fall" else "not a fall")
        MetricRow("Truth", if (s.fall) "a fall" else "a daily activity")
    }

    LabelledSwitch("Try it live with this phone's own motion sensor", live) { live = it }
    if (live) LiveFall(scorer, threshold)
}

@Composable
private fun LiveFall(scorer: Scorer, threshold: Float) {
    val context = LocalContext.current
    var last by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var impacts by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val stream = FallStream { features, peak ->
            // Leaving the screen closes the model; a reading still in flight must not crash.
            runCatching { scorer.score(features)[0] }.getOrNull()?.let { impacts++; last = it to peak }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                stream.add(event.timestamp, event.values[0] / SensorManager.GRAVITY_EARTH,
                    event.values[1] / SensorManager.GRAVITY_EARTH, event.values[2] / SensorManager.GRAVITY_EARTH)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager.unregisterListener(listener) }
    }
    Text("Drop the phone onto a cushion, or tap it on a table. Each impact over 2 g is scored 3 s later. " +
        "This is only a demonstration: the monitoring service does not use the phone's sensor.", style = MaterialTheme.typography.bodySmall)
    MetricRow("Impacts scored", impacts.toString())
    last?.let { (score, peak) ->
        MetricRow("Last impact", "%.1f g, score %.2f → %s".format(peak, score, if (score >= threshold) "fall" else "not a fall"))
    }
}

// ── Heart rate in context: a recorded session ───────────────────────────────

private val BandColor = Color(0xFFC98A00)

private class HrSession(val resting: Float, val seconds: IntArray, val motion: FloatArray, val rate: FloatArray)

private fun readHrSession(context: Context): HrSession? = runCatching {
    val lines = context.assets.open("ml/samples/hr_context_sample.txt").bufferedReader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
    val resting = lines.first().split(' ')[1].toFloat()
    val rows = lines.drop(1).map { it.split(' ') }
    fun f(v: String) = if (v == "nan") Float.NaN else v.toFloat()
    HrSession(resting, IntArray(rows.size) { rows[it][0].toInt() }, FloatArray(rows.size) { f(rows[it][1]) }, FloatArray(rows.size) { f(rows[it][2]) })
}.getOrNull()

@Composable
private fun HrContextTryIt(scorer: Scorer) {
    val context = LocalContext.current
    val session = remember { readHrSession(context) } ?: return
    var rise by remember { mutableStateOf(false) }
    // A quiet stretch: the raised rate stays under 130, where the fixed heart-rate rule is silent
    // and this rule is the only one that can notice.
    val riseFrom = 9 * 60
    val riseTo = 15 * 60
    val result by produceState<Triple<FloatArray, FloatArray, List<Int>>?>(null, rise, scorer) {
        value = withContext(Dispatchers.Default) {
            runCatching { replayHrSession(session, scorer, if (rise) riseFrom..riseTo else null) }.getOrNull()
        }
    }

    Text("Try it: a recorded session", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text("PAMAP2 person 102 (CC BY 4.0): 74 minutes of lying, sitting, walking, cycling, stairs and more. " +
        "The band is worked out now, on this phone, from movement alone.", style = MaterialTheme.typography.labelSmall)
    LabelledSwitch("Add a made-up +30 bpm rise at minutes 9–15 (while sitting)", rise) { rise = it }
    val r = result
    if (r == null) Text("Working…", style = MaterialTheme.typography.bodySmall) else {
        val (rates, highs, fired) = r
        LinePlot(listOf(rates, highs), listOf(MaterialTheme.colorScheme.onSurface, BandColor),
            marker = fired.firstOrNull(), description = "Measured heart rate and the top of the expected band")
        Text("Dark: measured heart rate. Amber: top of the expected band. Red line: first alert.", style = MaterialTheme.typography.labelSmall)
        MetricRow("Rule alerts (LOW or MODERATE)", if (fired.isEmpty()) "none" else fired.joinToString { "minute ${session.seconds[it] / 60}" })
    }
}

/** Runs the model and the real rule over the recording, as the monitoring pipeline would. */
private fun replayHrSession(s: HrSession, scorer: Scorer, rise: IntRange?): Triple<FloatArray, FloatArray, List<Int>> {
    val model = HrContextModel(scorer)
    val start = 1_700_000_000_000L
    val samples = s.seconds.indices.map { i ->
        val extra = if (rise != null && s.seconds[i] in rise) 30 else 0
        VitalsSample(
            timestamp = start + s.seconds[i] * 1000L,
            heartRate = s.rate[i].takeIf { it.isFinite() }?.toInt()?.plus(extra),
            motionMagnitudeG = s.motion[i].takeIf { it.isFinite() }
        )
    }
    val bands = HashMap<Long, com.gone.ai.health.detect.HeartRateBand>()
    val rates = FloatArray(samples.size) { samples[it].heartRate?.toFloat() ?: Float.NaN }
    val highs = FloatArray(samples.size) { Float.NaN }
    val fired = mutableListOf<Int>()
    var lastAlert: Long? = null
    val thresholds = AnomalyThresholds.DEFAULT
    for (i in samples.indices) {
        val now = samples[i].timestamp
        val recent = samples.subList(maxOf(0, i - 72), i + 1)
        model.expected(recent, now, s.resting)?.let { bands[now] = it; highs[i] = it.high }
        val ctx = DetectionContext(VitalsWindow.of(recent), PatientBaseline.EMPTY, thresholds, RiskScores.ZERO, now, ModelSignals(bands))
        val cooledDown = lastAlert?.let { now - it > thresholds.perTypeCooldownMinutes * 60_000L } ?: true
        if (HeartRateContextRule.evaluate(ctx) != null && cooledDown) {
            fired += i
            lastAlert = now
        }
    }
    return Triple(rates, highs, fired)
}

// ── Sleep: a recorded night against the lab's scoring ───────────────────────

private class SleepNight(val stages: IntArray, val motion: FloatArray)

private fun readSleepNight(context: Context): SleepNight? = runCatching {
    val lines = context.assets.open("ml/samples/sleep_sample.txt").bufferedReader().readLines().filter { !it.startsWith("#") }
    fun values(prefix: String) = lines.first { it.startsWith(prefix) }.split(' ').drop(1)
    SleepNight(values("stages").map { it.toInt() }.toIntArray(),
        values("motion").map { if (it == "nan") Float.NaN else it.toFloat() }.toFloatArray())
}.getOrNull()

@Composable
private fun SleepTryIt(info: ModelInfo, scorer: Scorer) {
    val context = LocalContext.current
    val night = remember { readSleepNight(context) } ?: return
    val model by produceState<List<Boolean?>?>(null, scorer) {
        value = withContext(Dispatchers.Default) {
            runCatching { com.gone.ai.ml.SleepEstimator.epochs(night.motion, scorer, info.threshold ?: 0.5f) }.getOrNull()
        }
    }
    Text("Try it: a recorded night", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text("Walch et al. 2019, PhysioNet (ODC-By): an Apple Watch's movement and the sleep lab's scoring. Worked out now, on this phone.",
        style = MaterialTheme.typography.labelSmall)
    val lab = night.stages.map { if (it < 0) null else it != 0 }
    val m = model ?: run { Text("Working…", style = MaterialTheme.typography.bodySmall); return }
    SleepStrip("Sleep lab", lab)
    SleepStrip("Sleep model", m)
    val both = lab.indices.filter { it < m.size && lab[it] != null && m[it] != null }
    val agree = both.count { lab[it] == m[it] }
    MetricRow("Epochs where the model agrees with the lab", if (both.isEmpty()) "—" else "%.0f %%".format(100.0 * agree / both.size))
    MetricRow("Asleep (lab / model)", "${lab.count { it == true } / 2} / ${m.count { it == true } / 2} min")
    Text("Dark: asleep. Light: awake. Grey: no score or no data.", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun SleepStrip(label: String, epochs: List<Boolean?>) {
    val asleep = MaterialTheme.colorScheme.primary
    val awake = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val none = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    Text(label, style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(18.dp).semantics { contentDescription = "$label: asleep and awake through the night" }) {
        val w = size.width / epochs.size.coerceAtLeast(1)
        epochs.forEachIndexed { i, e ->
            drawRect(when (e) { true -> asleep; false -> awake; null -> none }, Offset(i * w, 0f), androidx.compose.ui.geometry.Size(w + 0.5f, size.height))
        }
    }
}

/** A switch a screen reader announces with its text: the whole row is the toggle. */
@Composable
private fun LabelledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ── A plain line plot ───────────────────────────────────────────────────────

@Composable
private fun LinePlot(series: List<FloatArray>, colors: List<Color>, marker: Int?, description: String) {
    val all = series.flatMap { s -> s.filter { it.isFinite() } }
    if (all.isEmpty()) return
    val low = all.min()
    val high = all.max().let { if (it - low < 1e-3f) low + 1f else it }
    val markerColor = MaterialTheme.colorScheme.error
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
        series.forEachIndexed { k, s ->
            val path = Path()
            var drawing = false
            s.forEachIndexed { i, v ->
                if (!v.isFinite()) { drawing = false; return@forEachIndexed }
                val p = Offset(size.width * i / (s.size - 1).coerceAtLeast(1), size.height * (1 - (v - low) / (high - low)))
                if (drawing) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                drawing = true
            }
            drawPath(path, colors[k], style = Stroke(width = 3f))
        }
        marker?.let {
            val x = size.width * it / (series[0].size - 1).coerceAtLeast(1)
            drawLine(markerColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        }
    }
}
