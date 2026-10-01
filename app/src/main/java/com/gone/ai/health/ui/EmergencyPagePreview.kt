package com.gone.ai.health.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gone.ai.health.data.EmergencyProfileEntity
import com.gone.ai.health.domain.EmergencyPayload
import com.gone.ai.health.domain.Staleness
import com.gone.ai.health.domain.Temperature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compose-native preview of the offline emergency page.
 *
 * WHAT THIS IS NOT
 *
 * This is not a WebView rendering of the actual HTML. It's a Compose card that maps
 * [EmergencyPayload] fields to styled rows — fast, no web engine overhead, and it
 * previews the content without requiring the QR to be generated first.
 *
 * It is intentionally styled to look like the HTML page (white card, red header, same
 * section order) so the user can validate their settings before writing to the tag.
 *
 * COLOUR NOTE
 *
 * The emergency page uses a white background regardless of app theme — it is styled to
 * look like an emergency medical form, not an app screen. The colours here match the
 * HTML template in [com.gone.ai.health.domain.EmergencyHtmlRenderer].
 */
@Composable
fun EmergencyPagePreview(
    payload: EmergencyPayload,
    modifier: Modifier = Modifier
) {
    val now = System.currentTimeMillis()
    val staleness = payload.staleness(now)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(16.dp))
    ) {
        // ── Emergency header ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFC0392B))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "🚨 Emergency Medical ID",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "G-one Health — Offline Snapshot",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // ── Staleness banner ──────────────────────────────────────────────────
        when (staleness) {
            Staleness.STALE ->
                StaleBanner(
                    text    = "⚠️ Vitals may be outdated — recorded 1–24 hours ago.",
                    bgColor = Color(0xFFFFFBEB),
                    textColor = Color(0xFF78350F),
                    borderColor = Color(0xFFD97706)
                )
            Staleness.VERY_STALE ->
                StaleBanner(
                    text    = "🔴 Snapshot is old (> 24 h) — treat vitals as historical.",
                    bgColor = Color(0xFFFFF5F5),
                    textColor = Color(0xFF7F1D1D),
                    borderColor = Color(0xFFC0392B)
                )
            else -> {}
        }

        // ── Patient identity ──────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFAFAFA))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            payload.name?.let {
                Text(it, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111111))
            } ?: Text("(Name not set)", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9CA3AF))

            payload.age?.let {
                Text("Age: $it", fontSize = 13.sp, color = Color(0xFF555555), modifier = Modifier.padding(top = 3.dp))
            }

            payload.bloodGroup?.let { bg ->
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFF5F5))
                        .border(2.dp, Color(0xFFC0392B), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Blood Group",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC0392B),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = bg,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFC0392B)
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0xFFEEEEEE))

        // ── Medical information ───────────────────────────────────────────────
        val hasMedical = listOf(payload.allergies, payload.chronicConditions,
            payload.medications, payload.implantedDevices).any { it != null }

        if (hasMedical) {
            PreviewSection(title = "⚕️ Medical Information") {
                payload.allergies?.let {
                    InfoRow(label = "⚠️ Allergies", value = it, isHighlighted = true)
                }
                payload.chronicConditions?.let {
                    InfoRow(label = "Chronic Conditions", value = it)
                }
                payload.medications?.let {
                    InfoRow(label = "Medications", value = it)
                }
                payload.implantedDevices?.let {
                    InfoRow(label = "⚡ Implanted Devices", value = it, isBlueTint = true)
                }
            }
        }

        // ── Last known vitals ─────────────────────────────────────────────────
        val hasVitals = listOf(payload.heartRate, payload.spo2, payload.bodyTempC,
            payload.riskStatus).any { it != null }

        if (hasVitals) {
            PreviewSection(title = "❤️ Last Known Vitals") {
                payload.heartRate?.let  { VitalRow("Heart Rate", "$it BPM") }
                payload.spo2?.let       { VitalRow("SpO₂", "$it%", warn = it < 92) }
                payload.bodyTempC?.let  { VitalRow("Temperature", Temperature.fahrenheitText(it), warn = it >= 38.0f) }
                payload.motionStatus?.let {
                    VitalRow("Motion Status",
                        if (it == "fall_detected") "⚠️ Fall detected" else it.replaceFirstChar { c -> c.uppercase() },
                        critical = it == "fall_detected"
                    )
                }
                payload.riskStatus?.let {
                    VitalRow("Risk Status",
                        it.replaceFirstChar { c -> c.uppercase() },
                        warn = it.lowercase() == "elevated",
                        critical = it.lowercase() == "critical"
                    )
                }
                payload.readingTimestamp?.let {
                    HorizontalDivider(color = Color(0xFFF0F0F0), modifier = Modifier.padding(vertical = 6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Last Reading", fontSize = 12.sp, color = Color(0xFF888888))
                        Text(
                            formatTimestamp(it),
                            fontSize = 12.sp, color = Color(0xFF888888),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // ── Emergency contact ─────────────────────────────────────────────────
        payload.emergencyContact?.takeIf { it.isNotBlank() }?.let { contact ->
            PreviewSection(title = "📞 Emergency Contact") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF16A34A))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📞 Call Emergency Contact", color = Color.White,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(contact, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1D4ED8))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🚑 Call 112 — Emergency Services", color = Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Footer ────────────────────────────────────────────────────────────
        Text(
            text = "G-one Offline Emergency Snapshot\nFor live data, tap the NFC tag when online.",
            fontSize = 10.sp,
            color = Color(0xFFAAAAAA),
            textAlign = TextAlign.Center,
            lineHeight = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

@Composable
private fun StaleBanner(text: String, bgColor: Color, textColor: Color, borderColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(start = 0.dp)
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(borderColor))
        Text(
            text = text,
            fontSize = 13.sp,
            color = textColor,
            lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun PreviewSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF555555),
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
    HorizontalDivider(color = Color(0xFFEEEEEE))
}

@Composable
private fun InfoRow(label: String, value: String, isHighlighted: Boolean = false, isBlueTint: Boolean = false) {
    val bgColor = when {
        isHighlighted -> Color(0xFFFFFBEB)
        isBlueTint    -> Color(0xFFEFF6FF)
        else          -> Color(0xFFF7F8FA)
    }
    val labelColor = when {
        isHighlighted -> Color(0xFF92400E)
        isBlueTint    -> Color(0xFF1E40AF)
        else          -> Color(0xFF777777)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = labelColor, letterSpacing = 0.5.sp)
        Text(value, fontSize = 14.sp, color = Color(0xFF111111), lineHeight = 20.sp,
            modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun VitalRow(label: String, value: String, warn: Boolean = false, critical: Boolean = false) {
    val valueColor = when {
        critical -> Color(0xFFC0392B)
        warn     -> Color(0xFFB45309)
        else     -> Color(0xFF111111)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = Color(0xFF444444))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = valueColor,
            fontFamily = FontFamily.Monospace)
    }
    HorizontalDivider(color = Color(0xFFF0F0F0))
}

private val tsFormat = SimpleDateFormat("d MMM yyyy · h:mm a", Locale.getDefault())
private fun formatTimestamp(epochMs: Long): String = tsFormat.format(Date(epochMs))
