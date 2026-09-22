package com.gone.ai.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.gone.ai.health.data.MockHealthDataSeeder
import com.gone.ai.health.service.MonitoringDataSource
import com.gone.ai.health.service.SavedWearable
import com.gone.ai.health.source.ble.NearbyWearable
import com.gone.ai.health.ui.PickerColors
import com.gone.ai.health.ui.WearablePicker
import com.gone.ai.ui.components.DoctorLottieAnimation
import com.gone.ai.ui.components.LoadingLottieAnimation
import com.gone.ai.ui.components.WatchScanningLottieAnimation
import com.gone.ai.ui.theme.AccentGoldBg
import com.gone.ai.ui.theme.BaseBg
import com.gone.ai.ui.theme.BaseFg
import com.gone.ai.ui.theme.CardBg
import com.gone.ai.ui.theme.DarkBg
import com.gone.ai.ui.theme.DarkBorder
import com.gone.ai.ui.theme.DarkSurface
import com.gone.ai.ui.theme.DarkSurfaceElevated
import com.gone.ai.ui.theme.FontSans
import com.gone.ai.ui.theme.MutedBg
import com.gone.ai.ui.theme.MutedFg
import com.gone.ai.ui.theme.PrimaryBg
import com.gone.ai.ui.theme.PrimaryFg
import com.gone.ai.ui.theme.SecondaryBg
import com.gone.ai.ui.theme.TextPrimary
import com.gone.ai.ui.theme.TextSecondary
import com.gone.ai.ui.theme.TokenBorder
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    isDarkTheme: Boolean = false,
    onToggleTheme: () -> Unit = {},
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("gone_preferences", Context.MODE_PRIVATE) }

    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(prefs.getString("user_name", "") ?: "") }
    var age by rememberSaveable { mutableIntStateOf(prefs.getInt("user_age", 24).coerceIn(1, 110)) }
    var contact by rememberSaveable { mutableStateOf(prefs.getString("family_contact", "") ?: "") }
    var showNameError by rememberSaveable { mutableStateOf(false) }
    var isSeedingMockData by remember { mutableStateOf(false) }

    // Chosen from a BLE scan; saved only when onboarding finishes. The wearable is a BLE
    // device, so the bonded Classic Bluetooth list this step used to show never contained it.
    var selectedWearable by remember { mutableStateOf<NearbyWearable?>(null) }

    fun saveProfile(simulatedVitals: Boolean, connectedDeviceName: String) {
        prefs.edit {
            putBoolean("onboarding_completed", true)
            putBoolean("reboarding_v2_completed", true)
            putString("user_name", name.trim())
            putInt("user_age", age)
            putString("family_contact", contact.trim())
            putString("emergency_contact", contact.trim())
            putString("connected_device_name", connectedDeviceName)
        }
        MonitoringDataSource.setSimulationEnabled(context, simulatedVitals)
    }

    fun requireName(): Boolean {
        if (name.trim().isNotBlank()) return true
        showNameError = true
        step = 0
        return false
    }

    fun completeWithDevice() {
        if (!requireName()) return
        val device = selectedWearable ?: return
        scope.launch {
            // Only demo history is removed. This used to wipe the health record
            // unconditionally, so redoing onboarding erased a real user's vitals and alerts.
            if (MonitoringDataSource.isSimulationEnabled(context)) {
                MockHealthDataSeeder.clearAll(context)
            }
            val deviceName = device.name ?: device.address
            MonitoringDataSource.saveWearable(context, SavedWearable(device.address, deviceName))
            saveProfile(simulatedVitals = false, connectedDeviceName = deviceName)
            onFinish()
        }
    }

    /**
     * Visible on the device step. It used to be unlocked by tapping an animation six
     * times. It stays next to the wearable picker for anyone trying the app before the
     * wearable is built; simulated readings are labelled as such everywhere they appear.
     */
    fun startWithDemoData() {
        if (isSeedingMockData || !requireName()) return
        isSeedingMockData = true
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        scope.launch {
            try {
                MockHealthDataSeeder.seed(
                    context,
                    MockHealthDataSeeder.DemoProfile(
                        name = name.trim(),
                        age = age,
                        emergencyContact = contact.trim().ifBlank { null }
                    )
                )
                saveProfile(simulatedVitals = true, connectedDeviceName = "Simulated wearable")
                Toast.makeText(context, "Demo mode: simulated vitals and a day of sample history", Toast.LENGTH_LONG).show()
                onFinish()
            } finally {
                isSeedingMockData = false
            }
        }
    }

    val dark = isDarkTheme
    val background = if (dark) {
        Brush.verticalGradient(listOf(DarkBg, DarkSurface, Color(0xFF211F19)))
    } else {
        Brush.verticalGradient(listOf(BaseBg, Color(0xFFFFFAEC), SecondaryBg))
    }
    val content = if (dark) TextPrimary else BaseFg
    val muted = if (dark) TextSecondary else MutedFg
    val accent = if (dark) AccentGoldBg else PrimaryBg
    val accentFg = if (dark) PrimaryBg else PrimaryFg

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            OnboardingTopBar(
                step = step,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(30.dp))

                when (step) {
                    0 -> IntroStep(
                        name = name,
                        showError = showNameError,
                        isDarkTheme = dark,
                        onNameChange = {
                            name = it
                            if (it.trim().isNotBlank()) showNameError = false
                        },
                        onDone = {
                            if (name.trim().isBlank()) {
                                showNameError = true
                            } else {
                                focusManager.moveFocus(FocusDirection.Down)
                                step = 1
                            }
                        }
                    )
                    1 -> AgeStep(age = age, isDarkTheme = dark, onAgeChange = { age = it })
                    2 -> ContactStep(contact = contact, isDarkTheme = dark, onContactChange = { contact = it })
                    else -> {
                        SensorStep(
                            selectedAddress = selectedWearable?.address,
                            isDarkTheme = dark,
                            onSelect = { selectedWearable = it }
                        )
                        DemoDataOption(
                            isDarkTheme = dark,
                            busy = isSeedingMockData,
                            onUseDemoData = ::startWithDemoData
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (step) {
                        0 -> DoctorLottieAnimation(modifier = Modifier.size(332.dp))
                        1 -> LoadingLottieAnimation(modifier = Modifier.size(322.dp))
                        2 -> LoadingLottieAnimation(modifier = Modifier.size(322.dp))
                        else -> Box(
                            modifier = Modifier.size(332.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            WatchScanningLottieAnimation(
                                modifier = Modifier.size(if (isSeedingMockData) 280.dp else 332.dp),
                                speed = if (isSeedingMockData) 1.35f else 1f
                            )
                            if (isSeedingMockData) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(34.dp),
                                    strokeWidth = 3.dp,
                                    color = accent
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 36.dp)
            ) {
                Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
                ) {
                IconButton(
                    onClick = { if (step > 0) step -= 1 },
                    enabled = step > 0,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = if (step > 0) content else Color.Transparent)
                }

                Button(
                    onClick = {
                        when (step) {
                            0 -> if (name.trim().isBlank()) showNameError = true else step = 1
                            1 -> step = 2
                            2 -> {
                                focusManager.clearFocus()
                                step = 3
                            }
                            else -> completeWithDevice()
                        }
                    },
                    enabled = step != 3 || selectedWearable != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = accentFg,
                        disabledContainerColor = if (dark) DarkSurfaceElevated else MutedBg,
                        disabledContentColor = muted
                    ),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 15.dp)
                ) {
                    Text(
                        text = if (step == 3) "Open G-one" else "Continue",
                        fontFamily = FontSans,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                }
                }
            }
        }
    }
}

@Composable
private fun OnboardingTopBar(
    step: Int,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val content = if (isDarkTheme) TextPrimary else BaseFg
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (step == 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onToggleTheme,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "Toggle theme",
                        tint = content,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroStep(
    name: String,
    showError: Boolean,
    isDarkTheme: Boolean,
    onNameChange: (String) -> Unit,
    onDone: () -> Unit
) {
    StepHeader(
        title = "What should we call you?",
        isDarkTheme = isDarkTheme
    )
    Spacer(Modifier.height(26.dp))
    OnboardingTextField(
        value = name,
        onValueChange = onNameChange,
        placeholder = "Your name",
        isDarkTheme = isDarkTheme,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        isError = showError,
        errorText = "Name is required"
    )
}

@Composable
private fun AgeStep(age: Int, isDarkTheme: Boolean, onAgeChange: (Int) -> Unit) {
    StepHeader(
        title = "How old are you?",
        isDarkTheme = isDarkTheme
    )
    Spacer(Modifier.height(24.dp))
    AgeWheel(age = age, isDarkTheme = isDarkTheme, onAgeChange = onAgeChange)
}

@Composable
private fun ContactStep(contact: String, isDarkTheme: Boolean, onContactChange: (String) -> Unit) {
    StepHeader(
        title = "Emergency contact",
        isDarkTheme = isDarkTheme
    )
    Spacer(Modifier.height(26.dp))
    OnboardingTextField(
        value = contact,
        onValueChange = onContactChange,
        placeholder = "Phone number",
        isDarkTheme = isDarkTheme,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done
        )
    )
}

@Composable
private fun SensorStep(
    selectedAddress: String?,
    isDarkTheme: Boolean,
    onSelect: (NearbyWearable) -> Unit
) {
    StepHeader(
        title = "Connect your device",
        isDarkTheme = isDarkTheme
    )
    Spacer(Modifier.height(22.dp))
    WearablePicker(
        selectedAddress = selectedAddress,
        colors = PickerColors(
            content = if (isDarkTheme) TextPrimary else BaseFg,
            muted = if (isDarkTheme) TextSecondary else MutedFg,
            accent = AccentGoldBg,
            onAccent = PrimaryBg
        ),
        onChoose = onSelect
    )
}

@Composable
private fun StepHeader(
    title: String,
    isDarkTheme: Boolean
) {
    val content = if (isDarkTheme) TextPrimary else BaseFg

    Text(
        text = title,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
        fontFamily = FontSans,
        fontSize = 32.sp,
        lineHeight = 37.sp,
        fontWeight = FontWeight.ExtraBold,
        color = content
    )
}

@Composable
private fun OnboardingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isDarkTheme: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    isError: Boolean = false,
    errorText: String? = null,
    autoFocus: Boolean = true
) {
    val content = if (isDarkTheme) TextPrimary else BaseFg
    val muted = if (isDarkTheme) TextSecondary else MutedFg
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(150)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                // The placeholder is only drawn; this is what TalkBack reads as the field's name.
                .semantics {
                    contentDescription = placeholder
                    if (isError && errorText != null) error(errorText)
                },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FontSans,
                fontWeight = FontWeight.Bold,
                color = content
            ),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.padding(vertical = 12.dp)) {
                    if (value.isBlank()) {
                        Text(
                            placeholder,
                            color = muted.copy(alpha = 0.55f),
                            fontFamily = FontSans,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clearAndSetSemantics { }
                        )
                    }
                    innerTextField()
                }
            }
        )

        // Underline: subtle when empty, highlighted with AccentGoldBg when contact/name exists so user knows they can edit it
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(
                    if (isError) Color(0xFFEF4444)
                    else if (value.isNotBlank()) AccentGoldBg
                    else muted.copy(alpha = 0.35f)
                )
        )

        if (isError && errorText != null) {
            Spacer(Modifier.height(6.dp))
            Text(errorText, color = Color(0xFFEF4444), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AgeWheel(age: Int, isDarkTheme: Boolean, onAgeChange: (Int) -> Unit) {
    val ages = remember { (1..110).toList() }
    val initialIndex = remember { (age - 1).coerceIn(0, ages.lastIndex) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val content = if (isDarkTheme) TextPrimary else BaseFg
    val muted = if (isDarkTheme) TextSecondary else MutedFg

    val centerIndex by remember {
        derivedStateOf {
            val items = listState.layoutInfo.visibleItemsInfo
            if (items.isEmpty()) initialIndex else {
                val center = (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
                items.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - center) }?.index ?: initialIndex
            }
        }
    }

    LaunchedEffect(centerIndex) {
        ages.getOrNull(centerIndex)?.let {
            if (it != age) onAgeChange(it)
        }
    }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp)
            // A wheel cannot be scrolled with TalkBack, so it is exposed as an adjustable value.
            .clearAndSetSemantics {
                contentDescription = "Age"
                stateDescription = "${ages.getOrElse(centerIndex) { age }} years"
                progressBarRangeInfo = ProgressBarRangeInfo(age.toFloat(), 1f..110f, steps = 108)
                setProgress { target ->
                    val index = (target.roundToInt() - 1).coerceIn(0, ages.lastIndex)
                    scope.launch { listState.scrollToItem(index) }
                    true
                }
            }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        // Yellow underline aligned directly at the bottom of the current selected number
        Box(
            modifier = Modifier
                .offset(y = 22.dp)
                .width(54.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(AccentGoldBg)
        )
        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
            contentPadding = PaddingValues(vertical = 39.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(ages) { index, item ->
                val selected = index == centerIndex
                Box(modifier = Modifier.fillMaxWidth().height(42.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "$item",
                        fontFamily = FontSans,
                        fontSize = if (selected) 30.sp else 18.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (selected) content else muted.copy(alpha = 0.45f)
                    )
                }
            }
        }
    }
}

/**
 * The way in without hardware: simulated vitals and a day of sample history, so the app
 * can be tried before the wearable is built.
 */
@Composable
private fun DemoDataOption(
    isDarkTheme: Boolean,
    busy: Boolean,
    onUseDemoData: () -> Unit
) {
    val muted = if (isDarkTheme) TextSecondary else MutedFg
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
    ) {
        Text(
            text = "No wearable yet? Try G-one with simulated vitals — they are always labelled as simulated.",
            fontFamily = FontSans,
            fontSize = 13.sp,
            color = muted
        )
        TextButton(
            onClick = onUseDemoData,
            enabled = !busy,
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(
                text = if (busy) "Preparing demo data…" else "Use simulated vitals and sample history",
                fontFamily = FontSans,
                fontWeight = FontWeight.Bold,
                color = if (isDarkTheme) AccentGoldBg else PrimaryBg
            )
        }
    }
}
