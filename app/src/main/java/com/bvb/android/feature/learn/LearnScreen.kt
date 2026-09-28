package com.bvb.android.feature.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LearnUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val blocks: List<MdBlock> = emptyList(),
)

/** A parsed markdown block, ready to render. */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Bullet(val text: String, val ordered: Boolean, val number: Int = 0) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Code(val text: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    object Divider : MdBlock()
}

@HiltViewModel
class LearnViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {
    val uiState = MutableStateFlow(LearnUiState())

    init {
        load()
    }

    fun load() {
        uiState.value = uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val markdown = withContext(Dispatchers.IO) {
                    api.getLearnDoc("user-guide").string()
                }
                val blocks = withContext(Dispatchers.Default) { parseMarkdown(markdown) }
                uiState.value = LearnUiState(loading = false, blocks = blocks)
            } catch (e: Exception) {
                uiState.value = LearnUiState(loading = false, error = ApiError.messageOf(e))
            }
        }
    }
}

/** Line-based markdown parser covering the constructs used by the user guide. */
internal fun parseMarkdown(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = md.lines()
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
        paragraph.clear()
    }

    var orderedCounter = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks.add(MdBlock.Code(code.toString().trimEnd()))
            }
            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(4)
                blocks.add(MdBlock.Heading(level, trimmed.dropWhile { it == '#' }.trim()))
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = StringBuilder(trimmed.removePrefix(">").trim())
                while (i + 1 < lines.size && lines[i + 1].trim().startsWith(">")) {
                    i++
                    quote.append(" ").append(lines[i].trim().removePrefix(">").trim())
                }
                // The web build strips screenshot placeholders; do the same here.
                if (!quote.contains("📸")) blocks.add(MdBlock.Quote(quote.toString()))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                orderedCounter = 0
                blocks.add(MdBlock.Bullet(trimmed.drop(2).trim(), ordered = false))
            }
            Regex("^\\d+\\. ").containsMatchIn(trimmed) -> {
                flushParagraph()
                orderedCounter++
                blocks.add(
                    MdBlock.Bullet(
                        trimmed.replace(Regex("^\\d+\\. "), "").trim(),
                        ordered = true,
                        number = orderedCounter,
                    )
                )
            }
            trimmed == "---" || trimmed == "***" -> {
                flushParagraph()
                blocks.add(MdBlock.Divider)
            }
            // Pipe table: | Term | Meaning | followed by |---|---| and rows.
            trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length > 1 -> {
                flushParagraph()
                val tableLines = mutableListOf(trimmed)
                while (i + 1 < lines.size && lines[i + 1].trim().let { it.startsWith("|") && it.endsWith("|") && it.length > 1 }) {
                    i++
                    tableLines.add(lines[i].trim())
                }
                fun cells(row: String) = row.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                val separator = Regex("^\\|[\\s:|-]+\\|$")
                val header = cells(tableLines.first())
                val rows = tableLines.drop(1)
                    .filterNot { separator.matches(it) }
                    .map { row ->
                        // Normalize to the header width so the grid stays aligned.
                        val c = cells(row)
                        List(header.size) { idx -> c.getOrElse(idx) { "" } }
                    }
                blocks.add(MdBlock.Table(header, rows))
            }
            trimmed.isEmpty() -> flushParagraph()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(" ")
                paragraph.append(trimmed)
            }
        }
        i++
    }
    flushParagraph()
    return blocks
}

/** Renders inline markdown (bold, italic, inline code, links as plain text). */
internal fun inlineMarkdown(text: String, codeBg: androidx.compose.ui.graphics.Color): AnnotatedString {
    // Convert [label](url) into "label" (the guide's links are informative).
    val noLinks = text.replace(Regex("\\[([^\\]]*)\\]\\(([^)]*)\\)"), "$1")
    return buildAnnotatedString {
        var rest = noLinks
        val pattern = Regex("(\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|`[^`]+`)")
        while (true) {
            val m = pattern.find(rest) ?: break
            append(rest.substring(0, m.range.first))
            val token = m.value
            when {
                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(token.removeSurrounding("**"))
                }
                token.startsWith("`") -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)
                ) {
                    append(token.removeSurrounding("`"))
                }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.removeSurrounding("*"))
                }
            }
            rest = rest.substring(m.range.last + 1)
        }
        append(rest)
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, codeBg: androidx.compose.ui.graphics.Color) {
    // With two columns (the common "Term | Meaning" glossary) the first
    // column gets a third of the width; otherwise columns share it equally.
    fun columnWeight(index: Int): Float =
        if (table.header.size == 2 && index == 0) 0.5f else 1f

    Column(
        Modifier
            .padding(vertical = 6.dp)
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(8.dp),
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            table.header.forEachIndexed { idx, cell ->
                Text(
                    inlineMarkdown(cell, codeBg),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(columnWeight(idx))
                        .padding(horizontal = 2.dp),
                )
            }
        }
        table.rows.forEach { row ->
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                row.forEachIndexed { idx, cell ->
                    Text(
                        inlineMarkdown(cell, codeBg),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(columnWeight(idx))
                            .padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun LearnScreen(viewModel: LearnViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    if (state.loading) {
        FullScreenLoading()
        return
    }

    state.error?.let {
        Column(Modifier.fillMaxSize().padding(16.dp)) { ErrorBanner(it) }
        return
    }

    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    SelectionContainer {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            items(state.blocks.size) { index ->
                when (val block = state.blocks[index]) {
                    is MdBlock.Heading -> {
                        Spacer(Modifier.height(if (block.level <= 2) 20.dp else 12.dp))
                        Text(
                            block.text,
                            style = when (block.level) {
                                1 -> MaterialTheme.typography.headlineMedium
                                2 -> MaterialTheme.typography.headlineSmall
                                3 -> MaterialTheme.typography.titleLarge
                                else -> MaterialTheme.typography.titleMedium
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    is MdBlock.Paragraph -> {
                        Text(
                            inlineMarkdown(block.text, codeBg),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    is MdBlock.Bullet -> {
                        Row(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
                            Text(
                                if (block.ordered) "${block.number}." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                inlineMarkdown(block.text, codeBg),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    is MdBlock.Quote -> {
                        Row(
                            Modifier
                                .padding(vertical = 6.dp)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                        ) {
                            Text(
                                inlineMarkdown(block.text, codeBg),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    is MdBlock.Code -> {
                        Text(
                            block.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(vertical = 6.dp)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                        )
                    }
                    is MdBlock.Table -> MarkdownTable(block, codeBg)
                    MdBlock.Divider -> {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
