package com.gone.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gone.ai.ui.theme.AccentGoldBg
import com.gone.ai.ui.theme.BaseFg
import com.gone.ai.ui.theme.DarkBorder
import com.gone.ai.ui.theme.MutedFg
import com.gone.ai.ui.theme.PrimaryBg
import com.gone.ai.ui.theme.SuccessGreen
import com.gone.ai.ui.theme.TextPrimary
import com.gone.ai.ui.theme.TextSecondary
import com.gone.ai.ui.theme.TokenBorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CURSOR_ID = "streaming_cursor"

/**
 * Model output drawn from [MarkdownBlocks]: headings, lists, quotes, code and emphasis.
 *
 * Shared by chat, the document tools and the Vault so every screen shows a reply the same
 * way. While [isStreaming], the animated cursor follows the last block.
 */
@Composable
fun MarkdownText(
    text: String,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    fontSize: TextUnit = 15.sp
) {
    val blocks = remember(text) { MarkdownBlocks.parse(text) }
    val context = LocalContext.current
    val bodyColor = if (isDarkTheme) TextPrimary else BaseFg
    val accent = if (isDarkTheme) AccentGoldBg else PrimaryBg
    val lineHeight = (fontSize.value * 1.47f).sp
    val cursor = remember {
        mapOf(
            CURSOR_ID to InlineTextContent(
                Placeholder(16.sp, 16.sp, PlaceholderVerticalAlign.Center)
            ) { LoadingLottieAnimation(modifier = Modifier.size(16.dp)) }
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEachIndexed { index, block ->
            val withCursor = isStreaming && index == blocks.lastIndex
            val inlineContent = if (withCursor) cursor else emptyMap()
            when (block) {
                is MarkdownBlock.Heading -> {
                    val size = when (block.level) { 1 -> fontSize * 1.2f; 2 -> fontSize * 1.1f; else -> fontSize * 1.03f }
                    Text(
                        text = styled(block.text, isDarkTheme, withCursor),
                        inlineContent = inlineContent,
                        fontSize = size,
                        fontWeight = if (block.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (block.level == 1) accent else bodyColor,
                        lineHeight = (size.value + 6).sp,
                        modifier = Modifier
                            .padding(top = if (index > 0) 4.dp else 0.dp)
                            .semantics { heading() }
                    )
                }
                is MarkdownBlock.Bullet -> ListRow(
                    marker = {
                        Box(
                            modifier = Modifier
                                .padding(top = (lineHeight.value / 2 - 2.5f).coerceAtLeast(0f).dp)
                                .size(5.dp)
                                .background(accent, CircleShape)
                        )
                    }
                ) {
                    Text(
                        styled(block.text, isDarkTheme, withCursor),
                        inlineContent = inlineContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bodyColor,
                        fontSize = fontSize,
                        lineHeight = lineHeight
                    )
                }
                is MarkdownBlock.Numbered -> ListRow(
                    marker = {
                        Text(
                            "${block.number}.",
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            fontSize = fontSize * 0.97f,
                            lineHeight = lineHeight
                        )
                    }
                ) {
                    Text(
                        styled(block.text, isDarkTheme, withCursor),
                        inlineContent = inlineContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bodyColor,
                        fontSize = fontSize,
                        lineHeight = lineHeight
                    )
                }
                is MarkdownBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDarkTheme) Color(0xFF1E1D1A) else Color(0xFFF5F3EC))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .heightIn(min = 20.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(accent)
                    )
                    Text(
                        styled(block.text, isDarkTheme, withCursor),
                        inlineContent = inlineContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDarkTheme) TextSecondary else MutedFg,
                        fontStyle = FontStyle.Italic,
                        fontSize = fontSize * 0.97f,
                        lineHeight = lineHeight
                    )
                }
                is MarkdownBlock.Paragraph -> if (block.text.isNotBlank() || withCursor) {
                    Text(
                        styled(block.text, isDarkTheme, withCursor),
                        inlineContent = inlineContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bodyColor,
                        fontSize = fontSize,
                        lineHeight = lineHeight
                    )
                }
                is MarkdownBlock.Code -> {
                    CodeBlock(
                        code = block.code,
                        language = block.language,
                        isDarkTheme = isDarkTheme,
                        onCopy = { TextActions.copy(context, "Code", block.code) }
                    )
                    if (withCursor) LoadingLottieAnimation(modifier = Modifier.size(18.dp).padding(top = 4.dp))
                }
                is MarkdownBlock.Table -> Column(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .border(1.dp, if (isDarkTheme) DarkBorder else TokenBorder, RoundedCornerShape(8.dp))
                ) {
                    (listOf(block.headers) + block.rows).forEachIndexed { rowIndex, row ->
                        Row {
                            row.forEach { cell ->
                                Text(
                                    styled(cell, isDarkTheme, false),
                                    modifier = Modifier.width(160.dp)
                                        .background(if (rowIndex == 0) accent.copy(alpha = 0.08f) else Color.Transparent)
                                        .padding(10.dp),
                                    color = bodyColor,
                                    fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = fontSize,
                                    lineHeight = lineHeight
                                )
                            }
                        }
                    }
                }
                MarkdownBlock.Gap -> Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ListRow(marker: @Composable () -> Unit, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 1.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        marker()
        Box(modifier = Modifier.weight(1f, fill = false)) { content() }
    }
}

private fun styled(text: String, isDarkTheme: Boolean, withCursor: Boolean): AnnotatedString =
    buildAnnotatedString {
        val strong = if (isDarkTheme) TextPrimary else BaseFg
        for (span in MarkdownBlocks.inline(text)) {
            val style = when (span.style) {
                InlineStyle.PLAIN -> null
                InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold, color = strong)
                InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                InlineStyle.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = strong)
                InlineStyle.CODE -> SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = if (isDarkTheme) Color(0xFF262626) else Color(0xFFE8E5DD),
                    color = if (isDarkTheme) AccentGoldBg else PrimaryBg
                )
                InlineStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            }
            if (style == null) {
                append(span.text)
            } else {
                pushStyle(style)
                append(if (span.style == InlineStyle.CODE) " ${span.text} " else span.text)
                pop()
            }
        }
        if (withCursor) {
            append(" ")
            appendInlineContent(CURSOR_ID, "…")
        }
    }

@Composable
private fun CodeBlock(code: String, language: String, isDarkTheme: Boolean, onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val accent = if (isDarkTheme) AccentGoldBg else PrimaryBg

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (isDarkTheme) DarkBorder else TokenBorder, RoundedCornerShape(14.dp))
            .background(if (isDarkTheme) Color(0xFF141414) else Color(0xFFF6F5F0))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isDarkTheme) Color(0xFF1F1F1F) else Color(0xFFECEAE4))
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                language.ifBlank { "code" }.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (isDarkTheme) TextSecondary else MutedFg,
                fontWeight = FontWeight.Bold
            )
            TextButton(
                onClick = {
                    onCopy()
                    copied = true
                    scope.launch { delay(2000); copied = false }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = if (copied) SuccessGreen else accent,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (copied) "Copied" else "Copy code",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) SuccessGreen else accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Text(
            text = code,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (isDarkTheme) TextPrimary else BaseFg,
            lineHeight = 19.sp,
            fontSize = 13.sp
        )
    }
}
