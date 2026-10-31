package com.gone.ai.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gone.ai.health.clinic.ClinicSummaryBuilder
import com.gone.ai.health.clinic.ClinicViewModel
import com.gone.ai.ui.theme.ModernCardDark
import com.gone.ai.ui.theme.ModernCardLight

/**
 * Clinic Mode. The doctor points a phone camera at the QR and reads the summary as text;
 * nothing is uploaded and no app or internet is needed on either side.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClinicModeScreen(isDarkTheme: Boolean, onBack: () -> Unit, vm: ClinicViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val card = if (isDarkTheme) ModernCardDark else ModernCardLight

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Clinic Mode", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
        }
        Text(
            "A summary of your recent records for a doctor's visit. The doctor scans the code with any phone camera " +
                "and reads it as text. Nothing is uploaded; it works without the internet.",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClinicViewModel.DAY_CHOICES.forEach { d ->
                FilterChip(selected = state.days == d, onClick = { vm.setDays(d) }, label = { Text("$d days") })
            }
        }

        val inc = state.include
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(inc.medical, { vm.setInclude(inc.copy(medical = !inc.medical)) }, { Text("Medical details") })
            FilterChip(inc.medications, { vm.setInclude(inc.copy(medications = !inc.medications)) }, { Text("Medications") }, enabled = inc.medical)
            FilterChip(inc.wearable, { vm.setInclude(inc.copy(wearable = !inc.wearable)) }, { Text("Wearable") })
            FilterChip(inc.alerts, { vm.setInclude(inc.copy(alerts = !inc.alerts)) }, { Text("Alerts") })
            FilterChip(inc.journal, { vm.setInclude(inc.copy(journal = !inc.journal)) }, { Text("Sleep & stress") })
        }

        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val qr = state.qr
                when {
                    state.loading -> CircularProgressIndicator(Modifier.padding(48.dp))
                    qr != null -> Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "QR code of the clinic summary",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)
                    )
                    else -> Text("Could not draw the code.", color = Color.Black)
                }
                Text(
                    "${state.qrText.toByteArray(Charsets.UTF_8).size} of ${ClinicSummaryBuilder.QR_MAX_BYTES} bytes",
                    style = MaterialTheme.typography.labelSmall, color = Color.DarkGray
                )
            }
        }

        Button(
            onClick = {
                val text = state.summary?.toText() ?: return@Button
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "G-one clinic summary")
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share summary"))
            },
            enabled = state.summary != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Share as text")
        }

        state.summary?.sections?.forEach { section ->
            Surface(shape = RoundedCornerShape(16.dp), color = card, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    section.lines.forEach { line ->
                        Row {
                            Text(line.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.42f))
                            Text(line.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(0.58f))
                        }
                    }
                }
            }
        }
        Text(ClinicSummaryBuilder.NOTE, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}
