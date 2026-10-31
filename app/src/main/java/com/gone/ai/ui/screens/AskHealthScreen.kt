package com.gone.ai.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gone.ai.health.insight.Insight
import com.gone.ai.health.insight.InsightChart
import com.gone.ai.health.insight.InsightViewModel
import com.gone.ai.health.insight.InsightViewModel.Phase
import com.gone.ai.health.ui.BarChart
import com.gone.ai.health.ui.BarDatum
import com.gone.ai.health.ui.ChartMarker
import com.gone.ai.health.ui.ChartPoint
import com.gone.ai.health.ui.VitalLineChart
import com.gone.ai.health.ui.healthColors
import com.gone.ai.ui.components.AppSettings
import com.gone.ai.ui.theme.ModernCardDark
import com.gone.ai.ui.theme.ModernCardLight
import com.gone.ai.voice.AppLanguage
import kotlin.math.roundToInt

private val EXAMPLES = mapOf(
    AppLanguage.ENGLISH to listOf(
        "What was my highest heart rate yesterday?",
        "How was my oxygen last night?",
        "How many times did I fall this week?",
        "Compare my heart rate today with yesterday",
        "How much did I sleep this week?"
    ),
    AppLanguage.HINDI to listOf(
        "कल मेरी दिल की धड़कन सबसे ज़्यादा कितनी थी?",
        "कल रात मेरा ऑक्सीजन कैसा था?",
        "इस हफ़्ते मैं कितनी बार गिरा?",
        "इस हफ़्ते मेरी नींद का औसत",
        "आज मेरा तापमान अभी कितना है?"
    )
)

/**
 * Ask a question about your own readings, in English or Hindi, by typing or speaking.
 * The answer and the chart come from this phone's records; see [InsightViewModel].
 */
@Composable
fun AskHealthScreen(isDarkTheme: Boolean, onBack: () -> Unit, vm: InsightViewModel = viewModel()) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val phase by vm.phase.collectAsState()
    val heard by vm.heard.collectAsState()
    val language by vm.language.collectAsState()
    val speaking by vm.speaking.collectAsState()
    val voiceNotice by vm.voiceNotice.collectAsState()
    var question by rememberSaveable { mutableStateOf("") }
    val hi = language == AppLanguage.HINDI

    var micBlocked by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.listen() else micBlocked = AppSettings.isBlockedAfterDenial(context, Manifest.permission.RECORD_AUDIO)
    }
    fun onMic() {
        when {
            phase == Phase.Listening -> vm.stopListening()
            hasMic(context) -> vm.listen()
            micBlocked -> AppSettings.open(context)
            else -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun submit(text: String) {
        question = text
        focus.clearFocus()
        vm.ask(text)
    }

    // A switch of language re-answers in the new one; the numbers do not change.
    LaunchedEffect(language) { vm.refresh() }
    LaunchedEffect(heard) { if (heard.isNotBlank()) question = heard }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(if (hi) "अपनी सेहत से पूछें" else "Ask your health", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Text(
            if (hi) "अपनी रीडिंग के बारे में कुछ भी पूछें। जवाब और चार्ट इसी फ़ोन में सहेजे आपके रिकॉर्ड से बनते हैं, इंटरनेट के बिना।"
            else "Ask anything about your own readings. The answer and chart are worked out from the records on this phone, without the internet.",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { lang ->
                FilterChip(selected = language == lang, onClick = { vm.setLanguage(lang) }, label = { Text(lang.nativeName) })
            }
        }

        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (hi) "जैसे: कल रात मेरा ऑक्सीजन कैसा था?" else "e.g. How was my oxygen last night?") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit(question) }),
            trailingIcon = {
                Row {
                    if (vm.canListen) {
                        IconButton(onClick = ::onMic) {
                            Icon(if (phase == Phase.Listening) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = if (phase == Phase.Listening) "Stop listening" else "Ask by voice")
                        }
                    }
                    IconButton(onClick = { submit(question) }, enabled = question.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
                    }
                }
            }
        )

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXAMPLES.getValue(language).forEach { example ->
                AssistChip(onClick = { submit(example) }, label = { Text(example, maxLines = 1) })
            }
        }

        val card = if (isDarkTheme) ModernCardDark else ModernCardLight
        Surface(shape = RoundedCornerShape(16.dp), color = card, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val p = phase) {
                    Phase.Idle -> Text(
                        if (hi) "ऊपर एक सवाल लिखें, बोलें, या किसी उदाहरण पर टैप करें।" else "Type or say a question above, or tap an example.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Phase.Listening -> Text(if (hi) "सुन रहे हैं… $heard" else "Listening… $heard", style = MaterialTheme.typography.bodyLarge)
                    is Phase.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            when {
                                p.usingModel && hi -> "फ़ोन का AI सवाल समझ रहा है…"
                                p.usingModel -> "The on-phone AI is reading your question…"
                                hi -> "आपके रिकॉर्ड देखे जा रहे हैं…"
                                else -> "Looking through your records…"
                            }
                        )
                    }
                    is Phase.Answered -> AnswerCard(p.insight, p.readByModel, hi, isDarkTheme, speaking, vm::readAloud, vm::stopSpeaking)
                    Phase.NotUnderstood -> Text(
                        if (hi) "यह सवाल समझ नहीं आया। धड़कन, ऑक्सीजन, तापमान, हरकत, मांसपेशी, चेतावनी, नींद या तनाव के बारे में पूछें।"
                        else "I couldn't match that to your records. Ask about heart rate, oxygen, temperature, movement, muscle, alerts, sleep or stress.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    is Phase.Failed -> Text(p.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        voiceNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(
            if (hi) "AI सिर्फ़ यह चुनता है कि क्या देखना है। हर संख्या आपके सहेजे डेटा से गिनी जाती है। यह निदान नहीं है।"
            else "The AI only chooses what to look up. Every number is counted from your stored data. Not a diagnosis.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AnswerCard(
    insight: Insight,
    readByModel: Boolean,
    hi: Boolean,
    dark: Boolean,
    speaking: Boolean,
    onRead: () -> Unit,
    onStop: () -> Unit
) {
    val colors = healthColors(dark)
    Text(insight.heading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(insight.answer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

    when (val chart = insight.chart) {
        is InsightChart.Line -> if (chart.points.size >= 2) {
            VitalLineChart(
                points = chart.points.map { ChartPoint(it.timestamp, it.value) },
                markers = listOfNotNull(chart.highlight?.let { ChartMarker(it.timestamp, colors.Warning) }),
                color = colors.Connected,
                darkTheme = dark,
                modifier = Modifier.fillMaxWidth().height(140.dp).clearAndSetSemantics { contentDescription = insight.answer }
            )
        }
        is InsightChart.Bars -> BarChart(
            data = chart.bars.map { BarDatum(it.label, it.value.roundToInt(), if (it.emphasised) colors.Warning else null) },
            darkTheme = dark,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = insight.answer }
        )
        InsightChart.None -> Unit
    }

    Text(
        insight.basis + if (readByModel) (if (hi) " सवाल फ़ोन के AI ने पढ़ा।" else " Question read by the on-phone AI.") else "",
        style = MaterialTheme.typography.bodySmall
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (speaking) {
            OutlinedButton(onClick = onStop) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text(if (hi) "रोकें" else "Stop") }
        } else {
            FilledIconButton(onClick = onRead) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = if (hi) "सुनें" else "Read aloud") }
        }
    }
}

private fun hasMic(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
