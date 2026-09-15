package com.infinity.ai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinity.ai.ai.state.AIInferenceState
import com.infinity.ai.data.library.EntryType
import com.infinity.ai.data.library.LibraryRepository
import com.infinity.ai.model.ChatMessage
import com.infinity.ai.model.ChatSession
import com.infinity.ai.ui.components.AiBodyOrb
import com.infinity.ai.ui.components.BreadcrumbHeader
import com.infinity.ai.ui.components.BreadcrumbItem
import com.infinity.ai.ui.components.PureBreadcrumbText
import com.infinity.ai.ui.components.HeaderActionPill
import com.infinity.ai.ui.components.LoadingLottieAnimation
import com.infinity.ai.ui.components.toOrbState
import com.infinity.ai.ui.theme.*
import com.infinity.ai.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateToVoice: () -> Unit,
    onNavigateHome: () -> Unit = {},
    chatViewModel: ChatViewModel = viewModel()
) {
    val sessions        by chatViewModel.sessions.collectAsState()
    val currentSessionId by chatViewModel.currentSessionId.collectAsState()
    val messages        by chatViewModel.messages.collectAsState()
    val input           by chatViewModel.input.collectAsState()
    val showSuggestions by chatViewModel.showSuggestions.collectAsState()
    val aiState         by chatViewModel.aiState.collectAsState()
    val isExtracting    by chatViewModel.isExtracting.collectAsState()
    val extractProgress by chatViewModel.extractionProgress.collectAsState()
    val listState       = rememberLazyListState()
    val isChatScrolled  by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 16
        }
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context         = LocalContext.current
    val scope           = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val dark            = isDarkTheme
    var textForSelection by remember { mutableStateOf<String?>(null) }
    var showHistoryDrawer by remember { mutableStateOf(false) }

    val isImeVisible = WindowInsets.isImeVisible
    val effectiveBottomPadding = if (isImeVisible) 0.dp else bottomPadding

    val isGenerating = aiState is AIInferenceState.Thinking ||
                       aiState is AIInferenceState.Responding

    LaunchedEffect(messages.size, isGenerating, isImeVisible) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun copyToClipboard(text: String, label: String = "AI Response") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun shareText(prompt: String, response: String) {
        val shareBody = if (prompt.isNotBlank()) "Q: $prompt\n\nA: $response" else response
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, prompt.take(40).ifBlank { "G-one Chat" })
            putExtra(Intent.EXTRA_TEXT, shareBody)
        }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    }

    fun saveToVault(context: Context, prompt: String, response: String) {
        scope.launch(Dispatchers.IO) {
            val repo = LibraryRepository.getInstance(context)
            repo.save(
                type       = EntryType.NOTE,
                content    = if (prompt.isNotBlank()) "Q: $prompt\n\nA: $response" else response,
                title      = prompt.take(60).ifBlank { "Chat Response" },
                sourceInfo = "G-one Chat"
            )
            scope.launch { snackbarHostState.showSnackbar("Saved to Knowledge Vault") }
        }
    }

    val bgModifier = if (dark) {
        Modifier.background(DarkBg)
    } else {
        Modifier.background(BaseBg)
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData    = data,
                    containerColor  = if (dark) ModernCardDark else PrimaryBg,
                    contentColor    = if (dark) TextPrimary else PrimaryFg,
                    shape           = RoundedCornerShape(16.dp),
                    modifier        = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(bgModifier)
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = effectiveBottomPadding)
                    .imePadding()
            ) {
                ChatHeader(
                    isDarkTheme    = dark,
                    onNavigateHome = onNavigateHome,
                    onOpenHistory  = { showHistoryDrawer = true },
                    onNewChat      = { chatViewModel.createNewChat() },
                    isScrolled     = isChatScrolled
                )

                AnimatedVisibility(visible = isExtracting) {
                    ExtractionProgressBar(progress = extractProgress, isDarkTheme = dark)
                }

                if (showSuggestions && messages.isEmpty()) {
                    EmptyState(
                        modifier          = Modifier.weight(1f),
                        isDarkTheme       = dark,
                        aiState           = aiState,
                        onSuggestionClick = { chatViewModel.startFromSuggestion(it) }
                    )
                } else {
                    val promptMap = remember(messages) {
                        buildMap {
                            messages.forEachIndexed { i, msg ->
                                if (!msg.isUser) {
                                    val prev = messages.getOrNull(i - 1)
                                    put(msg.id, prev?.text ?: "")
                                }
                            }
                        }
                    }

                    LazyColumn(
                        state           = listState,
                        modifier        = Modifier.weight(1f),
                        contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            val streaming = isGenerating && msg == messages.last() && !msg.isUser
                            ChatBubble(
                                message      = msg,
                                isDarkTheme  = dark,
                                isStreaming  = streaming,
                                prompt       = promptMap[msg.id] ?: "",
                                onCopy       = { copyToClipboard(msg.text, if (msg.isUser) "Prompt" else "AI Response") },
                                onShare      = {
                                    if (msg.isUser) {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, msg.text)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share prompt"))
                                    } else {
                                        shareText(promptMap[msg.id] ?: "", msg.text)
                                    }
                                },
                                onSave       = {
                                    if (msg.isUser) {
                                        saveToVault(context, "User Prompt", msg.text)
                                    } else {
                                        saveToVault(context, promptMap[msg.id] ?: "", msg.text)
                                    }
                                },
                                onRegenerate = {
                                    val p = promptMap[msg.id] ?: ""
                                    if (p.isNotBlank()) chatViewModel.retry(msg.id, p)
                                },
                                onEditPrompt = {
                                    chatViewModel.onInputChange(msg.text)
                                },
                                onSelectText = { selectedText ->
                                    textForSelection = selectedText
                                }
                            )
                        }
                    }
                }

                ChatInputBar(
                    input         = input,
                    isDarkTheme   = dark,
                    isGenerating  = isGenerating,
                    onInputChange = { chatViewModel.onInputChange(it) },
                    onSend        = { chatViewModel.sendMessage(); keyboardController?.hide() },
                    onStop        = { chatViewModel.stopGeneration() },
                    onVoice       = onNavigateToVoice
                )

                if (textForSelection != null) {
                    TextSelectionBottomSheet(
                        text = textForSelection!!,
                        isDarkTheme = dark,
                        onDismiss = { textForSelection = null }
                    )
                }

                if (showHistoryDrawer) {
                    ChatHistorySheet(
                        sessions         = sessions,
                        currentSessionId = currentSessionId,
                        isDarkTheme      = dark,
                        onDismiss        = { showHistoryDrawer = false },
                        onSelectSession  = { chatViewModel.selectSession(it) },
                        onNewChat        = { chatViewModel.createNewChat() },
                        onDeleteSession  = { chatViewModel.deleteSession(it) }
                    )
                }
            }
        }
    }
}

// ── Header ─────────────────────────────────────────────────────────────────────

@Composable
private fun ChatHeader(
    isDarkTheme: Boolean,
    onNavigateHome: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewChat: () -> Unit,
    isScrolled: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Breadcrumb: Home / Assist (Sticky, background-less, border-less text)
        PureBreadcrumbText(
            items = listOf(
                BreadcrumbItem("Home", onNavigateHome),
                BreadcrumbItem("Assist")
            ),
            isDarkTheme = isDarkTheme
        )

        // Action Buttons: Non-sticky, hide when chat messages scroll
        AnimatedVisibility(
            visible = !isScrolled,
            enter = fadeIn(tween(180)) + expandHorizontally(),
            exit = fadeOut(tween(140)) + shrinkHorizontally()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeaderActionPill(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    label = "Chats",
                    darkTheme = isDarkTheme,
                    onClick = onOpenHistory
                )
                HeaderActionPill(
                    icon = Icons.Default.Add,
                    label = "New",
                    darkTheme = isDarkTheme,
                    onClick = onNewChat
                )
            }
        }
    }
}

// ── Chat History Drawer ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHistorySheet(
    sessions: List<ChatSession>,
    currentSessionId: String,
    isDarkTheme: Boolean,
    onDismiss: () -> Unit,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteSession: (String) -> Unit
) {
    val isExpanded = sessions.size > 4
    val drawerHeightRatio by animateFloatAsState(
        targetValue = if (isExpanded) 0.92f else 0.55f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "drawerHeightRatio"
    )

    var searchQuery by remember { mutableStateOf("") }
    val showSearch = sessions.size >= 5
    val filteredSessions = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) sessions
        else sessions.filter { session ->
            session.title.contains(searchQuery, ignoreCase = true) ||
            session.messages.any { it.text.contains(searchQuery, ignoreCase = true) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = if (isDarkTheme) DarkSurfaceElevated else CardBg,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isDarkTheme) DarkBorder else TokenBorder)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(drawerHeightRatio)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Previous Consultations",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isDarkTheme) TextPrimary else BaseFg,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (sessions.isEmpty()) "No saved consultations" else "${sessions.size} conversations",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDarkTheme) TextSecondary else MutedFg
                    )
                }

                Button(
                    onClick = {
                        onNewChat()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDarkTheme) AccentGoldBg else PrimaryBg,
                        contentColor = if (isDarkTheme) AccentGoldFg else PrimaryFg
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Chat", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            AnimatedVisibility(
                visible = showSearch,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDarkTheme) Color(0xFF1E1E1E) else SecondaryBg)
                            .border(1.dp, if (isDarkTheme) DarkBorder else TokenBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (isDarkTheme) AccentGoldBg else PrimaryBg,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = if (isDarkTheme) TextPrimary else BaseFg,
                                fontSize = 14.sp
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(if (isDarkTheme) AccentGoldBg else PrimaryBg),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search consultations...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isDarkTheme) TextSecondary else MutedFg,
                                        fontSize = 14.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = if (isDarkTheme) TextSecondary else MutedFg,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = if (isDarkTheme) DarkBorder else TokenBorder)

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No previous chats yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDarkTheme) TextSecondary else MutedFg
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (filteredSessions.isEmpty() && searchQuery.isNotBlank()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No consultations match \"$searchQuery\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isDarkTheme) TextSecondary else MutedFg
                                )
                            }
                        }
                    } else {
                        items(filteredSessions, key = { it.id }) { session ->
                        val isCurrent = session.id == currentSessionId
                        val lastMessage = session.messages.lastOrNull()?.text ?: "No messages"

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isCurrent) (if (isDarkTheme) Color(0xFF221F18) else Color(0xFFF7F4EA))
                                    else (if (isDarkTheme) ModernCardDark else SecondaryBg)
                                )
                                .border(
                                    1.dp,
                                    if (isCurrent) (if (isDarkTheme) AccentGoldBg else PrimaryBg)
                                    else (if (isDarkTheme) DarkBorder else TokenBorder),
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    onSelectSession(session.id)
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = session.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isDarkTheme) TextPrimary else BaseFg,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isCurrent) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isDarkTheme) AccentGoldBg else PrimaryBg)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    "ACTIVE",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isDarkTheme) AccentGoldFg else PrimaryFg
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(4.dp))

                                    Text(
                                        text = lastMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isDarkTheme) TextSecondary else MutedFg,
                                        maxLines = 1
                                    )

                                    Spacer(Modifier.height(6.dp))

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formatRelativeTime(session.updatedAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp,
                                            color = if (isDarkTheme) TextDisabled else TextTertiary
                                        )
                                        Text(
                                            text = "•",
                                            fontSize = 11.sp,
                                            color = if (isDarkTheme) TextDisabled else TextTertiary
                                        )
                                        Text(
                                            text = "${session.messages.size} msgs",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp,
                                            color = if (isDarkTheme) TextDisabled else TextTertiary
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { onDeleteSession(session.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Delete chat",
                                        tint = if (isDarkTheme) TextSecondary.copy(alpha = 0.6f) else MutedFg.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "Yesterday"
        days < 7 -> "${days}d ago"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}

// ── Extraction progress bar ────────────────────────────────────────────────────

@Composable
private fun ExtractionProgressBar(progress: Float, isDarkTheme: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .shadow(
                elevation = if (isDarkTheme) 0.dp else 2.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDarkTheme) ModernCardDark else CardBg)
            .border(1.dp, if (isDarkTheme) DarkBorder else TokenBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LoadingLottieAnimation(modifier = Modifier.size(22.dp))
                Text(
                    "Setting up AI neural model…",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isDarkTheme) TextSecondary else MutedFg,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = AccentGoldBg,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress        = { progress },
            modifier        = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color           = AccentGoldBg,
            trackColor      = if (isDarkTheme) DarkBorder else SecondaryBg
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "This only happens once on first launch",
            style = MaterialTheme.typography.labelSmall,
            color = if (isDarkTheme) TextDisabled else TextTertiary
        )
    }
}

// ── Empty state ────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean,
    aiState: AIInferenceState,
    onSuggestionClick: (String) -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AiBodyOrb(orbState = aiState.toOrbState(), isDarkTheme = isDarkTheme, size = 96.dp)
        }
        Spacer(Modifier.height(18.dp))

        Text(
            "How can G-one assist you?",
            style = MaterialTheme.typography.headlineSmall,
            color = if (isDarkTheme) TextPrimary else BaseFg,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Ask clinical questions, decode lab reports, or review vital trends.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextSecondary else MutedFg
        )

        Spacer(Modifier.height(12.dp))

        // Privacy indicator badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (isDarkTheme) ModernCardDark else SecondaryBg)
                .border(1.dp, if (isDarkTheme) DarkBorder else TokenBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = if (isDarkTheme) AccentGoldBg else PrimaryBg,
                modifier = Modifier.size(13.dp)
            )
            Text(
                "On-Device AI • Private & Encrypted",
                style = MaterialTheme.typography.labelSmall,
                color = if (isDarkTheme) TextSecondary else MutedFg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "SUGGESTIONS",
            style = MaterialTheme.typography.labelSmall,
            color = if (isDarkTheme) TextSecondary else MutedFg,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(
                    Icons.Default.Favorite,
                    "What does my resting heart rate mean?",
                    "Analyze typical ranges and cardio trends"
                ),
                Triple(
                    Icons.Default.Science,
                    "Decode lab report values",
                    "Translate blood panel and metabolic jargon"
                ),
                Triple(
                    Icons.Default.Thermostat,
                    "Heat stress and hydration guidance",
                    "Understand temperature impact on vitals"
                ),
                Triple(
                    Icons.AutoMirrored.Filled.Chat,
                    "Ask any health or medical question",
                    "Get clear, actionable explanations"
                )
            ).forEach { (icon, title, sub) ->
                ModernSuggestionCard(
                    icon        = icon,
                    title       = title,
                    subtitle    = sub,
                    isDarkTheme = isDarkTheme,
                    onClick     = { onSuggestionClick(title) }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ModernSuggestionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isDarkTheme: Boolean,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.98f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "suggestionCard"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(
                elevation = if (isDarkTheme) 0.dp else 2.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(20.dp))
            .background(if (isDarkTheme) ModernCardDark else CardBg)
            .border(1.dp, if (isDarkTheme) DarkBorder else TokenBorder, RoundedCornerShape(20.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (isDarkTheme) Color(0xFF262626) else SecondaryBg,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, null,
                    tint = if (isDarkTheme) AccentGoldBg else PrimaryBg,
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDarkTheme) TextPrimary else BaseFg,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkTheme) TextSecondary else MutedFg
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = (if (isDarkTheme) AccentGoldBg else PrimaryBg).copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ── Input bar ──────────────────────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    input: String,
    isDarkTheme: Boolean,
    isGenerating: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit
) {
    val canSend = input.isNotBlank() && !isGenerating

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isDarkTheme) DarkBg else BaseBg)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Text Input Capsule
            Row(
                modifier = Modifier
                    .weight(1f)
                    .shadow(
                        elevation = if (isDarkTheme) 0.dp else 2.dp,
                        shape = RoundedCornerShape(26.dp),
                        ambientColor = LightShadow,
                        spotColor = LightShadow
                    )
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (isDarkTheme) ModernCardDark else CardBg)
                    .border(
                        1.dp,
                        if (isDarkTheme) DarkBorder else TokenBorder,
                        RoundedCornerShape(26.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value         = input,
                    onValueChange = onInputChange,
                    modifier      = Modifier.weight(1f),
                    enabled       = !isGenerating,
                    textStyle     = MaterialTheme.typography.bodyMedium.copy(
                        color = if (isDarkTheme) TextPrimary else BaseFg,
                        fontSize = 15.sp,
                        lineHeight = 21.sp
                    ),
                    cursorBrush   = SolidColor(if (isDarkTheme) AccentGoldBg else PrimaryBg),
                    decorationBox = { innerTextField ->
                        if (input.isEmpty()) {
                            Text(
                                if (isGenerating) "Generating response…" else "Message G-one…",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                                color = if (isDarkTheme) TextSecondary else MutedFg
                            )
                        }
                        innerTextField()
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction      = ImeAction.Default,
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    maxLines = 5
                )
            }

            // Action Button (Send / Stop / Mic)
            val actionBg = when {
                isGenerating -> DestructiveBg
                canSend      -> if (isDarkTheme) AccentGoldBg else PrimaryBg
                else         -> if (isDarkTheme) ModernCardDark else SecondaryBg
            }
            val actionFg = when {
                isGenerating -> DestructiveFg
                canSend      -> if (isDarkTheme) AccentGoldFg else PrimaryFg
                else         -> if (isDarkTheme) AccentGoldBg else PrimaryBg
            }

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .shadow(
                        elevation = if (isDarkTheme) 0.dp else 3.dp,
                        shape = CircleShape,
                        ambientColor = LightShadow,
                        spotColor = LightShadow
                    )
                    .clip(CircleShape)
                    .background(actionBg, CircleShape)
                    .border(
                        1.dp,
                        if (!isGenerating && !canSend)
                            (if (isDarkTheme) DarkBorder else TokenBorder)
                        else Color.Transparent,
                        CircleShape
                    )
                    .clickable {
                        when {
                            isGenerating -> onStop()
                            canSend      -> onSend()
                            else         -> onVoice()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isGenerating) {
                    LoadingLottieAnimation(modifier = Modifier.size(36.dp))
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .background(actionFg, RoundedCornerShape(2.dp))
                    )
                } else {
                    Icon(
                        imageVector = if (canSend) Icons.Default.ArrowUpward else Icons.Default.Mic,
                        contentDescription = if (canSend) "Send" else "Voice",
                        tint = actionFg,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ── Chat bubble ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message     : ChatMessage,
    isDarkTheme : Boolean,
    isStreaming : Boolean,
    prompt      : String,
    onCopy      : () -> Unit,
    onShare     : () -> Unit,
    onSave      : () -> Unit,
    onRegenerate: () -> Unit,
    onEditPrompt: () -> Unit,
    onSelectText: (String) -> Unit
) {
    var showAiSheet by remember { mutableStateOf(false) }
    var showUserSheet by remember { mutableStateOf(false) }

    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        if (!message.isUser) {
            // Identity tag for AI message
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier              = Modifier.padding(bottom = 6.dp, start = 4.dp)
            ) {
                Box(
                    modifier         = Modifier
                        .size(20.dp)
                        .background(
                            if (isDarkTheme) Color(0xFF262626) else SecondaryBg,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome, null,
                        tint = if (isDarkTheme) AccentGoldBg else PrimaryBg,
                        modifier = Modifier.size(11.dp)
                    )
                }
                Text(
                    "G-one",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDarkTheme) AccentGoldBg else PrimaryBg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp
                )
            }
        }

        if (message.isUser) {
            // User Bubble - With long-press to open User Message Actions Sheet
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .shadow(
                        elevation = if (isDarkTheme) 0.dp else 3.dp,
                        shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                        ambientColor = LightShadow,
                        spotColor = LightShadow
                    )
                    .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                    .background(if (isDarkTheme) AccentGoldBg else PrimaryBg)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showUserSheet = true }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    message.text,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = if (isDarkTheme) AccentGoldFg else PrimaryFg,
                    fontSize   = 15.sp,
                    lineHeight = 22.sp
                )
            }
        } else {
            // AI Response Bubble
            Column(
                modifier = Modifier.widthIn(max = 320.dp),
                horizontalAlignment = Alignment.Start
            ) {
                AiBubbleContent(
                    message     = message,
                    isDarkTheme = isDarkTheme,
                    isStreaming = isStreaming,
                    modifier    = Modifier
                        .combinedClickable(
                            onClick     = {},
                            onLongClick = { if (!isStreaming && message.text.isNotBlank()) showAiSheet = true }
                        )
                )

                // Clean bottom action toolbar (Copy, Save, Retry) - Share moved to drawer only
                AnimatedVisibility(
                    visible = !isStreaming && message.text.isNotBlank(),
                    enter   = fadeIn(tween(200)),
                    exit    = fadeOut(tween(150))
                ) {
                    Row(
                        modifier              = Modifier.padding(top = 4.dp, start = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        BubbleActionPill(Icons.Default.ContentCopy, "Copy", onCopy, isDarkTheme)
                        BubbleActionPill(Icons.Default.BookmarkBorder, "Save", onSave, isDarkTheme)
                        if (prompt.isNotBlank()) {
                            BubbleActionPill(Icons.Default.Refresh, "Retry", onRegenerate, isDarkTheme)
                        }
                    }
                }
            }
        }
    }

    if (showAiSheet) {
        MessageActionsSheet(
            isDarkTheme  = isDarkTheme,
            onDismiss    = { showAiSheet = false },
            onCopy       = { showAiSheet = false; onCopy() },
            onSelectText = { showAiSheet = false; onSelectText(message.text) },
            onShare      = { showAiSheet = false; onShare() },
            onSave       = { showAiSheet = false; onSave() },
            onRegenerate = { showAiSheet = false; onRegenerate() }
        )
    }

    if (showUserSheet) {
        UserMessageActionsSheet(
            isDarkTheme  = isDarkTheme,
            onDismiss    = { showUserSheet = false },
            onCopy       = { showUserSheet = false; onCopy() },
            onEdit       = { showUserSheet = false; onEditPrompt() },
            onSelectText = { showUserSheet = false; onSelectText(message.text) },
            onShare      = { showUserSheet = false; onShare() },
            onSave       = { showUserSheet = false; onSave() }
        )
    }
}

// ── AI bubble content ──────────────────────────────────────────────────────────

@Composable
private fun AiBubbleContent(
    message     : ChatMessage,
    isDarkTheme : Boolean,
    isStreaming : Boolean,
    modifier    : Modifier = Modifier
) {
    val isLoading = message.text.isEmpty() && isStreaming

    // Inline Lottie streaming cursor placeholder replacing the blinking string cursor
    val lottieCursorInline = remember {
        mapOf(
            "lottie_cursor" to InlineTextContent(
                Placeholder(
                    width = 16.sp,
                    height = 16.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                )
            ) {
                LoadingLottieAnimation(modifier = Modifier.size(16.dp))
            }
        )
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = if (isDarkTheme) 0.dp else 2.dp,
                shape = RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp))
            .background(if (isDarkTheme) ModernCardDark else CardBg)
            .border(
                1.dp,
                if (isDarkTheme) DarkBorder else TokenBorder,
                RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
            )
            .padding(
                horizontal = if (isLoading) 12.dp else 16.dp,
                vertical = if (isLoading) 6.dp else 13.dp
            )
    ) {
        if (isLoading) {
            LoadingLottieAnimation(modifier = Modifier.size(34.dp))
        } else {
            val segments = remember(message.text) { parseMessageSegments(message.text) }
            val context = LocalContext.current

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                segments.forEachIndexed { index, seg ->
                    val isLastSegment = index == segments.lastIndex
                    when (seg) {
                        is MessageSegment.PlainText -> {
                            if (seg.text.isNotBlank() || (isStreaming && isLastSegment)) {
                                MarkdownFormattedText(
                                    text               = seg.text,
                                    isDarkTheme        = isDarkTheme,
                                    isStreaming        = isStreaming,
                                    isLastSegment      = isLastSegment,
                                    lottieCursorInline = lottieCursorInline
                                )
                            }
                        }
                        is MessageSegment.CodeBlock -> {
                            CodeBlockView(
                                code        = seg.code,
                                language    = seg.language,
                                isDarkTheme = isDarkTheme,
                                onCopyCode  = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Code", seg.code))
                                }
                            )
                            if (isStreaming && isLastSegment) {
                                LoadingLottieAnimation(modifier = Modifier.size(18.dp).padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Markdown Formatted Text Renderer ──────────────────────────────────────────

private sealed interface MarkdownLineBlock {
    data class Header(val level: Int, val content: String) : MarkdownLineBlock
    data class BulletItem(val content: String) : MarkdownLineBlock
    data class NumberedItem(val number: String, val content: String) : MarkdownLineBlock
    data class Blockquote(val content: String) : MarkdownLineBlock
    data class Paragraph(val content: String) : MarkdownLineBlock
    object EmptyLine : MarkdownLineBlock
}

private fun parseMarkdownBlocks(raw: String): List<MarkdownLineBlock> {
    val lines = raw.lines()
    val blocks = mutableListOf<MarkdownLineBlock>()
    val bulletRegex = Regex("""^(\*|-|•)\s+(.*)""")
    val numberedRegex = Regex("""^(\d+)\.\s+(.*)""")

    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> {
                if (blocks.isNotEmpty() && blocks.last() !is MarkdownLineBlock.EmptyLine) {
                    blocks.add(MarkdownLineBlock.EmptyLine)
                }
            }
            trimmed.startsWith("### ") -> blocks.add(MarkdownLineBlock.Header(3, trimmed.removePrefix("### ").trim()))
            trimmed.startsWith("## ")  -> blocks.add(MarkdownLineBlock.Header(2, trimmed.removePrefix("## ").trim()))
            trimmed.startsWith("# ")   -> blocks.add(MarkdownLineBlock.Header(1, trimmed.removePrefix("# ").trim()))
            trimmed.startsWith("> ")   -> blocks.add(MarkdownLineBlock.Blockquote(trimmed.removePrefix("> ").trim()))
            bulletRegex.matches(trimmed) -> {
                val match = bulletRegex.find(trimmed)
                if (match != null) {
                    blocks.add(MarkdownLineBlock.BulletItem(match.groupValues[2]))
                } else {
                    blocks.add(MarkdownLineBlock.Paragraph(line))
                }
            }
            numberedRegex.matches(trimmed) -> {
                val match = numberedRegex.find(trimmed)
                if (match != null) {
                    blocks.add(MarkdownLineBlock.NumberedItem(match.groupValues[1], match.groupValues[2]))
                } else {
                    blocks.add(MarkdownLineBlock.Paragraph(line))
                }
            }
            else -> blocks.add(MarkdownLineBlock.Paragraph(line))
        }
    }
    while (blocks.isNotEmpty() && blocks.last() is MarkdownLineBlock.EmptyLine) {
        blocks.removeAt(blocks.lastIndex)
    }
    return if (blocks.isEmpty()) listOf(MarkdownLineBlock.Paragraph(raw)) else blocks
}

private val INLINE_MARKDOWN_REGEX = Regex(
    """(\*\*\*(.+?)\*\*\*|\*\*(.+?)\*\*|__(.+?)__|(?<!\*)\*([^*]+)\*(?!\*)|(?<!_)_([^_]+)_(?!_)|`([^`]+)`|~~([^~]+)~~)"""
)

private fun formatInlineMarkdown(
    text: String,
    isDarkTheme: Boolean,
    appendCursor: Boolean
): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        for (match in INLINE_MARKDOWN_REGEX.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1

            if (start > cursor) {
                append(text.substring(cursor, start))
            }

            val boldItalic = match.groupValues[2]
            val bold1 = match.groupValues[3]
            val bold2 = match.groupValues[4]
            val italic1 = match.groupValues[5]
            val italic2 = match.groupValues[6]
            val code = match.groupValues[7]
            val strike = match.groupValues[8]

            when {
                boldItalic.isNotEmpty() -> {
                    pushStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic,
                            color = if (isDarkTheme) TextPrimary else BaseFg
                        )
                    )
                    append(boldItalic)
                    pop()
                }
                bold1.isNotEmpty() || bold2.isNotEmpty() -> {
                    val content = if (bold1.isNotEmpty()) bold1 else bold2
                    pushStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = if (isDarkTheme) TextPrimary else BaseFg
                        )
                    )
                    append(content)
                    pop()
                }
                italic1.isNotEmpty() || italic2.isNotEmpty() -> {
                    val content = if (italic1.isNotEmpty()) italic1 else italic2
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(content)
                    pop()
                }
                code.isNotEmpty() -> {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.5.sp,
                            background = if (isDarkTheme) Color(0xFF262626) else Color(0xFFE8E5DD),
                            color = if (isDarkTheme) AccentGoldBg else PrimaryBg
                        )
                    )
                    append(" $code ")
                    pop()
                }
                strike.isNotEmpty() -> {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(strike)
                    pop()
                }
            }

            cursor = end
        }

        if (cursor < text.length) {
            append(text.substring(cursor))
        }

        if (appendCursor) {
            append(" ")
            appendInlineContent("lottie_cursor", "[loading]")
        }
    }
}

@Composable
private fun MarkdownFormattedText(
    text: String,
    isDarkTheme: Boolean,
    isStreaming: Boolean,
    isLastSegment: Boolean,
    lottieCursorInline: Map<String, InlineTextContent>
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEachIndexed { index, block ->
            val isLastBlock = isLastSegment && index == blocks.lastIndex
            val appendCursor = isStreaming && isLastBlock

            when (block) {
                is MarkdownLineBlock.Header -> {
                    val fontSize = when (block.level) {
                        1 -> 18.sp
                        2 -> 16.5.sp
                        else -> 15.5.sp
                    }
                    val weight = when (block.level) {
                        1 -> FontWeight.Bold
                        2 -> FontWeight.Bold
                        else -> FontWeight.SemiBold
                    }
                    val color = when (block.level) {
                        1 -> if (isDarkTheme) AccentGoldBg else PrimaryBg
                        else -> if (isDarkTheme) TextPrimary else BaseFg
                    }
                    val annotated = formatInlineMarkdown(block.content, isDarkTheme, appendCursor)
                    Text(
                        text = annotated,
                        inlineContent = if (appendCursor) lottieCursorInline else emptyMap(),
                        fontSize = fontSize,
                        fontWeight = weight,
                        color = color,
                        lineHeight = (fontSize.value + 6).sp,
                        modifier = Modifier.padding(top = if (index > 0) 4.dp else 0.dp)
                    )
                }
                is MarkdownLineBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 1.dp, bottom = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .size(5.dp)
                                .background(if (isDarkTheme) AccentGoldBg else PrimaryBg, CircleShape)
                        )
                        val annotated = formatInlineMarkdown(block.content, isDarkTheme, appendCursor)
                        Text(
                            text = annotated,
                            inlineContent = if (appendCursor) lottieCursorInline else emptyMap(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDarkTheme) TextPrimary else BaseFg,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                is MarkdownLineBlock.NumberedItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 1.dp, bottom = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "${block.number}.",
                            fontWeight = FontWeight.Bold,
                            color = if (isDarkTheme) AccentGoldBg else PrimaryBg,
                            fontSize = 14.5.sp,
                            lineHeight = 22.sp
                        )
                        val annotated = formatInlineMarkdown(block.content, isDarkTheme, appendCursor)
                        Text(
                            text = annotated,
                            inlineContent = if (appendCursor) lottieCursorInline else emptyMap(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDarkTheme) TextPrimary else BaseFg,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                is MarkdownLineBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isDarkTheme) Color(0xFF1E1D1A) else Color(0xFFF5F3EC))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(20.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(if (isDarkTheme) AccentGoldBg else PrimaryBg)
                        )
                        val annotated = formatInlineMarkdown(block.content, isDarkTheme, appendCursor)
                        Text(
                            text = annotated,
                            inlineContent = if (appendCursor) lottieCursorInline else emptyMap(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDarkTheme) TextSecondary else MutedFg,
                            fontStyle = FontStyle.Italic,
                            fontSize = 14.5.sp,
                            lineHeight = 21.sp,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                is MarkdownLineBlock.Paragraph -> {
                    if (block.content.isNotBlank() || appendCursor) {
                        val annotated = formatInlineMarkdown(block.content, isDarkTheme, appendCursor)
                        Text(
                            text = annotated,
                            inlineContent = if (appendCursor) lottieCursorInline else emptyMap(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDarkTheme) TextPrimary else BaseFg,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    }
                }
                is MarkdownLineBlock.EmptyLine -> {
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ── Code block ─────────────────────────────────────────────────────────────────

@Composable
private fun CodeBlockView(
    code        : String,
    language    : String,
    isDarkTheme : Boolean,
    onCopyCode  : () -> Unit
) {
    val codeBg   = if (isDarkTheme) Color(0xFF141414) else Color(0xFFF6F5F0)
    val headerBg = if (isDarkTheme) Color(0xFF1F1F1F) else Color(0xFFECEAE4)
    var copied   by remember { mutableStateOf(false) }
    val scope    = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (isDarkTheme) DarkBorder else TokenBorder, RoundedCornerShape(14.dp))
            .background(codeBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                language.ifBlank { "CODE" }.uppercase(),
                style      = MaterialTheme.typography.labelSmall,
                color      = if (isDarkTheme) TextSecondary else MutedFg,
                fontWeight = FontWeight.Bold,
                fontSize   = 11.sp
            )
            TextButton(
                onClick        = {
                    onCopyCode()
                    copied = true
                    scope.launch { delay(2000); copied = false }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    null,
                    tint     = if (copied) SuccessGreen else (if (isDarkTheme) AccentGoldBg else PrimaryBg),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (copied) "Copied!" else "Copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) SuccessGreen else (if (isDarkTheme) AccentGoldBg else PrimaryBg),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        SelectionContainer {
            Text(
                text       = code,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .horizontalScroll(rememberScrollState()),
                style      = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color      = if (isDarkTheme) TextPrimary else BaseFg,
                lineHeight = 19.sp,
                fontSize   = 13.sp
            )
        }
    }
}

// ── Action pill under AI bubble ────────────────────────────────────────────────

@Composable
private fun BubbleActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    isDarkTheme: Boolean
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (isDarkTheme) TextSecondary else MutedFg,
                modifier = Modifier.size(13.dp)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                color = if (isDarkTheme) TextSecondary else MutedFg
            )
        }
    }
}

// ── Long-press bottom sheet ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    isDarkTheme  : Boolean,
    onDismiss    : () -> Unit,
    onCopy       : () -> Unit,
    onSelectText : () -> Unit,
    onShare      : () -> Unit,
    onSave       : () -> Unit,
    onRegenerate : () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(),
        containerColor   = if (isDarkTheme) DarkSurfaceElevated else CardBg,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isDarkTheme) DarkBorder else TokenBorder)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(
                "Message Actions",
                style      = MaterialTheme.typography.titleMedium,
                color      = if (isDarkTheme) TextPrimary else BaseFg,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            )
            HorizontalDivider(color = if (isDarkTheme) DarkBorder else TokenBorder)
            Spacer(Modifier.height(4.dp))
            SheetAction(Icons.Default.ContentCopy, "Copy", "Copy full response", isDarkTheme, onCopy)
            SheetAction(Icons.Default.TextFields, "Select Text", "Select and copy specific text", isDarkTheme, onSelectText)
            SheetAction(Icons.Default.Share, "Share", "Send via WhatsApp, Gmail, etc.", isDarkTheme, onShare)
            SheetAction(Icons.Default.BookmarkAdd, "Save to Knowledge Vault", "Store for offline access", isDarkTheme, onSave)
            SheetAction(Icons.Default.Refresh, "Regenerate", "Generate a new response", isDarkTheme, onRegenerate)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserMessageActionsSheet(
    isDarkTheme  : Boolean,
    onDismiss    : () -> Unit,
    onCopy       : () -> Unit,
    onEdit       : () -> Unit,
    onSelectText : () -> Unit,
    onShare      : () -> Unit,
    onSave       : () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(),
        containerColor   = if (isDarkTheme) DarkSurfaceElevated else CardBg,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isDarkTheme) DarkBorder else TokenBorder)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(
                "Message Actions",
                style      = MaterialTheme.typography.titleMedium,
                color      = if (isDarkTheme) TextPrimary else BaseFg,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            )
            HorizontalDivider(color = if (isDarkTheme) DarkBorder else TokenBorder)
            Spacer(Modifier.height(4.dp))
            SheetAction(Icons.Default.ContentCopy, "Copy", "Copy prompt to clipboard", isDarkTheme, onCopy)
            SheetAction(Icons.Default.Edit, "Edit Prompt", "Load into input bar to edit or ask again", isDarkTheme, onEdit)
            SheetAction(Icons.Default.TextFields, "Select Text", "Select and copy specific text", isDarkTheme, onSelectText)
            SheetAction(Icons.Default.Share, "Share", "Send prompt via other apps", isDarkTheme, onShare)
            SheetAction(Icons.Default.BookmarkAdd, "Save to Knowledge Vault", "Store in Knowledge Vault", isDarkTheme, onSave)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSelectionBottomSheet(
    text: String,
    isDarkTheme: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var copiedAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = if (isDarkTheme) DarkSurfaceElevated else CardBg,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isDarkTheme) DarkBorder else TokenBorder)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Select Text",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isDarkTheme) TextPrimary else BaseFg,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Touch and drag handles to select text",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDarkTheme) TextSecondary else MutedFg
                    )
                }

                TextButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Copied Text", text))
                        copiedAll = true
                        scope.launch {
                            delay(1500)
                            copiedAll = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isDarkTheme) AccentGoldBg else PrimaryBg
                    )
                ) {
                    Icon(
                        if (copiedAll) Icons.Default.Check else Icons.Default.ContentCopy,
                        null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (copiedAll) "Copied" else "Copy All", fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider(color = if (isDarkTheme) DarkBorder else TokenBorder)
            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 380.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDarkTheme) Color(0xFF191919) else SecondaryBg)
                    .border(1.dp, if (isDarkTheme) DarkBorder else TokenBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDarkTheme) TextPrimary else BaseFg,
                        fontSize = 15.sp,
                        lineHeight = 23.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDarkTheme) AccentGoldBg else PrimaryBg,
                    contentColor = if (isDarkTheme) AccentGoldFg else PrimaryFg
                )
            ) {
                Text("Done", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon        : ImageVector,
    title       : String,
    subtitle    : String,
    isDarkTheme : Boolean,
    onClick     : () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier         = Modifier
                .size(40.dp)
                .background(
                    if (isDarkTheme) Color(0xFF262626) else SecondaryBg,
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                tint = if (isDarkTheme) AccentGoldBg else PrimaryBg,
                modifier = Modifier.size(18.dp)
            )
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDarkTheme) TextPrimary else BaseFg,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) TextSecondary else MutedFg
            )
        }
    }
}

// ── Message segment parser ─────────────────────────────────────────────────────

private sealed interface MessageSegment {
    data class PlainText(val text: String) : MessageSegment
    data class CodeBlock(val language: String, val code: String) : MessageSegment
}

private fun parseMessageSegments(text: String): List<MessageSegment> {
    val segments = mutableListOf<MessageSegment>()
    val regex    = Regex("```(\\w*)\\n?([\\s\\S]*?)```", RegexOption.MULTILINE)
    var cursor   = 0

    for (match in regex.findAll(text)) {
        if (match.range.first > cursor) {
            segments += MessageSegment.PlainText(text.substring(cursor, match.range.first))
        }
        segments += MessageSegment.CodeBlock(
            match.groupValues[1].trim(),
            match.groupValues[2].trimEnd('\n')
        )
        cursor = match.range.last + 1
    }

    if (cursor < text.length) segments += MessageSegment.PlainText(text.substring(cursor))
    return if (segments.isEmpty()) listOf(MessageSegment.PlainText(text)) else segments
}
