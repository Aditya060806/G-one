package com.infinity.ai.ui.screens

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import com.infinity.ai.data.library.EntryType
import com.infinity.ai.data.library.LibraryEntry
import com.infinity.ai.ui.components.BreadcrumbHeader
import com.infinity.ai.ui.components.BreadcrumbItem
import com.infinity.ai.ui.components.HeaderActionPill
import com.infinity.ai.ui.components.PureBreadcrumbText
import com.infinity.ai.ui.theme.*
import com.infinity.ai.viewmodel.LibraryViewModel
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
                        BreadcrumbItem("Library")
                    ),
                    isDarkTheme = dark
                )

                AnimatedVisibility(
                    visible = !isScrolled,
                    enter = fadeIn(tween(180)) + expandHorizontally(),
                    exit = fadeOut(tween(140)) + shrinkHorizontally()
                ) {
                    HeaderActionPill(
                        icon = Icons.Default.FilterList,
                        label = if (selectedType != null) "Filtered" else "All",
                        darkTheme = dark,
                        onClick = { if (selectedType != null) vm.setFilter(null) else vm.setFilter(EntryType.NOTE) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Your saved AI-generated content",
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
                    .padding(horizontal = 16.dp, vertical = 13.dp),
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
                    modifier      = Modifier.weight(1f),
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
                    Icon(
                        Icons.Default.Close, "Clear",
                        tint = if (dark) TextSecondary else TextSecondaryLight,
                        modifier = Modifier.size(18.dp).clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.setSearch("")
                        }
                    )
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
            .clickable(
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
    var confirmDelete by remember { mutableStateOf(false) }
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

            if (!confirmDelete) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        confirmDelete = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline, "Delete",
                        tint = if (dark) TextDisabled else TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            confirmDelete = false
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close, "Cancel",
                            tint = if (dark) TextSecondary else TextSecondaryLight,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick  = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDelete()
                            confirmDelete = false
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete, "Confirm",
                            tint = ErrorRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
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
}

fun typeIcon(type: EntryType): ImageVector = when (type) {
    EntryType.PDF_SUMMARY -> Icons.Default.PictureAsPdf
    EntryType.OCR         -> Icons.Default.DocumentScanner
    EntryType.SCREENSHOT  -> Icons.Default.ScreenSearchDesktop
    EntryType.QUIZ        -> Icons.Default.Quiz
    EntryType.NOTE        -> Icons.Default.EditNote
}

