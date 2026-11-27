package ninja.takeya.gather

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal const val DETAIL_SUMMARY_KEY = "detail-summary"

@Composable
internal fun ListDetailToolbar(
    list: GatherList,
    collapsed: Boolean,
    progress: State<Float>,
    itemViews: Boolean,
    searching: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onView: () -> Unit,
    options: @Composable () -> Unit,
) {
    val background by animateColorAsState(
        if (collapsed) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
        GatherMotion.quick(), label = "List toolbar color",
    )
    Surface(color = background, modifier = Modifier.fillMaxWidth().testTag("detail-toolbar")) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library") }
                Box(Modifier.weight(1f).padding(end = 4.dp), contentAlignment = Alignment.CenterStart) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = collapsed,
                        enter = fadeIn(GatherMotion.quick()),
                        exit = fadeOut(GatherMotion.quick()),
                        label = "Compact list title",
                        modifier = if (collapsed) Modifier else Modifier.clearAndSetSemantics {},
                    ) {
                        Text(
                            list.title,
                            modifier = Modifier.testTag("detail-compact-title").semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (itemViews) {
                    IconButton(onClick = onSearch) {
                        Icon(
                            if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                            if (searching) "Close item search" else "Search items",
                        )
                    }
                    TextButton(
                        onClick = onView,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("View", maxLines = 1)
                    }
                }
                options()
            }
            Box(Modifier.fillMaxWidth().height(3.dp)) {
                if (list.showsDetailProgress) androidx.compose.animation.AnimatedVisibility(
                    visible = collapsed,
                    enter = fadeIn(GatherMotion.quick()),
                    exit = fadeOut(GatherMotion.quick()),
                    label = "Compact list progress",
                    modifier = if (collapsed) Modifier else Modifier.clearAndSetSemantics {},
                ) {
                    LinearProgressIndicator(
                        progress = { progress.value },
                        modifier = Modifier.fillMaxWidth().height(3.dp).testTag("detail-compact-progress"),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        drawStopIndicator = {},
                    )
                }
            }
        }
    }
}

@Composable
internal fun ListDetailSearch(query: String, change: (String) -> Unit, requestFocus: Boolean) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    OutlinedTextField(
        query, { change(it.take(100)) },
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .focusRequester(focus).testTag("filter-items"),
        placeholder = { Text("Filter items") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { change("") }) {
                Icon(Icons.Outlined.Close, "Clear item search")
            }
        },
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        singleLine = true,
    )
}

@Composable
internal fun ListDetailComposer(
    text: String,
    change: (String) -> Unit,
    connected: Boolean,
    writable: Boolean,
    focus: FocusRequester,
    add: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            OutlinedTextField(
                text, { change(it.take(500)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focus).onPreviewKeyEvent { event ->
                        if (event.key != Key.Enter && event.key != Key.NumPadEnter) false
                        else {
                            if (event.type == KeyEventType.KeyUp) add()
                            true
                        }
                    }.testTag("quick-item-text"),
                placeholder = { Text("Add item") },
                leadingIcon = if (text.isBlank()) {{ Icon(Icons.Outlined.Add, null) }} else null,
                trailingIcon = {
                    IconButton(
                        onClick = add,
                        enabled = writable && text.isNotBlank(),
                        modifier = Modifier.testTag("quick-item-add"),
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Add item") }
                },
                shape = RoundedCornerShape(13.dp),
                singleLine = true,
                enabled = connected,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
        }
    }
}

private val GatherList.showsDetailProgress
    get() = kind == "checklist" && !isTemplate && items.isNotEmpty()

@Composable
internal fun ListDetailSummary(
    list: GatherList,
    progress: State<Float>,
    writable: Boolean,
    model: GatherViewModel,
    rail: Boolean = false,
) {
    val background = MaterialTheme.colorScheme.background
    Box(
        Modifier.fillMaxWidth().heightIn(min = if (rail) 212.dp else 228.dp).clipToBounds()
            .background(background).testTag("detail-summary"),
    ) {
        ListCoverArtwork(list, model, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to background.copy(alpha = .9f),
            .45f to background.copy(alpha = .62f),
            1f to Color.Transparent,
        )))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent,
            .34f to background.copy(alpha = .12f),
            .61f to background.copy(alpha = .74f),
            1f to background,
        )))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, top = 54.dp, end = 20.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(listIcon(list.icon), null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                list.title,
                style = if (rail) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (list.description.isNotBlank())
                ListDescription(list.description, if (rail) 2 else 3, MaterialTheme.typography.bodyMedium)
            if (list.shared || list.archived || list.repeatCadence != "manual") {
                val info = listOfNotNull(
                    "Archived".takeIf { list.archived },
                    (if (list.owner) "Shared by you" else "Shared by ${list.ownerUsername}").takeIf { list.shared },
                    "${list.repeatCadence.replaceFirstChar { it.uppercase() }} · Does not reset automatically."
                        .takeIf { list.repeatCadence != "manual" },
                ).joinToString(" · ")
                Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (list.showsDetailProgress) {
                Text("${list.checkedCount} of ${list.items.size} complete", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { progress.value },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
            }
            if (list.isTemplate) {
                Text("Start a separate private copy with unchecked items. People on this template won’t have access to the copy.",
                    style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                        model.action("list.clone", payload("listId" to list.id, "title" to "${list.title} — $date".take(200)), true)
                    },
                    enabled = writable,
                ) { Text("Start list") }
            }
        }
    }
}

internal data class DetailJump(val id: String, val label: String, val index: Int, val count: Int)

internal fun GatherList.detailJumps(
    entries: List<DetailEntry>,
    grouping: ItemGrouping,
    visibleItems: List<ListItem>,
): List<DetailJump> {
    val indexed = entries.withIndex()
    return if (grouping != ItemGrouping.None) {
        val headings = indexed.mapNotNull { (index, entry) ->
            (entry as? DetailEntry.GroupHeading)?.let { it.groupId to index }
        }.distinctBy { it.first }.toMap()
        viewGroups(visibleItems, grouping).mapNotNull { group ->
            headings[group.id]?.let { DetailJump(group.id, group.title.orEmpty(), it, group.rows.size) }
        }
    } else {
        val counts = visibleItems.groupingBy { it.sectionId }.eachCount()
        indexed.mapNotNull { (index, entry) ->
            val section = (entry as? DetailEntry.SectionHeading)?.section ?: return@mapNotNull null
            DetailJump(section.id, section.title, index, counts[section.id] ?: 0)
        }.filter { it.count > 0 }.distinctBy { it.id }
    }
}

@Composable
internal fun ListDetailInfoRail(
    list: GatherList,
    grouping: ItemGrouping,
    jumps: List<DetailJump>,
    jump: (Int) -> Unit,
    progress: State<Float>,
    writable: Boolean,
    model: GatherViewModel,
) {
    Column(Modifier.width(270.dp).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("detail-info-rail")) {
        ListDetailSummary(list, progress, writable, model, rail = true)
        if (jumps.size > 1) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                when (grouping) {
                    ItemGrouping.Place -> "Jump to place"
                    ItemGrouping.Person -> "Jump to person"
                    ItemGrouping.None -> "Jump to section"
                },
                Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            jumps.forEach { destination ->
                TextButton(onClick = { jump(destination.index) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(destination.label, Modifier.weight(1f))
                    Text(destination.count.toString())
                }
            }
        }
    }
}

@Composable
private fun ListDescription(description: String, lines: Int, style: TextStyle) {
    var expanded by remember(description) { mutableStateOf(false) }
    var truncated by remember(description, lines) { mutableStateOf(false) }
    Column {
        Text(
            description,
            style = style,
            maxLines = if (expanded) Int.MAX_VALUE else lines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
        )
        if (expanded || truncated)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show more")
            }
    }
}
