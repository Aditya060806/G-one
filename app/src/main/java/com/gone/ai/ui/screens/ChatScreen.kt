package com.gone.ai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gone.ai.ai.state.AIInferenceState
import com.gone.ai.data.library.EntryType
import com.gone.ai.data.library.LibraryRepository
import com.gone.ai.chat.ChatSuggestions
import com.gone.ai.model.ChatMessage
import com.gone.ai.model.ChatSession
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.gone.ai.R
import com.gone.ai.ui.components.BreadcrumbHeader
import com.gone.ai.ui.components.BreadcrumbItem
import com.gone.ai.ui.components.MarkdownBlocks
import com.gone.ai.ui.components.PureBreadcrumbText
import com.gone.ai.ui.components.HeaderActionPill
import com.gone.ai.ui.components.LoadingLottieAnimation
import com.gone.ai.ui.components.MarkdownText
import com.gone.ai.ui.components.TextActions
import com.gone.ai.ui.theme.*
import com.gone.ai.viewmodel.ChatViewModel
import com.gone.ai.voice.SpeakableText
import com.gone.ai.voice.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
    val attachment      by chatViewModel.attachment.collectAsState()
    val useHealthData   by chatViewModel.useHealthData.collectAsState()
    val suggestions     by chatViewModel.suggestions.collectAsState()
    var showDataSheet   by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { chatViewModel.refreshSuggestions() }
    val notice          by chatViewModel.notice.collectAsState()
    val listState       = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context         = LocalContext.current
    val scope           = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val dark            = isDarkTheme
    var textForSelection by remember { mutableStateOf<String?>(null) }
    var showAttachment by remember { mutableStateOf(false) }
    var showHistoryDrawer by remember { mutableStateOf(false) }

    // Read aloud: the voice is created on first use, and reading stops when chat closes.
    var speech by remember { mutableStateOf<SpeechOutput?>(null) }
    val speaking by (speech?.speaking ?: remember { MutableStateFlow(false) }).collectAsState()
    var readingId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(speaking) { if (!speaking) readingId = null }
    DisposableEffect(Unit) { onDispose { speech?.stop() } }
    fun readAloud(msg: ChatMessage) {
        val out = SpeechOutput.getInstance(context)
        val problem = out.unavailable.value
        if (problem != null) {
            scope.launch { snackbarHostState.showSnackbar(problem) }
            return
        }
        out.stop()
        SpeakableText().finish(msg.text).forEach(out::speak)
        speech = out
        readingId = msg.id
    }

    val isImeVisible = WindowInsets.isImeVisible
    val effectiveBottomPadding = if (isImeVisible) 0.dp else bottomPadding

    val isGenerating = aiState is AIInferenceState.Thinking ||
                       aiState is AIInferenceState.Responding

    // TalkBack: a streamed reply is announced once, when it is complete, not token by token.
    var replyAnnouncement by remember { mutableStateOf("") }
    var wasGenerating by remember { mutableStateOf(false) }
    LaunchedEffect(isGenerating) {
        if (wasGenerating && !isGenerating) {
            messages.lastOrNull { !it.isUser }?.text?.takeIf { it.isNotBlank() }?.let {
                replyAnnouncement = "G-one replied: " + MarkdownBlocks.plainText(it)
            }
        }
        wasGenerating = isGenerating
    }

    LaunchedEffect(notice) {
        val text = notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        chatViewModel.noticeShown()
    }

    LaunchedEffect(currentSessionId, messages.size) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun copyToClipboard(text: String, label: String = "AI Response") = TextActions.copy(context, label, text)

    fun shareText(prompt: String, response: String) {
        val shareBody = if (prompt.isNotBlank()) "Q: $prompt\n\nA: $response" else response
        TextActions.share(context, prompt.take(40).ifBlank { "G-one Chat" }, shareBody)
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
            Box(
                Modifier
                    .size(1.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = replyAnnouncement
                    }
            )
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
                    useHealthData  = useHealthData,
                    onOpenData     = { showDataSheet = true }
                )

                AnimatedVisibility(visible = isExtracting) {
                    ExtractionProgressBar(progress = extractProgress, isDarkTheme = dark)
                }

                attachment?.let { doc ->
                    AttachmentBar(title = doc.title, isDarkTheme = dark, onView = { showAttachment = true })
                }

                if (showSuggestions && messages.isEmpty()) {
                    EmptyState(
                        modifier          = Modifier.weight(1f),
                        isDarkTheme       = dark,
                        suggestions       = suggestions,
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

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state           = listState,
                        modifier        = Modifier.fillMaxSize(),
                        contentPadding  = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 64.dp),
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
                                        TextActions.share(context, "G-one prompt", msg.text)
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
                                },
                                isReading    = readingId == msg.id,
                                onReadAloud  = { readAloud(msg) },
                                onStopReading = { speech?.stop() }
                            )
                        }
                    }
                    val showLatest by remember { derivedStateOf { listState.canScrollForward } }
                    if (showLatest) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    listState.scrollToItem((messages.size - 1).coerceAtLeast(0))
                                    listState.scrollBy(Float.MAX_VALUE)
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (dark) AccentGoldBg else PrimaryBg,
                                contentColor = if (dark) AccentGoldFg else PrimaryFg
                            )
                        ) {
                            Icon(Icons.Default.ArrowDownward, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Latest")
                        }
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

                if (showDataSheet) {
                    HealthDataSheet(
                        enabled     = useHealthData,
                        isDarkTheme = dark,
                        onToggle    = { chatViewModel.setUseHealthData(it) },
                        loadPreview = { chatViewModel.healthContextPreview() },
                        onDismiss   = { showDataSheet = false }
                    )
                }

                val openAttachment = attachment
                if (showAttachment && openAttachment != null) {
                    AttachmentSheet(
                        title = openAttachment.title,
                        text = openAttachment.text,
                        isDarkTheme = dark,
                        onDismiss = { showAttachment = false }
                    )
                }

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatHeader(
    isDarkTheme: Boolean,
    onNavigateHome: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewChat: () -> Unit,
    useHealthData: Boolean,
    onOpenData: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PureBreadcrumbText(
            items = listOf(BreadcrumbItem("Home", onNavigateHome), BreadcrumbItem("Assist")),
            isDarkTheme = isDarkTheme
        )
        // Keep every action reachable, including on narrow screens and large font sizes.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HeaderActionPill(
                icon = if (useHealthData) Icons.Default.MonitorHeart else Icons.Default.HeartBroken,
                label = if (useHealthData) "My data" else "Data off",
                darkTheme = isDarkTheme, onClick = onOpenData
            )
            HeaderActionPill(Icons.AutoMirrored.Filled.Chat, "Chats", isDarkTheme, onClick = onOpenHistory)
            HeaderActionPill(Icons.Default.Add, "New", isDarkTheme, onClick = onNewChat)
        }
    }
}

@Composable
private fun AssistLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.gone_assist_logo),
        contentDescription = "G-one. Private. Local. Always.",
        contentScale = ContentScale.Fit,
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(BaseBg).padding(8.dp)
    )
}

// ── Personal data sheet ────────────────────────────────────────────────────────

/**
 * The switch for personal data in chat, and the exact facts the model receives when it is on,
 * so the person can see what "use my data" means rather than take it on trust.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthDataSheet(
    enabled: Boolean,
    isDarkTheme: Boolean,
    onToggle: (Boolean) -> Unit,
    loadPreview: suspend () -> String?,
    onDismiss: () -> Unit
) {
    var preview by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        preview = null
        if (enabled) {
            loading = true
            preview = runCatching { loadPreview() }.getOrNull()
            loading = false
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (isDarkTheme) DarkSurfaceElevated else CardBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Use my health data",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isDarkTheme) TextPrimary else BaseFg,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Latest reading, recent session reports and alerts, and records you shared from the Vault.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDarkTheme) TextSecondary else MutedFg
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (enabled) "WHAT G-ONE CAN SEE" else "G-ONE ANSWERS WITHOUT YOUR DATA",
                style = MaterialTheme.typography.labelSmall,
                color = if (isDarkTheme) TextSecondary else MutedFg,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            when {
                !enabled -> Text(
                    "Nothing about you is added to your questions. Everything stays on this phone either way.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDarkTheme) TextPrimary else BaseFg
                )
                loading -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isDarkTheme) Color(0xFF191919) else SecondaryBg)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    SelectionContainer {
                        Text(
                            preview ?: "The facts could not be read right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDarkTheme) TextPrimary else BaseFg,
                            lineHeight = 19.sp
                        )
                    }
                }
            }
        }
    }
}

// ── Attached document ──────────────────────────────────────────────────────────

/** Shows which document this conversation is about; tapping opens it. */
@Composable
private fun AttachmentBar(title: String, isDarkTheme: Boolean, onView: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isDarkTheme) ModernCardDark else SecondaryBg)
            .border(1.dp, if (isDarkTheme) DarkBorder else TokenBorder, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = "View the document", onClick = onView)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Default.Description, contentDescription = null,
            tint = if (isDarkTheme) AccentGoldBg else PrimaryBg,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "About this document",
                style = MaterialTheme.typography.labelSmall,
                color = if (isDarkTheme) TextSecondary else MutedFg
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDarkTheme) TextPrimary else BaseFg,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "View",
            style = MaterialTheme.typography.labelMedium,
            color = if (isDarkTheme) AccentGoldBg else PrimaryBg,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(title: String, text: String, isDarkTheme: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (isDarkTheme) DarkSurfaceElevated else CardBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isDarkTheme) TextPrimary else BaseFg,
                fontWeight = FontWeight.Bold
            )
            Text(
                "G-one answers from this text in this conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) TextSecondary else MutedFg
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    MarkdownText(text = text, isDarkTheme = isDarkTheme)
                }
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
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Search chats" },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = if (isDarkTheme) TextPrimary else BaseFg,
                                fontSize = 14.sp
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(if (isDarkTheme) AccentGoldBg else PrimaryBg),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search chats…",
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
                                .clickable(role = Role.Button) {
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
    suggestions: List<ChatSuggestions.Suggestion>,
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
            AssistLogo(Modifier.size(132.dp))
        }
        Spacer(Modifier.height(18.dp))

        Text(
            "Your health, in context.",
            style = MaterialTheme.typography.headlineSmall,
            color = if (isDarkTheme) TextPrimary else BaseFg,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Ask health questions, understand a report, or talk through your own readings.",
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
                "On-device answers · Private by design",
                style = MaterialTheme.typography.labelSmall,
                color = if (isDarkTheme) TextSecondary else MutedFg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "START A CONVERSATION",
            style = MaterialTheme.typography.labelSmall,
            color = if (isDarkTheme) TextSecondary else MutedFg,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            suggestions.forEach { suggestion ->
                ModernSuggestionCard(
                    icon        = when (suggestion.kind) {
                        ChatSuggestions.Kind.SESSION -> Icons.Default.Timeline
                        ChatSuggestions.Kind.ALERT   -> Icons.Default.NotificationImportant
                        ChatSuggestions.Kind.RECORD  -> Icons.Default.MedicalInformation
                        ChatSuggestions.Kind.HEART   -> Icons.Default.Favorite
                        ChatSuggestions.Kind.LAB     -> Icons.Default.Science
                        ChatSuggestions.Kind.GENERAL -> Icons.AutoMirrored.Filled.Chat
                    },
                    title       = suggestion.title,
                    subtitle    = suggestion.subtitle,
                    isDarkTheme = isDarkTheme,
                    onClick     = { onSuggestionClick(suggestion.title) }
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
            .clickable(role = Role.Button, interactionSource = src, indication = null, onClick = onClick)
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

    Column(
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
                    modifier      = Modifier.weight(1f).semantics { contentDescription = "Message G-one" },
                    enabled       = true,
                    textStyle     = MaterialTheme.typography.bodyMedium.copy(
                        color = if (isDarkTheme) TextPrimary else BaseFg,
                        fontSize = 15.sp,
                        lineHeight = 21.sp
                    ),
                    cursorBrush   = SolidColor(if (isDarkTheme) AccentGoldBg else PrimaryBg),
                    decorationBox = { innerTextField ->
                        if (input.isEmpty()) {
                            Text(
                                "Message G-one…",
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
                isGenerating -> if (isDarkTheme) Color(0xFF382020) else Color(0xFFFFECEA)
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
                    .size(52.dp)
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
                    .clickable(role = Role.Button) {
                        when {
                            isGenerating -> onStop()
                            canSend      -> onSend()
                            else         -> onVoice()
                        }
                    }
                    .semantics {
                        contentDescription = when {
                            isGenerating -> "Stop the reply"
                            canSend      -> "Send"
                            else         -> "Voice chat"
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isGenerating) {
                    LoadingLottieAnimation(
                        modifier = Modifier.size(40.dp),
                        tint = if (isDarkTheme) Color(0xFFFF7770) else DestructiveBg
                    )
                } else {
                    Icon(
                        imageVector = if (canSend) Icons.Default.ArrowUpward else Icons.Default.Mic,
                        contentDescription = null,
                        tint = actionFg,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ── Chat bubble ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    onSelectText: (String) -> Unit,
    isReading   : Boolean,
    onReadAloud : () -> Unit,
    onStopReading: () -> Unit
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
                    Image(
                        painterResource(R.drawable.gone_assist_logo), null,
                        modifier = Modifier.fillMaxSize().background(BaseBg),
                        contentScale = ContentScale.Fit
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
                    .widthIn(max = 420.dp).fillMaxWidth(0.88f)
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
                modifier = Modifier.fillMaxWidth(),
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
                    FlowRow(
                        modifier              = Modifier.padding(top = 4.dp, start = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement   = Arrangement.spacedBy(2.dp)
                    ) {
                        BubbleActionPill(Icons.Default.ContentCopy, "Copy", onCopy, isDarkTheme)
                        if (isReading) {
                            BubbleActionPill(Icons.Default.StopCircle, "Stop reading", onStopReading, isDarkTheme)
                        } else {
                            BubbleActionPill(Icons.AutoMirrored.Filled.VolumeUp, "Read aloud", onReadAloud, isDarkTheme)
                        }
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
            onRegenerate = { showAiSheet = false; onRegenerate() },
            isReading    = isReading,
            onReadAloud  = { showAiSheet = false; if (isReading) onStopReading() else onReadAloud() }
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

    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        if (isLoading) {
            Text(
                "Thinking through your question…",
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) TextSecondary else MutedFg,
                modifier = Modifier.padding(6.dp)
            )
        } else {
            MarkdownText(text = message.text, isDarkTheme = isDarkTheme, isStreaming = false, fontSize = 16.sp)
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
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
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
    onRegenerate : () -> Unit,
    isReading    : Boolean,
    onReadAloud  : () -> Unit
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
            if (isReading) {
                SheetAction(Icons.Default.StopCircle, "Stop reading", "Stop reading this reply aloud", isDarkTheme, onReadAloud)
            } else {
                SheetAction(Icons.AutoMirrored.Filled.VolumeUp, "Read aloud", "Hear this reply in your phone's voice", isDarkTheme, onReadAloud)
            }
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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
            .clickable(role = Role.Button, onClick = onClick)
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
