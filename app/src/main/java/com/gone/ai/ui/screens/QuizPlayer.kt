package com.gone.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gone.ai.quiz.QuizQuestion
import com.gone.ai.ui.theme.ErrorRed
import com.gone.ai.ui.theme.ModernBorderDark
import com.gone.ai.ui.theme.ModernBorderLight
import com.gone.ai.ui.theme.ModernCardDark
import com.gone.ai.ui.theme.ModernCardLight
import com.gone.ai.ui.theme.SuccessGreen
import com.gone.ai.ui.theme.TextPrimary
import com.gone.ai.ui.theme.TextPrimaryLight
import com.gone.ai.ui.theme.TextSecondary
import com.gone.ai.ui.theme.TextSecondaryLight

/**
 * A parsed quiz, answered one tap per question: the choice is marked right or wrong and the
 * explanation appears. Used by the Quiz tool and when a saved quiz is opened from the Vault.
 */
@Composable
fun QuizPlayer(
    questions: List<QuizQuestion>,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {}
) {
    var picks by rememberSaveable(questions) { mutableStateOf(IntArray(questions.size) { -1 }) }
    val answered = picks.count { it >= 0 }
    val correct = questions.indices.count { picks[it] == questions[it].correctIndex }
    val primary = if (isDarkTheme) TextPrimary else TextPrimaryLight
    val secondary = if (isDarkTheme) TextSecondary else TextSecondaryLight

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (answered == questions.size) "Score: $correct of ${questions.size}"
                    else "$answered of ${questions.size} answered · $correct correct",
                    style = MaterialTheme.typography.titleSmall,
                    color = primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                if (answered > 0) {
                    TextButton(onClick = { picks = IntArray(questions.size) { -1 } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Try again")
                    }
                }
            }
        }
        itemsIndexed(questions) { index, question ->
            val pick = picks[index]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isDarkTheme) ModernCardDark else ModernCardLight)
                    .border(1.dp, if (isDarkTheme) ModernBorderDark else ModernBorderLight, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Question ${index + 1} of ${questions.size}", style = MaterialTheme.typography.labelSmall, color = secondary)
                Text(question.question, style = MaterialTheme.typography.bodyLarge, color = primary, fontWeight = FontWeight.SemiBold)
                question.options.forEachIndexed { optionIndex, option ->
                    val isCorrect = optionIndex == question.correctIndex
                    val revealed = pick >= 0
                    val state = when {
                        !revealed -> null
                        isCorrect -> "Correct answer"
                        optionIndex == pick -> "Your answer, not correct"
                        else -> null
                    }
                    val accent = when {
                        !revealed -> null
                        isCorrect -> SuccessGreen
                        optionIndex == pick -> ErrorRed
                        else -> null
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(accent?.copy(alpha = 0.14f) ?: Color.Transparent)
                            .border(
                                1.dp,
                                accent ?: (if (isDarkTheme) ModernBorderDark else ModernBorderLight),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable(enabled = !revealed, role = Role.Button) {
                                picks = picks.copyOf().also { it[index] = optionIndex }
                            }
                            .semantics { state?.let { stateDescription = it } }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("${'A' + optionIndex}", color = accent ?: secondary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(option, color = primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (accent != null) {
                            Icon(
                                if (isCorrect) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (pick >= 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (pick == question.correctIndex) "Correct." else "The answer is ${'A' + question.correctIndex}.",
                        color = if (pick == question.correctIndex) SuccessGreen else ErrorRed,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    question.explanation?.let {
                        Text(it, color = secondary, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                    }
                }
            }
        }
        item { footer() }
    }
}
