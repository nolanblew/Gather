package ninja.takeya.gather

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

internal fun List<DescriptionRun>.styledText(
    linkColor: Color,
    openLink: ((DescriptionLink) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    for (run in this@styledText) {
        val underline = run.underline || run.link != null
        val decoration = when {
            underline && run.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            underline -> TextDecoration.Underline
            run.strike -> TextDecoration.LineThrough
            else -> null
        }
        val start = length
        withStyle(SpanStyle(
            color = if (run.link == null) Color.Unspecified else linkColor,
            fontWeight = if (run.bold) FontWeight.Bold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            textDecoration = decoration,
        )) { append(run.text) }
        if (run.link != null && openLink != null && length > start) {
            addLink(LinkAnnotation.Clickable(run.link.url ?: run.link.listId.orEmpty()) { openLink(run.link) }, start, length)
        }
    }
}

internal fun List<DescriptionBlock>.numberedOrdinals(): List<Int> {
    var counter = 0
    return map { block ->
        counter = if (block.type == "numbered") counter + 1 else 0
        counter
    }
}

@Composable
internal fun DescriptionContent(
    blocks: List<DescriptionBlock>,
    openList: (String) -> Unit,
    report: (String) -> Unit,
    toggleCheckbox: ((Int, Boolean) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val ordinals = remember(blocks) { blocks.numberedOrdinals() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((index, block) in blocks.withIndex()) {
            if (block.type == "image") {
                if (block.display == "inline") DescriptionImage(block)
                continue
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (block.type) {
                    "bullet" -> Text("•", Modifier.width(20.dp))
                    "numbered" -> Text("${ordinals[index]}.", Modifier.widthIn(min = 22.dp).padding(end = 5.dp))
                    "checkbox" -> Checkbox(block.checked, { toggleCheckbox?.invoke(index, it) }, enabled = toggleCheckbox != null)
                }
                Text(block.runs.styledText(MaterialTheme.colorScheme.primary) { link ->
                    when (link.type) {
                        "list" -> link.listId?.let(openList)
                        "external" -> {
                            val url = externalDescriptionUrl(link.url.orEmpty())
                            if (url == null || runCatching { uriHandler.openUri(url) }.isFailure) report("This link could not be opened.")
                        }
                    }
                }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        blocks.filter { it.type == "image" && it.display == "attachment" }.forEach { DescriptionImage(it) }
    }
}

@Composable
internal fun DescriptionImage(
    block: DescriptionBlock,
    actions: (@Composable () -> Unit)? = null,
) {
    val dataUrl = block.dataUrl ?: return
    val bitmap by produceState<Bitmap?>(null, dataUrl) { value = decodeItemImage(dataUrl) }
    val picture = bitmap
    val label = block.alt.orEmpty().ifBlank { if (block.display == "attachment") "Image attachment" else "Description image" }
    var enlarged by remember(dataUrl) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    if (block.display == "attachment") {
        OutlinedCard(shape = shape) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clickable(enabled = picture != null) { enlarged = true }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (picture != null) Image(
                        picture.asImageBitmap(), null, Modifier.size(width = 52.dp, height = 36.dp).clip(shape),
                        contentScale = ContentScale.Crop,
                    ) else Icon(Icons.Outlined.Image, null, Modifier.size(24.dp))
                    Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                actions?.invoke()
            }
        }
    } else {
        Column {
            if (picture != null) Image(
                picture.asImageBitmap(), label,
                Modifier.fillMaxWidth().heightIn(max = 224.dp).clip(shape).clickable { enlarged = true },
                contentScale = ContentScale.Fit,
            ) else Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actions != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
        }
    }
    if (enlarged && picture != null) Dialog(onDismissRequest = { enlarged = false }) {
        Surface(shape = shape) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { enlarged = false }) { Icon(Icons.Outlined.Close, "Close image") }
                }
                Image(picture.asImageBitmap(), label, Modifier.fillMaxWidth().heightIn(max = 460.dp), contentScale = ContentScale.Fit)
            }
        }
    }
}
