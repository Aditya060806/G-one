package com.gone.ai.health.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gone.ai.health.data.ReadingSource
import com.gone.ai.health.data.SessionReportEntity
import com.gone.ai.health.explain.ResponseTier
import com.gone.ai.health.domain.Temperature
import com.gone.ai.health.session.RepresentativePointCodec
import com.gone.ai.health.session.ReportText
import com.gone.ai.health.session.SessionReportBuilder
import com.gone.ai.ui.components.BreadcrumbItem
import com.gone.ai.ui.components.GlassCard
import com.gone.ai.ui.components.PureBreadcrumbText
import com.gone.ai.ui.theme.Blue500
import com.gone.ai.ui.theme.ErrorRed
import com.gone.ai.ui.theme.ModernBgDark
import com.gone.ai.ui.theme.ModernBgLight
import com.gone.ai.ui.theme.SuccessGreen
import com.gone.ai.ui.theme.TextPrimary
import com.gone.ai.ui.theme.TextPrimaryLight
import com.gone.ai.ui.theme.WarnAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every stored session report, newest first.
 *
 * REFERENCE's vault also showed an e-mail delivery chip and a debug panel of raw report
 * JSON. Neither applies: reports are shared by the user through the share sheet, and the
 * debug panel was development scaffolding left in the shipped screen.
 */
@Composable
fun ReportsScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateHome: () -> Unit,
    onOpenReport: (Long) -> Unit,
    vm: HealthViewModel
) {
    val reports by vm.reports.collectAsState()
    val files by vm.reportFiles.collectAsState()
    val content = if (isDarkTheme) TextPrimary else TextPrimaryLight
    val muted = secondaryTextColor(isDarkTheme)

    Box(modifier = Modifier.fillMaxSize().background(if (isDarkTheme) ModernBgDark else ModernBgLight)) {
        if (reports.isEmpty()) {
            HealthEmptyState(
                title = "No reports yet",
                body = "Start a session on the Monitor screen while monitoring runs. When you end it, " +
                    "its report appears here.",
                darkTheme = isDarkTheme
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(64.dp))
                reports.forEach { report ->
                    val (statusText, statusColor) = statusOf(report, isDarkTheme)
                    GlassCard(
                        darkTheme = isDarkTheme,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        onClick = { onOpenReport(report.id) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dateTime(report.startedAt), color = content, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    "${SessionReportBuilder.duration(report.endedAt - report.startedAt)} · " +
                                        "${report.sampleCount} readings" +
                                        (if (ReadingSource.SIMULATED in report.sourceList) " · simulated" else "") +
                                        when {
                                            files[report.id]?.addingSummary == true -> " · adding summary…"
                                            files[report.id]?.pdf != null || vm.reportPdfFile(report).exists() -> " · PDF"
                                            else -> ""
                                        },
                                    color = muted,
                                    fontSize = 12.sp
                                )
                            }
                            Text(statusText, color = statusColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(bottomPadding + 24.dp))
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 18.dp)
                .zIndex(10f)
        ) {
            PureBreadcrumbText(
                items = listOf(BreadcrumbItem("Home", onNavigateHome), BreadcrumbItem("Reports")),
                isDarkTheme = isDarkTheme
            )
        }
    }
}

/**
 * One report: what was recorded, how the session went from start to end, and the alerts
 * raised. Share sends plain text through the Android share sheet; nothing leaves the phone
 * until the user picks where it goes.
 */
@Composable
fun ReportScreen(
    reportId: Long,
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateHome: () -> Unit,
    onOpenReports: () -> Unit,
    onDeleted: () -> Unit,
    onOpenPdf: () -> Unit,
    vm: HealthViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by vm.reportFiles.collectAsState()
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val pdf = vm.reportPdf(reportId) ?: error("no PDF")
                    context.contentResolver.openOutputStream(target)?.use { out -> pdf.inputStream().use { it.copyTo(out) } }
                        ?: error("could not open the destination")
                }.isSuccess
            }
            Toast.makeText(context, if (saved) "PDF saved" else "The PDF could not be saved", Toast.LENGTH_SHORT).show()
        }
    }
    // Loaded kept apart from null so a report still loading does not read as "not found".
    var loaded by remember(reportId) { mutableStateOf(false) }
    var report by remember(reportId) { mutableStateOf<SessionReportEntity?>(null) }
    LaunchedEffect(reportId) {
        vm.observeReport(reportId).collect {
            report = it
            loaded = true
        }
    }
    val summarising by vm.summarising.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    val content = if (isDarkTheme) TextPrimary else TextPrimaryLight
    val muted = secondaryTextColor(isDarkTheme)

    Box(modifier = Modifier.fillMaxSize().background(if (isDarkTheme) ModernBgDark else ModernBgLight)) {
        val r = report
        if (loaded && r == null) {
            HealthEmptyState(title = "Report not found", body = "It may have been deleted.", darkTheme = isDarkTheme)
        } else if (r != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(64.dp))

                Text(dateTime(r.startedAt), color = content, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Until ${time(r.endedAt)} · ${SessionReportBuilder.duration(r.endedAt - r.startedAt)} · ${r.sampleCount} readings",
                    color = muted,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(14.dp))

                val (statusText, statusColor) = statusOf(r, isDarkTheme)
                GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
                    StatusLine(statusColor, statusText, content)
                    if (ReadingSource.SIMULATED in r.sourceList) {
                        StatusLine(WarnAmber, "Built from simulated vitals, not measurements from a person.", content)
                    }
                    if (r.gapCount > 0) {
                        StatusLine(muted, "The record has ${r.gapCount} gap${if (r.gapCount == 1) "" else "s"} with no readings.", content)
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionHeader(title = "What was recorded", darkTheme = isDarkTheme)
                Spacer(Modifier.height(8.dp))
                GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
                    r.observationLines.forEach { line ->
                        Text(line, color = content, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }

                val points = remember(r.representativePoints) { RepresentativePointCodec.decode(r.representativePoints) }
                if (points.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    SectionHeader(
                        title = "Through the session",
                        darkTheme = isDarkTheme,
                        subtitle = "Each point averages one fifth of the session; motion shows its peak."
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
                        points.forEach { p ->
                            val values = listOfNotNull(
                                p.heartRate?.let { "HR ${it.toInt()}" },
                                p.spo2?.let { "SpO₂ ${it.toInt()}%" },
                                p.bodyTempC?.let { "core ${Temperature.fahrenheitText(it)}" },
                                p.skinTempC?.let { "skin ${Temperature.fahrenheitText(it)}" },
                                p.motionPeakG?.let { "${oneDecimal(it)} g" },
                                p.emgMean?.let { "EMG ${it.toInt()}" }
                            )
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Column(modifier = Modifier.width(72.dp)) {
                                    Text(p.label, color = content, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(time(p.timestamp), color = muted, fontSize = 11.sp)
                                }
                                Text(values.joinToString("  ·  ").ifEmpty { "no readings" }, color = content, fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionHeader(
                    title = "Plain-language summary",
                    darkTheme = isDarkTheme,
                    subtitle = "Optional. Written by the on-device model from the observations above and " +
                        "checked before it is shown: it may not add numbers, advice or diagnoses."
                )
                Spacer(Modifier.height(8.dp))
                GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
                    val summary = r.aiSummary
                    if (summary != null) {
                        Text(summary, color = content, fontSize = 14.sp)
                    } else if (summarising == r.id) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Blue500)
                            Text("Writing — this can take a minute if the model has to load.", color = muted, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                        }
                    } else {
                        OutlinedButton(onClick = { vm.summariseReport(r) }, enabled = summarising == null) {
                            Text("Write a summary on this phone")
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionHeader(
                    title = "PDF report",
                    darkTheme = isDarkTheme,
                    subtitle = "Made on this phone when the session ended. Nothing is sent anywhere unless you share it."
                )
                Spacer(Modifier.height(8.dp))
                GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
                    val status = files[r.id]
                    when {
                        status?.writingPdf == true -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Blue500)
                            Text("Preparing the PDF…", color = muted, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                        }
                        status?.addingSummary == true -> Text(
                            "The PDF is ready. It will be updated once the plain-language summary is written.",
                            color = muted, fontSize = 13.sp
                        )
                    }
                    status?.problem?.let { Text(it, color = ErrorRed, fontSize = 13.sp) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onOpenPdf, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(16.dp))
                            Text("View", modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val pdf = vm.reportPdf(reportId)
                                    if (pdf == null) {
                                        Toast.makeText(context, "The PDF could not be prepared", Toast.LENGTH_SHORT).show()
                                    } else {
                                        sharePdf(context, pdf, "G-one monitoring report, ${dateTime(r.startedAt)}")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                            Text("Share", modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(
                            onClick = { saveLauncher.launch(vm.reportPdfFile(r).name) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                            Text("Save", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                Row {
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "G-one monitoring report, ${dateTime(r.startedAt)}")
                                .putExtra(Intent.EXTRA_TEXT, ReportText.plain(r))
                            context.startActivity(Intent.createChooser(send, "Share report"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Blue500, contentColor = Color.White),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                        Text("Share as text", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { confirmDelete = true }, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Delete, null, tint = ErrorRed, modifier = Modifier.size(18.dp))
                        Text("Delete", color = ErrorRed, modifier = Modifier.padding(start = 8.dp))
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "A record of sensor readings, not a diagnosis. Skin temperature and muscle-sensor " +
                        "levels are not calibrated measurements.",
                    color = muted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(bottomPadding + 32.dp))
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 18.dp)
                .zIndex(10f)
        ) {
            PureBreadcrumbText(
                items = listOf(
                    BreadcrumbItem("Home", onNavigateHome),
                    BreadcrumbItem("Reports", onOpenReports),
                    BreadcrumbItem("Report")
                ),
                isDarkTheme = isDarkTheme
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this report?") },
            text = { Text("The report is removed. The readings it summarised stay in your history until they age out.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteReport(reportId)
                    onDeleted()
                }) { Text("Delete", color = ErrorRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } }
        )
    }
}

/** Hands a report PDF to the share sheet; the receiving app gets read access to this one file. */
private fun sharePdf(context: Context, pdf: File, subject: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", pdf)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(subject, uri)
    context.startActivity(Intent.createChooser(send, "Share PDF report"))
}

/** The report's status in the same words and colours as the alert it reflects. */
private fun statusOf(report: SessionReportEntity, darkTheme: Boolean): Pair<String, Color> {
    val severity = report.severityEnum ?: return "No alerts raised" to SuccessGreen
    val count = "${report.eventCount} alert${if (report.eventCount == 1) "" else "s"}"
    return "$count · ${ResponseTier.forSeverity(severity).label}" to severityColor(severity, darkTheme)
}

private fun dateTime(millis: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(millis))

private fun time(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

private fun oneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)
