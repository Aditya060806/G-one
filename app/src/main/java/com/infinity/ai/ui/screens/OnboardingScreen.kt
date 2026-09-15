package com.infinity.ai.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.infinity.ai.health.data.MockHealthDataSeeder
import com.infinity.ai.ui.components.DoctorLottieAnimation
import com.infinity.ai.ui.components.LoadingLottieAnimation
import com.infinity.ai.ui.components.WatchScanningLottieAnimation
import com.infinity.ai.ui.theme.AccentGoldBg
import com.infinity.ai.ui.theme.BaseBg
import com.infinity.ai.ui.theme.BaseFg
import com.infinity.ai.ui.theme.CardBg
import com.infinity.ai.ui.theme.DarkBg
import com.infinity.ai.ui.theme.DarkBorder
import com.infinity.ai.ui.theme.DarkSurface
import com.infinity.ai.ui.theme.DarkSurfaceElevated
import com.infinity.ai.ui.theme.FontSans
import com.infinity.ai.ui.theme.MutedBg
import com.infinity.ai.ui.theme.MutedFg
import com.infinity.ai.ui.theme.PrimaryBg
import com.infinity.ai.ui.theme.PrimaryFg
import com.infinity.ai.ui.theme.SecondaryBg
import com.infinity.ai.ui.theme.TextPrimary
import com.infinity.ai.ui.theme.TextSecondary
import com.infinity.ai.ui.theme.TokenBorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

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
    var language by rememberSaveable { mutableStateOf(prefs.getString("preferred_language", "en") ?: "en") }
    var showNameError by rememberSaveable { mutableStateOf(false) }
    var mockTapCount by rememberSaveable { mutableIntStateOf(0) }
    var isSeedingMockData by remember { mutableStateOf(false) }

    val bluetoothManager = remember {
        runCatching { context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager }.getOrNull()
    }
    val bluetoothAdapter = bluetoothManager?.adapter

    var hasBtPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var isBtEnabled by remember { mutableStateOf(bluetoothAdapter?.isEnabled == true) }
    var bondedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }

    fun refreshBluetoothDevices() {
        isBtEnabled = bluetoothAdapter?.isEnabled == true
        if (!isBtEnabled || !hasBtPermission || bluetoothAdapter == null) {
            bondedDevices = emptyList()
            selectedDevice = null
            return
        }

        try {
            @SuppressLint("MissingPermission")
            val devices = bluetoothAdapter.bondedDevices?.toList().orEmpty()
            bondedDevices = devices.sortedWith(compareByDescending<BluetoothDevice> { device ->
                val lowerName = runCatching { device.name?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
                listOf("watch", "band", "fit", "sensor", "health", "ring").any(lowerName::contains)
            }.thenBy { device -> runCatching { device.name.orEmpty() }.getOrDefault("") })
            if (selectedDevice !in bondedDevices) selectedDevice = null
        } catch (_: SecurityException) {
            bondedDevices = emptyList()
            selectedDevice = null
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grants[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                grants[Manifest.permission.BLUETOOTH_SCAN] == true
        } else {
            grants[Manifest.permission.BLUETOOTH] != false
        }
        refreshBluetoothDevices()
    }

    LaunchedEffect(hasBtPermission, isBtEnabled) {
        refreshBluetoothDevices()
    }

    DisposableEffect(context, hasBtPermission) {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                refreshBluetoothDevices()
            }
        }
        runCatching { context.registerReceiver(receiver, filter) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        } else {
            btPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH))
        }
    }

    fun openBluetoothSettings() {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    fun saveProfile(mockMode: Boolean, connectedDeviceName: String) {
        prefs.edit()
            .putBoolean("onboarding_completed", true)
            .putBoolean("reboarding_v2_completed", true)
            .putBoolean("is_mock_mode", mockMode)
            .putString("user_name", name.trim())
            .putInt("user_age", age)
            .putString("family_contact", contact.trim())
            .putString("emergency_contact", contact.trim())
            .putString("preferred_language", language)
            .putString("connected_device_name", connectedDeviceName)
            .apply()
    }

    fun completeWithDevice() {
        if (name.trim().isBlank()) {
            showNameError = true
            step = 0
            return
        }
        val device = selectedDevice ?: return
        scope.launch {
            MockHealthDataSeeder.clearAll(context)
            val deviceName = runCatching {
                @SuppressLint("MissingPermission")
                device.name ?: "Bluetooth Biosensor"
            }.getOrDefault("Bluetooth Biosensor")
            saveProfile(mockMode = false, connectedDeviceName = deviceName)
            onFinish()
        }
    }

    fun unlockMockMode() {
        if (isSeedingMockData) return
        isSeedingMockData = true
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        scope.launch {
            MockHealthDataSeeder.seed(context)
            saveProfile(mockMode = true, connectedDeviceName = "G-one BioPulse")
            Toast.makeText(context, "Developer data mode enabled", Toast.LENGTH_SHORT).show()
            onFinish()
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
                language = language,
                onToggleTheme = onToggleTheme,
                onLanguageChange = {
                    language = it
                    prefs.edit().putString("preferred_language", it).apply()
                }
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
                    else -> SensorStep(
                        hasBtPermission = hasBtPermission,
                        isBtEnabled = isBtEnabled,
                        bondedDevices = bondedDevices,
                        selectedDevice = selectedDevice,
                        isDarkTheme = dark,
                        onRequestPermission = ::requestBluetoothPermission,
                        onOpenBluetoothSettings = ::openBluetoothSettings,
                        onRefresh = ::refreshBluetoothDevices,
                        onSelectDevice = { selectedDevice = it }
                    )
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
                            modifier = Modifier
                                .size(332.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    mockTapCount += 1
                                    if (mockTapCount >= 6) unlockMockMode()
                                },
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
                    enabled = step != 3 || selectedDevice != null,
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
    language: String,
    onToggleTheme: () -> Unit,
    onLanguageChange: (String) -> Unit
) {
    val content = if (isDarkTheme) TextPrimary else BaseFg
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (step == 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                LanguageChip("English", language == "en") { onLanguageChange("en") }
                LanguageChip("Hindi", language == "hi") { onLanguageChange("hi") }
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
private fun LanguageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = if (selected) AccentGoldBg else MutedFg,
        fontFamily = FontSans
    )
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
    hasBtPermission: Boolean,
    isBtEnabled: Boolean,
    bondedDevices: List<BluetoothDevice>,
    selectedDevice: BluetoothDevice?,
    isDarkTheme: Boolean,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDevice: (BluetoothDevice) -> Unit
) {
    StepHeader(
        title = "Connect your device",
        isDarkTheme = isDarkTheme
    )
    Spacer(Modifier.height(22.dp))
    SensorConnectionPanel(
        hasBtPermission = hasBtPermission,
        isBtEnabled = isBtEnabled,
        bondedDevices = bondedDevices,
        selectedDevice = selectedDevice,
        isDarkTheme = isDarkTheme,
        onRequestPermission = onRequestPermission,
        onOpenBluetoothSettings = onOpenBluetoothSettings,
        onRefresh = onRefresh,
        onSelectDevice = onSelectDevice
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
        modifier = Modifier.fillMaxWidth(),
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
                .focusRequester(focusRequester),
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
                        Text(placeholder, color = muted.copy(alpha = 0.55f), fontFamily = FontSans, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp)
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

@Composable
private fun SensorConnectionPanel(
    hasBtPermission: Boolean,
    isBtEnabled: Boolean,
    bondedDevices: List<BluetoothDevice>,
    selectedDevice: BluetoothDevice?,
    isDarkTheme: Boolean,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDevice: (BluetoothDevice) -> Unit
) {
    val content = if (isDarkTheme) TextPrimary else BaseFg
    val muted = if (isDarkTheme) TextSecondary else MutedFg

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when {
            !hasBtPermission -> SensorActionRow(
                title = "Allow Bluetooth access",
                action = "Allow",
                isDarkTheme = isDarkTheme,
                onClick = onRequestPermission
            )
            !isBtEnabled -> SensorActionRow(
                title = "Turn on Bluetooth",
                action = "Open",
                isDarkTheme = isDarkTheme,
                onClick = onOpenBluetoothSettings
            )
            bondedDevices.isEmpty() -> SensorActionRow(
                title = "Pair your device first",
                action = "Pair",
                isDarkTheme = isDarkTheme,
                onClick = onOpenBluetoothSettings
            )
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Choose your device", fontFamily = FontSans, fontWeight = FontWeight.Bold, color = content)
                    IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Refresh, "Refresh devices", tint = muted, modifier = Modifier.size(19.dp))
                    }
                }
                bondedDevices.take(4).forEach { device ->
                    val deviceName = runCatching {
                        @SuppressLint("MissingPermission")
                        device.name ?: "Bluetooth Biosensor"
                    }.getOrDefault("Bluetooth Biosensor")
                    val selected = selectedDevice == device
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDevice(device) }
                            .padding(vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(deviceName, fontFamily = FontSans, fontWeight = FontWeight.Bold, color = if (selected) AccentGoldBg else content, fontSize = 16.sp)
                        if (selected) {
                            Icon(Icons.Default.Check, null, tint = AccentGoldBg, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        if (hasBtPermission && isBtEnabled) {
            Button(
                onClick = onRefresh,
                modifier = Modifier.align(Alignment.Start),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = if (isDarkTheme) AccentGoldBg else PrimaryBg
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Refresh devices", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SensorActionRow(
    title: String,
    action: String,
    isDarkTheme: Boolean,
    onClick: () -> Unit
) {
    val content = if (isDarkTheme) TextPrimary else BaseFg
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontFamily = FontSans, fontWeight = FontWeight.Bold, color = content, fontSize = 16.sp)
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = AccentGoldBg, contentColor = PrimaryBg),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 9.dp)
        ) {
            Text(action, fontWeight = FontWeight.Bold)
        }
    }
}
