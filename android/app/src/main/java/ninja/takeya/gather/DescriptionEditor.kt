@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ninja.takeya.gather

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val blockLabels = listOf(
    "paragraph" to "Paragraph",
    "bullet" to "Bulleted list",
    "numbered" to "Numbered list",
    "checkbox" to "Checkbox",
)

@Composable
internal fun DescriptionEditor(
    entries: List<DescriptionEntry>,
    change: (List<DescriptionEntry>) -> Unit,
    availableLists: List<GatherList>,
    enabled: Boolean,
    preparing: Boolean,
    onPreparing: (Boolean) -> Unit,
) {
    var activeId by remember { mutableStateOf<String?>(null) }
    var focusId by remember { mutableStateOf<String?>(null) }
    val selections = remember { mutableStateMapOf<String, TextRange>() }
    val compositions = remember { mutableStateMapOf<String, TextRange>() }
    var pendingStyle by remember { mutableStateOf<DescriptionRun?>(null) }
    var showListStyles by remember { mutableStateOf(false) }
    var linkEntry by remember { mutableStateOf<Pair<String, TextRange>?>(null) }
    var imageDisplay by remember { mutableStateOf("inline") }
    var descriptionError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestEntries by rememberUpdatedState(entries)
    val latestChange by rememberUpdatedState(change)
    val active = entries.firstOrNull { it.id == activeId && it.block.type != "image" }
        ?: entries.firstOrNull { it.block.type != "image" }
    val selection = active?.id?.let { selections[it] } ?: TextRange.Zero
    val activeRuns = active?.block?.runs.orEmpty()
    val textLength = entries.sumOf { it.block.text.length }
    val imageCount = entries.count { it.block.type == "image" }
    val canAddBlock = enabled && entries.size < MAX_DESCRIPTION_BLOCKS
    val canAddImage = canAddBlock && !preparing && imageCount < MAX_DESCRIPTION_IMAGES
    val fontColor = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val ordinals = remember(entries) { entries.map { it.block }.numberedOrdinals() }
    fun update(id: String, block: DescriptionBlock) {
        if (block.runs.size <= MAX_DESCRIPTION_RUNS) change(entries.map { if (it.id == id) it.copy(block = block) else it })
        else descriptionError = "This block has too many formatting changes. Add a new block to continue."
    }
    fun blockAtSelection(type: String): DescriptionEntry {
        if (active != null) {
            update(active.id, active.block.copy(type = type, checked = type == "checkbox" && active.block.checked))
            return active
        }
        val entry = DescriptionEntry(DescriptionBlock(type = type))
        change(entries + entry)
        return entry
    }
    fun makeActive(entry: DescriptionEntry) {
        if (activeId != entry.id) {
            activeId = entry.id
            pendingStyle = null
        }
    }
    fun addTextBlock(type: String) {
        val entry = DescriptionEntry(DescriptionBlock(type = type))
        val position = if (active == null) entries.size else entries.indexOf(active) + 1
        change(entries.toMutableList().apply { add(position, entry) })
        makeActive(entry)
        focusId = entry.id
    }
    fun marked(mark: DescriptionMark): Boolean = if (selection.collapsed) {
        mark.enabled(pendingStyle ?: activeRuns.styleAt(selection.start))
    } else activeRuns.within(selection).let { it.isNotEmpty() && it.all(mark::enabled) }
    fun mark(mark: DescriptionMark) {
        val entry = active ?: blockAtSelection("paragraph")
        makeActive(entry)
        val next = !marked(mark)
        if (selection.collapsed) pendingStyle = mark.set(pendingStyle ?: activeRuns.styleAt(selection.start), next)
        else update(entry.id, entry.block.copy(runs = activeRuns.styleRange(selection) { mark.set(it, next) }))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { result ->
        if (result != null) {
            val display = imageDisplay
            val insertAfter = active?.id
            descriptionError = null
            onPreparing(true)
            scope.launch {
                try {
                    val dataUrl = prepareItemImage(context.contentResolver, result)
                    val filename = if (display == "attachment") withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.query(result, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0)?.take(500) else null
                            }
                        }.getOrNull()
                    } else null
                    val entry = DescriptionEntry(DescriptionBlock(type = "image", dataUrl = dataUrl, alt = filename, display = display))
                    val current = latestEntries
                    val index = current.indexOfFirst { it.id == insertAfter }
                    if (current.size < MAX_DESCRIPTION_BLOCKS && current.count { it.block.type == "image" } < MAX_DESCRIPTION_IMAGES) {
                        latestChange(current.toMutableList().apply {
                            add(if (display == "inline" && index >= 0) index + 1 else size, entry)
                        })
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    descriptionError = "This image could not be added. Choose another image."
                } finally {
                    onPreparing(false)
                }
            }
        }
    }
    fun pickImage(display: String) {
        imageDisplay = display
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    fun option(entry: DescriptionEntry, replacement: DescriptionBlock?) {
        if (replacement == null) change(entries.filterNot { it.id == entry.id })
        else update(entry.id, replacement)
    }
    fun updateText(entry: DescriptionEntry, next: TextFieldValue, current: TextRange, composition: TextRange?) {
        val block = entry.block
        if (textLength - block.text.length + next.text.length > MAX_DESCRIPTION_TEXT) return
        makeActive(entry)
        if (next.text != block.text) {
            val edited = block.applyTextEdit(next.text, composition ?: current, pendingStyle, next.selection)
            val addedLines = block.type != "paragraph" && next.composition == null &&
                next.text.count { it == '\n' } > block.text.count { it == '\n' }
            if (addedLines) {
                val lines = edited.splitListLines()
                if (entries.size + lines.size - 1 <= MAX_DESCRIPTION_BLOCKS) {
                    val replacements = listOf(entry.copy(block = lines.first())) + lines.drop(1).map { DescriptionEntry(it) }
                    change(entries.flatMap { if (it.id == entry.id) replacements else listOf(it) })
                    val beforeCursor = next.text.take(next.selection.start)
                    val target = replacements[beforeCursor.count { it == '\n' }]
                    selections[target.id] = TextRange(beforeCursor.length - beforeCursor.lastIndexOf('\n') - 1)
                    compositions.remove(entry.id)
                    makeActive(target)
                    focusId = target.id
                    return
                }
            }
            update(entry.id, edited)
        } else if (next.selection != current) pendingStyle = null
        selections[entry.id] = next.selection
        val nextComposition = next.composition
        if (nextComposition == null) compositions.remove(entry.id)
        else compositions[entry.id] = nextComposition
    }

    Text("Description", style = MaterialTheme.typography.titleMedium)
    OutlinedCard(shape = RoundedCornerShape(14.dp)) {
        FlowRow(Modifier.fillMaxWidth()) {
            Row {
                FormatAction(Icons.Outlined.FormatBold, "Bold", active != null && enabled, marked(DescriptionMark.Bold)) { mark(DescriptionMark.Bold) }
                FormatAction(Icons.Outlined.FormatItalic, "Italic", active != null && enabled, marked(DescriptionMark.Italic)) { mark(DescriptionMark.Italic) }
                FormatAction(Icons.Outlined.FormatUnderlined, "Underline", active != null && enabled, marked(DescriptionMark.Underline)) { mark(DescriptionMark.Underline) }
                FormatAction(Icons.Outlined.FormatStrikethrough, "Strikethrough", active != null && enabled, marked(DescriptionMark.Strike)) { mark(DescriptionMark.Strike) }
            }
            Row {
                Box {
                    FormatAction(Icons.AutoMirrored.Outlined.FormatListBulleted, "List style", enabled && (active != null || canAddBlock)) { showListStyles = true }
                    DropdownMenu(showListStyles, { showListStyles = false }) {
                        blockLabels.forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                trailingIcon = if (active?.block?.type == type) {
                                    { Icon(Icons.Outlined.Check, null) }
                                } else null,
                                onClick = {
                                    showListStyles = false
                                    makeActive(blockAtSelection(type))
                                },
                            )
                        }
                    }
                }
                FormatAction(Icons.Outlined.Link, "Add or edit link", enabled && (active != null || canAddBlock)) {
                    val entry = active ?: blockAtSelection("paragraph")
                    linkEntry = entry.id to selection
                }
                FormatAction(Icons.Outlined.Image, "Insert inline image", canAddImage) { pickImage("inline") }
                FormatAction(Icons.Outlined.AttachFile, "Attach image", canAddImage) { pickImage("attachment") }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            entries.forEachIndexed { index, entry ->
                val block = entry.block
                if (block.type == "image" && block.display == "attachment") return@forEachIndexed
                key(entry.id) {
                    if (block.type == "image") {
                        DescriptionImage(block) {
                            DescriptionBlockOptions(entry, index, entries.size, enabled, change = { option(entry, it) }) { target ->
                                change(entries.toMutableList().apply { add(target, removeAt(index)) })
                            }
                        }
                    } else {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(focusId) {
                            if (focusId == entry.id) {
                                focus.requestFocus()
                                focusId = null
                            }
                        }
                        val current = (selections[entry.id] ?: TextRange.Zero).withinText(block.text.length)
                        val composition = compositions[entry.id]?.withinText(block.text.length)
                        Row(verticalAlignment = Alignment.Top) {
                            when (block.type) {
                                "bullet" -> Text("•", Modifier.width(18.dp).padding(top = 10.dp))
                                "numbered" -> Text("${ordinals[index]}.", Modifier.widthIn(min = 22.dp).padding(top = 10.dp, end = 4.dp))
                                "checkbox" -> Checkbox(block.checked, { update(entry.id, block.copy(checked = it)) }, enabled = enabled)
                            }
                            BasicTextField(
                                value = TextFieldValue(block.runs.styledText(primary), selection = current, composition = composition),
                                onValueChange = { updateText(entry, it, current, composition) },
                                enabled = enabled,
                                modifier = Modifier.weight(1f).heightIn(min = 42.dp).padding(vertical = 10.dp)
                                    .focusRequester(focus).onFocusChanged { if (it.isFocused) makeActive(entry) }
                                    .testTag(if (entry.id == entries.firstOrNull { it.block.type != "image" }?.id) "field-Description" else "description-block-$index")
                                    .semantics { contentDescription = if (index == 0) "Description" else "Description block ${index + 1}" },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = fontColor),
                                cursorBrush = SolidColor(primary),
                                decorationBox = { inner ->
                                    Box {
                                        if (block.text.isEmpty()) {
                                            Text("Write a description", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        inner()
                                    }
                                },
                            )
                            if (entries.size > 1 || block.type != "paragraph") {
                                DescriptionBlockOptions(entry, index, entries.size, enabled, change = { option(entry, it) }) { target ->
                                    change(entries.toMutableList().apply { add(target, removeAt(index)) })
                                }
                            }
                        }
                    }
                }
            }
            if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
            var adding by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { adding = true }, enabled = canAddBlock) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add block")
                }
                DropdownMenu(adding, { adding = false }) {
                    blockLabels.forEach { (type, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { adding = false; addTextBlock(type) })
                    }
                }
            }
        }
    }
    entries.forEachIndexed { index, entry ->
        if (entry.block.type == "image" && entry.block.display == "attachment") key(entry.id) {
            DescriptionImage(entry.block) {
                DescriptionBlockOptions(entry, index, entries.size, enabled, change = { option(entry, it) }) { target ->
                    change(entries.toMutableList().apply { add(target, removeAt(index)) })
                }
            }
        }
    }
    descriptionError?.let { DraftWarning(it) }
    if (textLength == MAX_DESCRIPTION_TEXT) DraftWarning("The description has reached its $MAX_DESCRIPTION_TEXT character limit.")
    if (imageCount == MAX_DESCRIPTION_IMAGES) Text("$MAX_DESCRIPTION_IMAGES images added", style = MaterialTheme.typography.bodySmall)
    linkEntry?.let { (id, range) ->
        val entry = entries.find { it.id == id }
        if (entry != null) {
            val runs = entry.block.runs
            val initial = if (range.collapsed) runs.styleAt(range.start).link else runs.within(range).firstOrNull()?.link
            DescriptionLinkDialog(availableLists, initial, { linkEntry = null }) { link, label ->
                val insertion = label.take(MAX_DESCRIPTION_TEXT - textLength)
                val newRuns = when {
                    !range.collapsed -> runs.styleRange(range) { it.copy(link = link) }
                    link == null -> runs.styleRange(runs.linkRangeAt(range.start)) { it.copy(link = null) }
                    initial != null -> runs.styleRange(runs.linkRangeAt(range.start)) { it.copy(link = link) }
                    else -> runs.replaceText(range, insertion, runs.styleAt(range.start).copy(link = link))
                }
                update(id, entry.block.copy(runs = newRuns))
                if (range.collapsed && initial == null && link != null) {
                    selections[id] = TextRange(range.start + insertion.length)
                }
                linkEntry = null
            }
        }
    }
}

private fun TextRange.withinText(length: Int) = TextRange(start.coerceIn(0, length), end.coerceIn(0, length))

private fun List<DescriptionRun>.linkRangeAt(offset: Int): TextRange {
    val link = styleAt(offset).link ?: return TextRange(offset)
    var cursor = 0
    var start = 0
    for (run in this) {
        val end = cursor + run.text.length
        if (run.link != link) {
            if (cursor >= offset) return TextRange(start, cursor)
            start = end
        } else if (end >= offset && offset < start) return TextRange(offset)
        cursor = end
    }
    return TextRange(start, cursor)
}

@Composable
private fun FormatAction(icon: ImageVector, label: String, enabled: Boolean, selected: Boolean? = null, click: () -> Unit) {
    if (selected == null) IconButton(onClick = click, enabled = enabled) {
        Icon(icon, label, Modifier.size(20.dp))
    } else IconToggleButton(
        checked = selected,
        onCheckedChange = { click() },
        enabled = enabled,
        colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer),
    ) { Icon(icon, label, Modifier.size(20.dp)) }
}

@Composable
private fun DescriptionBlockOptions(
    entry: DescriptionEntry,
    index: Int,
    count: Int,
    enabled: Boolean,
    change: (DescriptionBlock?) -> Unit,
    move: (Int) -> Unit,
) {
    val block = entry.block
    val image = block.type == "image"
    var menu by remember { mutableStateOf(false) }
    var editAlt by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, if (image) "Image options" else "Block options") }
        DropdownMenu(menu, { menu = false }) {
            if (image) {
                DropdownMenuItem(text = { Text("Edit alternative text") }, onClick = { menu = false; editAlt = true })
                DropdownMenuItem(text = { Text(if (block.display == "inline") "Show as attachment" else "Show inline") }, onClick = {
                    menu = false
                    change(block.copy(display = if (block.display == "inline") "attachment" else "inline"))
                })
            }
            if (index > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; move(index - 1) })
            if (index + 1 < count) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; move(index + 1) })
            DropdownMenuItem(text = { Text(if (image) "Remove image" else "Remove block") }, onClick = { menu = false; change(null) })
        }
    }
    if (editAlt) {
        var alt by remember { mutableStateOf(block.alt.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editAlt = false },
            title = { Text("Alternative text") },
            text = { FormText(alt, { alt = it }, "Image description", 500) },
            confirmButton = { TextButton(onClick = { change(block.copy(alt = alt.trim())); editAlt = false }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { editAlt = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DescriptionLinkDialog(
    lists: List<GatherList>,
    existing: DescriptionLink?,
    dismiss: () -> Unit,
    apply: (DescriptionLink?, String) -> Unit,
) {
    var type by remember { mutableStateOf(existing?.type ?: "external") }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var listId by remember { mutableStateOf(existing?.listId.orEmpty()) }
    var query by remember { mutableStateOf("") }
    val selected = lists.find { it.id == listId }
    val validUrl = externalDescriptionUrl(url)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Link") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Choice("Link to", type, listOf("external" to "Web address", "list" to "Gather list")) { type = it }
                if (type == "external") FormText(url, { url = it }, "Web address or mailto link", 2000)
                else {
                    FormText(query, { query = it }, "Search lists", 100)
                    val visible = lists.filter { it.title.contains(query.trim(), ignoreCase = true) }.sortedBy { it.title.lowercase() }
                    for (list in visible) SelectionRow(list.title, list.id == listId, single = true) { listId = list.id }
                    if (visible.isEmpty()) Text("No lists found.")
                }
                if (existing != null) TextButton(onClick = { apply(null, "") }) { Text("Remove link") }
            }
        },
        confirmButton = {
            TextButton(enabled = if (type == "list") selected != null else validUrl != null, onClick = {
                if (type == "list" && selected != null) apply(DescriptionLink("list", listId = listId), selected.title)
                if (type == "external" && validUrl != null) apply(DescriptionLink("external", url = validUrl), validUrl)
            }) { Text("Apply link") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
