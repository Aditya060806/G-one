package com.gone.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import com.gone.ai.data.library.EntryType
import com.gone.ai.ocr.TaskStatus
import com.gone.ai.data.library.LibraryEntry
import com.gone.ai.quiz.QuizParser
import com.gone.ai.ui.components.BreadcrumbItem
import com.gone.ai.ui.components.MarkdownText
import com.gone.ai.ui.components.PureBreadcrumbText
import com.gone.ai.ui.theme.*
import com.gone.ai.viewmodel.LibraryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One saved Vault entry, in full.
 *
 * Tapping an entry used to do nothing, so nothing saved could be read again. A saved quiz
 * opens as a quiz to answer; everything else as formatted, selectable text.
 */
@Composable
fun LibraryEntryScreen(
    entryId: Long,
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onAskAssistant: (title: String, text: String) -> Unit,
    vm: LibraryViewModel
) {
    var loaded by remember(entryId) { mutableStateOf(false) }
    var entry by remember(entryId) { mutableStateOf<LibraryEntry?>(null) }
    LaunchedEffect(entryId) {
        vm.observeEntry(entryId).collect {
            entry = it
            loaded = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDarkTheme) ModernBgDark else ModernBgLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = bottomPadding)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            PureBreadcrumbText(
                items = listOf(
                    BreadcrumbItem("Home", onNavigateHome),
                    BreadcrumbItem("Vault", onNavigateBack),
                    BreadcrumbItem("Entry")
                ),
                isDarkTheme = isDarkTheme
            )
            Spacer(Modifier.height(16.dp))

            val current = entry
            DisposableEffect(entryId) { onDispose { vm.keyValues.reset() } }
            when {
                current != null -> EntryBody(
                    entry = current,
                    isDarkTheme = isDarkTheme,
                    vm = vm,
                    onAsk = { onAskAssistant(current.title, current.content) },
                    onAskAbout = onAskAssistant,
                    onDelete = {
                        vm.delete(current)
                        onNavigateBack()
                    }
                )
                loaded -> Text(
                    "This entry is no longer in your Vault.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.EntryBody(
    entry: LibraryEntry,
    isDarkTheme: Boolean,
    vm: LibraryViewModel,
    onAsk: () -> Unit,
    onAskAbout: (String, String) -> Unit,
    onDelete: () -> Unit
) {
    val keyText by vm.keyValues.output.collectAsState()
    val keyStatus by vm.keyValues.status.collectAsState()
    val keySaved by vm.keyValues.saved.collectAsState()
    val color = typeColor(entry.type)
    val date = remember(entry.createdAt) {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(entry.createdAt))
    }
    val questions = remember(entry.content, entry.type) {
        if (entry.type == EntryType.QUIZ) QuizParser.parse(entry.content) else emptyList()
    }

    Text(
        entry.title,
        style = MaterialTheme.typography.titleLarge,
        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(entry.type.label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
        }
        Text(
            listOf(date, entry.sourceInfo).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            ResultActionsRow(
                text = entry.content,
                saved = false,
                isDarkTheme = isDarkTheme,
                actions = ResultActions(shareSubject = entry.title, onAskAssistant = onAsk)
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
            .toggleable(value = entry.useInAi, role = Role.Switch, onValueChange = { vm.setUseInAi(entry, it) })
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Use in G-one's answers about you",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (entry.useInAi) "The chat can quote this when you ask about yourself."
                else "The chat does not see this entry.",
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) TextSecondary else TextSecondaryLight
            )
        }
        Switch(checked = entry.useInAi, onCheckedChange = null)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (entry.type == EntryType.MEDICAL_RECORD && keyStatus == TaskStatus.Idle) {
            TextButton(onClick = { vm.pullKeyValues(entry) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Pull out key values", color = if (isDarkTheme) AccentGoldBg else PrimaryBg)
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Delete", color = ErrorRed)
        }
    }
    if (keyStatus != TaskStatus.Idle) {
        DocumentResultSection(
            text = keyText,
            status = keyStatus,
            saved = keySaved,
            isDarkTheme = isDarkTheme,
            onStop = { vm.keyValues.stop() },
            onRetry = { vm.pullKeyValues(entry) },
            actions = ResultActions(
                shareSubject = "Key values: ${entry.title}",
                onSave = { vm.keyValues.requestSave() },
                onAskAssistant = { onAskAbout("Key values: ${entry.title}", keyText) }
            ),
            modifier = Modifier.weight(1f, fill = false)
        )
        return
    }
    Spacer(Modifier.height(8.dp))

    if (questions.isNotEmpty()) {
        QuizPlayer(questions = questions, isDarkTheme = isDarkTheme, modifier = Modifier.weight(1f))
    } else {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            SelectionContainer {
                MarkdownText(text = entry.content, isDarkTheme = isDarkTheme, fontSize = MaterialTheme.typography.bodyMedium.fontSize)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
