package ninja.takeya.gather

import androidx.compose.ui.text.TextRange
import java.net.URI
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val MAX_DESCRIPTION_BLOCKS = 100
internal const val MAX_DESCRIPTION_RUNS = 500
internal const val MAX_DESCRIPTION_TEXT = 16000
internal const val MAX_DESCRIPTION_IMAGES = 8
internal const val MAX_SUBITEMS = 100

internal data class DescriptionEntry(
    val block: DescriptionBlock = DescriptionBlock(),
    val id: String = UUID.randomUUID().toString(),
)

internal fun ListItem?.descriptionDraft(): List<DescriptionEntry> = when {
    this == null -> emptyList()
    description.isNotEmpty() -> description.map { DescriptionEntry(it) }
    note.isNotEmpty() -> listOf(DescriptionEntry(DescriptionBlock(runs = listOf(DescriptionRun(note)))))
    else -> emptyList()
}

internal data class DescriptionWrite(val note: String, val blocks: List<DescriptionBlock>)

internal fun List<DescriptionEntry>.descriptionWrite(): DescriptionWrite {
    val blocks = map { it.block }.dropLastWhile { it.type != "image" && !it.checked && it.text.isBlank() }
    val plain = blocks.singleOrNull()?.takeIf { block ->
        block.type == "paragraph" && block.text.length <= 4000 &&
            block.runs.all { it == DescriptionRun(it.text) }
    }
    return if (plain == null) DescriptionWrite("", blocks) else DescriptionWrite(plain.text, emptyList())
}

internal fun DescriptionBlock.toPayload() = when (type) {
    "image" -> payload("type" to type, "dataUrl" to dataUrl, "display" to display).let {
        if (alt.isNullOrEmpty()) it else JsonObject(it + ("alt" to JsonPrimitive(alt)))
    }
    "checkbox" -> payload("type" to type, "runs" to runs.map(DescriptionRun::toPayload), "checked" to checked)
    else -> payload("type" to type, "runs" to runs.map(DescriptionRun::toPayload))
}

private fun DescriptionRun.toPayload() = payload(*buildList {
    add("text" to text)
    if (bold) add("bold" to true)
    if (italic) add("italic" to true)
    if (underline) add("underline" to true)
    if (strike) add("strike" to true)
    link?.let {
        add("link" to if (it.type == "list") payload("type" to it.type, "listId" to it.listId)
        else payload("type" to it.type, "url" to it.url))
    }
}.toTypedArray())

internal fun Subitem.toPayload() = payload("id" to id, "text" to text, "checked" to checked)

internal fun externalDescriptionUrl(value: String): String? = runCatching {
    val url = value.trim()
    if (url.length > 2000 || url.any { it <= ' ' || it == '\u007f' }) return null
    val uri = URI(url)
    when (uri.scheme?.lowercase()) {
        "https", "http" -> url.takeIf { !uri.rawAuthority.isNullOrBlank() }
        "mailto" -> url.takeIf { !uri.rawSchemeSpecificPart.isNullOrBlank() }
        else -> null
    }
}.getOrNull()

internal enum class DescriptionMark {
    Bold, Italic, Underline, Strike;

    fun enabled(run: DescriptionRun) = when (this) {
        Bold -> run.bold
        Italic -> run.italic
        Underline -> run.underline
        Strike -> run.strike
    }

    fun set(run: DescriptionRun, enabled: Boolean) = when (this) {
        Bold -> run.copy(bold = enabled)
        Italic -> run.copy(italic = enabled)
        Underline -> run.copy(underline = enabled)
        Strike -> run.copy(strike = enabled)
    }
}

internal fun List<DescriptionRun>.styleAt(offset: Int): DescriptionRun {
    var end = 0
    for (run in this) {
        end += run.text.length
        if (end >= offset) return run.copy(text = "")
    }
    return DescriptionRun("")
}

private fun List<DescriptionRun>.slice(start: Int, end: Int): List<DescriptionRun> {
    var offset = 0
    return mapNotNull { run ->
        val localStart = (start - offset).coerceIn(0, run.text.length)
        val localEnd = (end - offset).coerceIn(0, run.text.length)
        offset += run.text.length
        if (localStart < localEnd) run.copy(text = run.text.substring(localStart, localEnd)) else null
    }
}

internal fun List<DescriptionRun>.within(selection: TextRange): List<DescriptionRun> = slice(selection.min, selection.max)

private fun List<DescriptionRun>.merged(): List<DescriptionRun> = buildList {
    for (run in this@merged) {
        if (run.text.isEmpty()) continue
        val previous = lastOrNull()
        if (previous != null && previous.copy(text = "") == run.copy(text = "")) {
            removeAt(lastIndex)
            add(previous.copy(text = previous.text + run.text))
        } else add(run)
    }
}

internal fun List<DescriptionRun>.styleRange(
    selection: TextRange,
    change: (DescriptionRun) -> DescriptionRun,
): List<DescriptionRun> = (
    slice(0, selection.min) + within(selection).map(change) + slice(selection.max, Int.MAX_VALUE)
).merged()

internal fun List<DescriptionRun>.replaceText(
    selection: TextRange,
    replacement: String,
    style: DescriptionRun = styleAt(selection.min),
): List<DescriptionRun> = (
    slice(0, selection.min) + style.copy(text = replacement) + slice(selection.max, Int.MAX_VALUE)
).merged()

internal fun DescriptionBlock.applyTextEdit(
    newText: String,
    oldSelection: TextRange,
    pending: DescriptionRun?,
    newSelection: TextRange = oldSelection,
): DescriptionBlock {
    val previous = text
    if (previous == newText) return this
    fun matchesReplacement(range: TextRange): Boolean {
        val suffix = previous.length - range.max
        val end = newText.length - suffix
        return end >= range.min && newText.regionMatches(0, previous, 0, range.min) &&
            newText.regionMatches(end, previous, range.max, suffix)
    }
    val deleted = previous.length - newText.length
    val deletion = TextRange(newSelection.min, newSelection.min + deleted.coerceAtLeast(0))
    val removed = when {
        matchesReplacement(oldSelection) -> oldSelection
        deleted > 0 && oldSelection.collapsed && matchesReplacement(deletion) -> deletion
        else -> {
            val first = previous.commonPrefixWith(newText).length
            val suffix = previous.substring(first).commonSuffixWith(newText.substring(first)).length
            TextRange(first, previous.length - suffix)
        }
    }
    val inserted = newText.substring(removed.min, newText.length - (previous.length - removed.max))
    return copy(runs = runs.replaceText(removed, inserted, pending ?: runs.styleAt(removed.min)))
}

internal fun DescriptionBlock.splitListLines(): List<DescriptionBlock> {
    var start = 0
    return text.split('\n').mapIndexed { index, line ->
        val end = start + line.length
        copy(runs = runs.slice(start, end), checked = index == 0 && checked).also { start = end + 1 }
    }
}
