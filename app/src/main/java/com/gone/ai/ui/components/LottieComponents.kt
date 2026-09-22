package com.gone.ai.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

object LottieAssets {
    const val LOADING = "loading.json"
    const val DOCTOR_ADVERTISE = "Doctor_advertise.json"
    const val WATCH_SCANNING = "watch_scanning.json"
}

/**
 * Core generic Lottie asset animation wrapper.
 */
@Composable
fun LottieAssetAnimation(
    assetName: String,
    modifier: Modifier = Modifier,
    iterations: Int = LottieConstants.IterateForever,
    speed: Float = 1.0f,
    isPlaying: Boolean = true,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    tint: Color? = null
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset(assetName))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = iterations,
        speed = speed,
        isPlaying = isPlaying
    )

    val tintProperties = tint?.let {
        rememberLottieDynamicProperties(
            rememberLottieDynamicProperty(
                property = LottieProperty.COLOR_FILTER,
                value = SimpleColorFilter(it.toArgb()),
                keyPath = arrayOf("**")
            )
        )
    }
    Box(modifier = modifier, contentAlignment = alignment) {
        LottieAnimation(
            composition = composition,
            dynamicProperties = tintProperties,
            progress = { progress },
            contentScale = contentScale,
            alignment = alignment,
            modifier = Modifier.matchParentSize()
        )
    }
}

/**
 * Universal Loading Lottie Animation (`loading.json`)
 */
@Composable
fun LoadingLottieAnimation(
    modifier: Modifier = Modifier.size(100.dp),
    speed: Float = 1.0f,
    iterations: Int = LottieConstants.IterateForever,
    isPlaying: Boolean = true,
    tint: Color? = null
) {
    LottieAssetAnimation(
        assetName = LottieAssets.LOADING,
        modifier = modifier,
        speed = speed,
        iterations = iterations,
        isPlaying = isPlaying,
        tint = tint
    )
}

/**
 * Doctor and Healthcare Companion Lottie Animation (`Doctor_advertise.json`)
 */
@Composable
fun DoctorLottieAnimation(
    modifier: Modifier = Modifier.size(180.dp),
    speed: Float = 1.0f,
    iterations: Int = LottieConstants.IterateForever,
    isPlaying: Boolean = true
) {
    LottieAssetAnimation(
        assetName = LottieAssets.DOCTOR_ADVERTISE,
        modifier = modifier,
        speed = speed,
        iterations = iterations,
        isPlaying = isPlaying
    )
}

/**
 * Watch & Sensor Scanning Lottie Animation (`watch_scanning.json`)
 */
@Composable
fun WatchScanningLottieAnimation(
    modifier: Modifier = Modifier.size(180.dp),
    speed: Float = 1.0f,
    iterations: Int = LottieConstants.IterateForever,
    isPlaying: Boolean = true
) {
    LottieAssetAnimation(
        assetName = LottieAssets.WATCH_SCANNING,
        modifier = modifier,
        speed = speed,
        iterations = iterations,
        isPlaying = isPlaying
    )
}
