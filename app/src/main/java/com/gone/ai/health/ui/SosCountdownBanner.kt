package com.gone.ai.health.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gone.ai.health.service.HealthMonitoringService
import com.gone.ai.ui.theme.DestructiveBg
import kotlinx.coroutines.delay

/**
 * The SOS countdown, over whatever screen is open: what was detected, the seconds left, and a
 * large "I'm OK" that stops it. The same button is on the notification, for a locked phone.
 * Nothing shows when no SOS is waiting.
 */
@Composable
fun SosCountdownBanner(modifier: Modifier = Modifier) {
    val pending by HealthMonitoringService.pendingSos.collectAsState()
    val waiting = pending ?: return

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(waiting.sendAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val secondsLeft = ((waiting.sendAt - now + 999) / 1000).coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DestructiveBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            if (secondsLeft > 0) "SOS in $secondsLeft s" else "Sending SOS…",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        // Announced once when it appears, not every second as the count changes.
        Text(
            waiting.reasons.joinToString("; ") + ".",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
        )
        Text(
            "G-one will text, then call, your emergency contact. If you are all right, stop it.",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 14.sp
        )
        Button(
            onClick = { HealthMonitoringService.cancelSos() },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = DestructiveBg),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(top = 4.dp)
        ) {
            Text("I'm OK, don't send", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
