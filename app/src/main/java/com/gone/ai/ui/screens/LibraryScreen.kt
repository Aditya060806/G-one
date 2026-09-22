package com.gone.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.activity.result.contract.ActivityResultContracts
import com.gone.ai.data.library.EntryType
import com.gone.ai.data.library.LibraryEntry
import com.gone.ai.ui.components.BreadcrumbHeader
import com.gone.ai.ui.components.BreadcrumbItem
import com.gone.ai.ui.components.HeaderActionPill
import com.gone.ai.ui.components.PureBreadcrumbText
import com.gone.ai.ui.theme.*
import com.gone.ai.viewmodel.LibraryViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LibraryScreen(
    isDarkTheme   : Boolean,
    bottomPadding : Dp,
    onOpenEntry   : (Long) -> Unit,
    onNavigateBack: () -> Unit = {},
    onNavigateHome: () -> Unit = {},
    vm            : LibraryViewModel = viewModel()
) {
    val entries      by vm.entries.collectAsState()
    val selectedType by vm.selectedType.collectAsState()
    val searchQuery  by vm.searchQuery.collectAsState()
    val lastDeleted  by vm.lastDeleted.collectAsState()
    val importState  by vm.importState.collectAsState()
    val snackbar     = remember { SnackbarHostState() }
    val recordPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importRecord(it) }
    }

    LaunchedEffect(importState) {
        when (val state = importState) {
            is LibraryViewModel.ImportState.Done -> {
                vm.importHandled()
                onOpenEntry(state.entryId)
            }
            is LibraryViewModel.ImportState.Failed -> {
                snackbar.showSnackbar(state.message)
                vm.importHandled()
            }
            else -> Unit
        }
    }

    // Deleting is immediate; the snackbar offers the way back.
    LaunchedEffect(lastDeleted) {
        val entry = lastDeleted ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "Deleted \"${entry.title.take(40)}\"",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete() else vm.undoExpired(entry)
    }
    val listState    = rememberLazyListState()
    val isScrolled   by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 10
        }
    }
    val dark = isDarkTheme
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (dark) ModernBgDark else ModernBgLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = bottomPadding)
        ) {
            Spacer(Modifier.height(16.dp))

            // ── Header (Sticky breadcrumb text, non-sticky action pill) ──────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PureBreadcrumbText(
                    items = listOf(
                        BreadcrumbItem("Home", onNavigateHome),
                        BreadcrumbItem("Tools", onNavigateBack),
                        BreadcrumbItem("Vault")
                    ),
                    isDarkTheme = dark
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(
                        visible = !isScrolled && selectedType != null,
                        enter = fadeIn(tween(180)) + expandHorizontally(),
                        exit = fadeOut(tween(140)) + shrinkHorizontally()
                    ) {
                        HeaderActionPill(
                            icon = Icons.Default.FilterListOff,
                            label = "Clear filter",
                            darkTheme = dark,
                            onClick = { vm.setFilter(null) }
                        )
                    }
                    HeaderActionPill(
                        icon = Icons.AutoMirrored.Filled.NoteAdd,
                        label = "Add record",
                        darkTheme = dark,
                        onClick = { recordPicker.launch(arrayOf("application/pdf", "image/*")) }
                    )
                }
            }

            (importState as? LibraryViewModel.ImportState.Reading)?.let { reading ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "${reading.fileName}: ${reading.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dark) TextSecondary else TextSecondaryLight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Saved results, and records you added for G-one to use",
                style = MaterialTheme.typography.bodyMedium,
                color = if (dark) Color(0xFF94A3B8) else Color(0xFF64748B),
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(Modifier.height(18.dp))

            // ── Search bar ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .shadow(
                        elevation = if (dark) 0.dp else 2.dp,
                        shape = RoundedCornerShape(16.dp),
                        ambientColor = LightShadow,
                        spotColor = LightShadow
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (dark) ModernCardDark else ModernCardLight)
                    .border(1.dp, if (dark) ModernBorderDark else ModernBorderLight, RoundedCornerShape(16.dp))
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.Search, null,
                    tint = if (dark) TextSecondary else TextSecondaryLight,
                    modifier = Modifier.size(18.dp)
                )
                BasicTextField(
                    value         = searchQuery,
                    onValueChange = { vm.setSearch(it) },
                    modifier      = Modifier.weight(1f).semantics { contentDescription = "Search saved content" },
                    textStyle     = MaterialTheme.typography.bodyMedium.copy(
                        color = if (dark) TextPrimary else TextPrimaryLight
                    ),
                    cursorBrush   = SolidColor(ModernBlue),
                    singleLine    = true,
                    decorationBox = { inner ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                "Search saved content…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (dark) TextSecondary else TextSecondaryLight
                            )
                        }
                        inner()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.setSearch("")
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Close, "Clear search",
                            tint = if (dark) TextSecondary else TextSecondaryLight,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Filter chips ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VaultChip("All", selectedType == null, ModernBlue, dark) { vm.setFilter(null) }
                EntryType.entries.forEach { type ->
                    VaultChip(
                        label    = type.label,
                        selected = selectedType == type,
                        color    = typeColor(type),
                        dark     = dark,
                        onClick  = { vm.setFilter(if (selectedType == type) null else type) }
                    )
                }
            }

            // ── Stat pills ────────────────────────────────────────────────────
            if (selectedType == null && searchQuery.isBlank() && entries.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EntryType.entries.forEach { type ->
                        val c = entries.count { it.type == type }
                        if (c > 0) VaultStatPill(type.label, c, typeColor(type), dark)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Content ───────────────────────────────────────────────────────
            if (entries.isEmpty()) {
                VaultEmptyState(dark, searchQuery.isNotBlank())
            } else {
                LazyColumn(
                    state               = listState,
                    contentPadding      = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        VaultEntryCard(
                            entry    = entry,
                            dark     = dark,
                            onClick  = { onOpenEntry(entry.id) },
                            onDelete = { vm.delete(entry) }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = bottomPadding + 12.dp, start = 16.dp, end = 16.dp)
        )
    }
}

// ── Filter chip ───────────────────────────────────────────────────────────────

@Composable
private fun VaultChip(label: String, selected: Boolean, color: Color, dark: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val isPressed by interaction.collectIsPressedAsState()

    val animatedBg by animateColorAsState(
        targetValue = if (selected) color.copy(alpha = if (dark) 0.22f else 0.14f)
                      else if (dark) ModernCardDark else ModernCardLight,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chipBg"
    )
    val animatedBorder by animateColorAsState(
        targetValue = if (selected) color.copy(alpha = if (dark) 0.60f else 0.45f)
                      else if (dark) ModernBorderDark else ModernBorderLight,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chipBorder"
    )
    val animatedTextColor by animateColorAsState(
        targetValue = if (selected) color else if (dark) TextSecondary else TextSecondaryLight,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chipText"
    )
    val animatedElevation by animateDpAsState(
        targetValue = if (isPressed) 0.dp else if (dark) 0.dp else (if (selected) 2.dp else 1.dp),
        label = "chipElevation"
    )

    Box(
        modifier = Modifier
            .pressScale(interaction, pressedScale = 0.95f)
            .shadow(
                elevation = animatedElevation,
                shape = RoundedCornerShape(20.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(20.dp))
            .background(animatedBg)
            .border(
                1.dp,
                animatedBorder,
                RoundedCornerShape(20.dp)
            )
            .clickable(role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style      = MaterialTheme.typography.labelMedium,
            color      = animatedTextColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

// ── Stat pill ─────────────────────────────────────────────────────────────────

@Composable
private fun VaultStatPill(label: String, count: Int, color: Color, dark: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (dark) ModernCardDark else ModernCardLight)
            .border(1.dp, if (dark) ModernBorderDark else ModernBorderLight, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (dark) TextSecondary else TextSecondaryLight
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Entry card ────────────────────────────────────────────────────────────────

@Composable
private fun VaultEntryCard(
    entry: LibraryEntry, dark: Boolean, onClick: () -> Unit, onDelete: () -> Unit
) {
    val color = typeColor(entry.type)
    val fmt   = remember { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val isPressed by interaction.collectIsPressedAsState()
    val elevation by animateDpAsState(
        targetValue = if (isPressed) 1.dp else (if (dark) 0.dp else 4.dp),
        label = "entryCardElevation"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.98f)
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(24.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(24.dp))
            .background(if (dark) ModernCardDark else ModernCardLight)
            .border(1.dp, if (dark) ModernBorderDark else ModernBorderLight, RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "Open",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
    ) {
        // Top accent strip
        Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(color.copy(alpha = 0.5f)))

        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(typeIcon(entry.type), null, tint = color, modifier = Modifier.size(20.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = if (dark) TextPrimary else TextPrimaryLight,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.content.take(120).replace("\n", " "),
                    style      = MaterialTheme.typography.bodySmall,
                    color      = if (dark) TextSecondary else TextSecondaryLight,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(color.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            entry.type.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        fmt.format(Date(entry.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dark) TextDisabled else TextTertiary
                    )
                }
            }

            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onDelete()
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.DeleteOutline, "Delete ${entry.title}",
                    tint = if (dark) TextSecondary else TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun VaultEmptyState(dark: Boolean, isSearching: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(if (dark) Color(0xFF1E293B) else ModernBlueSubtle, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isSearching) Icons.Default.SearchOff else Icons.Default.AutoStories,
                null,
                tint = ModernBlue,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (isSearching) "No results found" else "Vault is empty",
            style      = MaterialTheme.typography.titleMedium,
            color      = if (dark) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (isSearching) "Try different keywords"
            else "Generate a summary, OCR scan, or quiz —\nit will be saved here automatically.",
            style       = MaterialTheme.typography.bodyMedium,
            color       = if (dark) TextSecondary else TextSecondaryLight,
            textAlign   = TextAlign.Center,
            lineHeight  = 22.sp
        )
    }
}

// ── Type helpers (public — used by ChatScreen save action) ────────────────────

fun typeColor(type: EntryType): Color = when (type) {
    EntryType.PDF_SUMMARY -> Color(0xFF10B981)
    EntryType.OCR         -> ModernBlue
    EntryType.SCREENSHOT  -> Color(0xFF8B5CF6)
    EntryType.QUIZ        -> Color(0xFF10B981)
    EntryType.NOTE        -> Color(0xFFF59E0B)
    EntryType.MEDICAL_RECORD -> Color(0xFFE05D5D)
}

fun typeIcon(type: EntryType): ImageVector = when (type) {
    EntryType.PDF_SUMMARY -> Icons.Default.PictureAsPdf
    EntryType.OCR         -> Icons.Default.DocumentScanner
    EntryType.SCREENSHOT  -> Icons.Default.ScreenSearchDesktop
    EntryType.QUIZ        -> Icons.Default.Quiz
    EntryType.NOTE        -> Icons.Default.EditNote
    EntryType.MEDICAL_RECORD -> Icons.Default.MedicalInformation
}

