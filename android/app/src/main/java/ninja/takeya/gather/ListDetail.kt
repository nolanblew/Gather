package ninja.takeya.gather

import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

@Composable
internal fun DetailScreen(
    list: GatherList,
    state: GatherState,
    writable: Boolean,
    model: GatherViewModel,
    edit: (Editor) -> Unit,
    confirm: (Confirmation) -> Unit,
    chooseCover: () -> Unit,
) {
    var lastPlaces by remember(list.id) { mutableStateOf(list.places) }
    var lastRecipients by remember(list.id) { mutableStateOf(list.recipients) }
    val ui = model.ui
    val filter = ui.filters[list.id] ?: ListFilter()
    val showDetails = ui.hiddenDetails[list.id] != true
    val hiddenSaved = ui.hiddenSavedItems[list.id]
    val capture = ui.itemCapture[list.id].orEmpty()
    val organization = state.workspace.version >= 3
    val itemViews = state.workspace.version >= 4
    val selected = ui.itemSelections[list.id]
    val reordering = ui.reorderListId == list.id
    val showingCapture = !reordering && selected == null
    val query = ui.itemQueries[list.id].orEmpty()
    val searching = itemViews && showingCapture && (ui.itemSearchListId == list.id || query.isNotBlank())
    val narrowed = filter.active || query.isNotBlank()
    val sort = if (reordering || !itemViews) ItemSort.Manual else model.itemSort(list)
    val grouping = ui.itemGrouping[list.id] ?: ItemGrouping.None
    val canReorderGroups = remember(list, grouping) {
        grouping != ItemGrouping.None &&
            (list.storedGroupOrder(grouping).isNotEmpty() || list.realizedGroupLabels(grouping).size > 1)
    }
    val canFilter =
        filter.active || list.usesPlaces || list.usesRecipients ||
            (list.kind == "checklist" && list.checkedCount > 0)
    val scroll = ui.listScroll("detail:${list.id}")
    val canonical = remember(list) { list.canonicalItems() }
    val ordinals = remember(list.numbered, canonical) {
        if (list.numbered) canonical.mapIndexed { index, item -> item.id to index + 1 }.toMap()
        else emptyMap()
    }
    val visibleItems = remember(list, sort, filter, query) { list.viewItems(sort, filter, query) }
    val entries = remember(list, visibleItems, grouping, narrowed, itemViews) {
        list.detailEntries(visibleItems, grouping, narrowed, itemViews && list.separatesCompletedItems)
    }
    val reorderRows = remember(entries) { entries.filterIsInstance<DetailEntry.Row>().associateBy { it.key } }
    val drag = remember(list.id, scroll) { ReorderDragState<DetailEntry.Row>(scroll) }
    val dragTarget by remember(drag) { derivedStateOf { drag.targetKey() } }
    val focusManager = LocalFocusManager.current
    val openFilter = { draft: ListFilter -> edit(Editor.Filters(list, draft)) }
    fun clearFilters() {
        ui.filters.remove(list.id)
        ui.itemQueries.remove(list.id)
        ui.hiddenSavedItems.remove(list.id)
    }
    fun startReorder() {
        if (ui.itemSearchListId == list.id) ui.itemSearchListId = null
        focusManager.clearFocus()
        ui.itemSelections.remove(list.id)
        ui.reorderListId = list.id
    }
    fun finishMode() {
        ui.itemSelections.remove(list.id)
        if (reordering) ui.reorderListId = null
    }
    fun moveItem(from: DetailEntry.Row, destinationId: String, token: String?) {
        if (!reordering || !writable || token == null) return
        val ids = movedItemIds(from.bucket.itemIds, from.row.item.id, destinationId) ?: return
        model.action(
            "item.reorderVisible",
            payload("listId" to list.id, "itemIds" to ids, "expectedOrderToken" to token),
            onSuccess = { model.setItemSort(list.id, ItemSort.Manual) },
        )
    }
    LaunchedEffect(list.items, selected) {
        if (selected != null) {
            val current = list.items.filter { it.id in selected }.mapTo(mutableSetOf()) { it.id }
            if (current != selected) ui.itemSelections[list.id] = current
        }
    }
    LaunchedEffect(list.places, list.recipients, list.kind, filter) {
        val removedPlace = filter.place?.takeIf { list.placeFilterLabel(it) == null }
        val removedPerson = filter.person?.takeIf { id ->
            id != FILTER_UNSET && list.recipients.none { it.id == id }
        }
        val reconciled = filter.copy(
            place = filter.place.takeIf { removedPlace == null },
            person = filter.person.takeIf { removedPerson == null },
            notDone = filter.notDone && list.kind == "checklist",
        )
        if (reconciled != filter) {
            if (reconciled.active) ui.filters[list.id] = reconciled else ui.filters.remove(list.id)
        }
        removedPlace?.let { id ->
            val label = lastPlaces.find { it.id == id }?.label ?: "The place"
            model.report("$label was removed. The filter was cleared.")
        }
        removedPerson?.let { id ->
            val label = lastRecipients.find { it.id == id }?.name ?: "The person"
            model.report("$label was removed. The filter was cleared.")
        }
        lastPlaces = list.places
        lastRecipients = list.recipients
    }
    val captureFocus = remember(list.id) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val addItem = {
        if (capture.isNotBlank() && writable) {
            captureFocus.requestFocus()
            keyboard?.show()
            val fields = mutableListOf<Pair<String, Any?>>("text" to capture.trim())
            val availability = filter.draftAvailability()
            if (availability.mode != "unassigned") fields += "availability" to availability.toPayload()
            filter.person?.takeUnless { it == FILTER_UNSET }?.let { fields += "recipientId" to it }
            model.action("item.create", list.orderedPayload(*fields.toTypedArray()), onSuccess = {
                ui.itemCapture.remove(list.id)
                model.showSavedItem(list.id, null)
            })
        }
    }
    val progress =
        animateFloatAsState(
            if (list.items.isEmpty()) 0f else list.checkedCount.toFloat() / list.items.size,
            animationSpec = GatherMotion.standard(),
            label = "Checklist progress",
        )

    val scope = rememberCoroutineScope()
    val grouped = itemViews && grouping != ItemGrouping.None
    val empty = visibleItems.isEmpty() && (narrowed || list.items.isEmpty())
    val revealItem = ui.itemToReveal[list.id]

    fun closeSearch() {
        if (ui.itemSearchListId == list.id) ui.itemSearchListId = null
        ui.itemQueries.remove(list.id)
        focusManager.clearFocus()
        keyboard?.hide()
    }

    BoxWithConstraints(Modifier.widthIn(max = 1240.dp).fillMaxSize().imePadding()) {
        val withRail = maxWidth >= 880.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.35f)
        val leading = listOf(!withRail, grouped, filter.active, hiddenSaved != null, empty).count { it }
        val collapsed by remember(scroll, withRail) {
            derivedStateOf {
                if (withRail) false
                else if (scroll.firstVisibleItemIndex > 0) true
                else {
                    val hero = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == DETAIL_SUMMARY_KEY }
                    hero != null && scroll.firstVisibleItemScrollOffset > hero.size * .62f
                }
            }
        }
        val jumps = remember(withRail, list, entries, grouping, visibleItems) {
            if (withRail) list.detailJumps(entries, grouping, visibleItems) else emptyList()
        }
        LaunchedEffect(revealItem, entries, leading) {
            if (revealItem == null) return@LaunchedEffect
            val target = entries.indexOfFirst { it is DetailEntry.Row && it.row.item.id == revealItem }
            if (target >= 0) scroll.scrollToItem(leading + target)
            ui.itemToReveal.remove(list.id)
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ListDetailToolbar(
                    list = list,
                    collapsed = collapsed,
                    progress = progress,
                    itemViews = itemViews,
                    searching = searching,
                    onBack = { model.select(null) },
                    onSearch = {
                        if (searching) closeSearch()
                        else {
                            ui.itemSelections.remove(list.id)
                            if (reordering) ui.reorderListId = null
                            ui.itemSearchListId = list.id
                        }
                    },
                    onView = {
                        focusManager.clearFocus()
                        edit(Editor.Filters(list, filter, view = true))
                    },
                    options = {
                        ListDetailOptions(
                            list, state, writable, model, edit, confirm, organization, itemViews,
                            canFilter, filter, reordering, showDetails, visibleItems, canReorderGroups,
                            startReorder = ::startReorder, clearFilters = ::clearFilters,
                            chooseCover = chooseCover,
                            selectItems = if (showingCapture && visibleItems.isNotEmpty()) {
                                {
                                    if (ui.itemSearchListId == list.id) ui.itemSearchListId = null
                                    focusManager.clearFocus()
                                    ui.itemSelections[list.id] = emptySet()
                                }
                            } else null,
                        )
                    },
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = searching,
                    enter = expandVertically(GatherMotion.quick(), expandFrom = Alignment.Top) + fadeIn(GatherMotion.quick()),
                    exit = shrinkVertically(GatherMotion.quick(), shrinkTowards = Alignment.Top) + fadeOut(GatherMotion.quick()),
                    label = "List search",
                ) {
                    ListDetailSearch(
                        query, { ui.itemQueries[list.id] = it },
                        requestFocus = ui.itemSearchListId == list.id,
                    )
                }
                if (itemViews && !showingCapture) {
                    ItemViewModeControls(
                        visibleCount = visibleItems.size,
                        selectedCount = selected?.size,
                        reordering = reordering,
                        writable = writable,
                        finishMode = ::finishMode,
                        openGroupOrder = if (canReorderGroups) {
                            { edit(Editor.GroupOrder(list.id, grouping)) }
                        } else null,
                        openBulk = { places ->
                            if (!selected.isNullOrEmpty()) edit(Editor.BulkAdd(list, selected, places))
                        },
                    )
                }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).testTag("detail-items")
                        .reorderDragInput(drag, reordering && writable, list.orderToken) { source, target, token ->
                            val from = reorderRows[source.key]
                            val to = reorderRows[target]
                            if (from != null && to != null && from.bucketKey == to.bucketKey && from.bucket.itemIds == source.bucket.itemIds) {
                                moveItem(from, to.row.item.id, token)
                            } else {
                                model.report("This group changed. Review the latest list and try again.")
                            }
                        },
                    state = scroll,
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    if (!withRail) item(key = DETAIL_SUMMARY_KEY) {
                        ListDetailSummary(list, progress, writable, model)
                    }
                    if (grouped) item(key = "item-grouping") {
                        val count = if (visibleItems.size == 1) "1 item" else "${visibleItems.size} items"
                        Text(
                            "Grouped by ${grouping.label.lowercase()} · $count",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (filter.active)
                        item(key = "active-filters") {
                            Column {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { openFilter(filter) }, modifier = Modifier.weight(1f)) {
                                        Text(filter.summary(list), modifier = Modifier.fillMaxWidth())
                                    }
                                    TextButton(onClick = ::clearFilters) { Text("Clear") }
                                }
                                if (filter.place != null && filter.place != FILTER_UNSET) {
                                    val unassigned = list.items.count { it.availability.mode == "unassigned" }
                                    if (unassigned > 0)
                                        TextButton(onClick = { openFilter(filter.copy(place = FILTER_UNSET)) }) {
                                            Text("$unassigned without a place")
                                        }
                                }
                            }
                        }
                    if (hiddenSaved != null)
                        item(key = "saved-hidden-item") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Item saved. It isn't shown with these filters.", Modifier.weight(1f))
                                TextButton(onClick = {
                                    clearFilters()
                                    ui.itemToReveal[list.id] = hiddenSaved
                                }) { Text("View item") }
                            }
                        }
                    if (narrowed && visibleItems.isEmpty())
                        item {
                            Text("No items match these filters.", Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            TextButton(onClick = ::clearFilters) { Text("Clear filters") }
                        }
                    else if (list.items.isEmpty())
                        item {
                            Text("Add your first item.", Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    items(entries, key = { it.key }, contentType = { it::class }) { entry ->
                        val placement = Modifier.animateItem(
                            fadeInSpec = GatherMotion.quick(),
                            placementSpec = GatherMotion.standard(),
                            fadeOutSpec = GatherMotion.quick(),
                        )
                        when (entry) {
                            DetailEntry.Completed -> Text(
                                "Done",
                                Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 5.dp).semantics { heading() },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            is DetailEntry.SectionHeading -> {
                                val section = entry.section
                                Row(
                                    placement.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        section?.title ?: "Unsectioned",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f).semantics { heading() },
                                    )
                                    if (section != null && !entry.done && showingCapture) {
                                        IconButton(
                                            onClick = { edit(Editor.ItemForm(list, sectionId = section.id, filter = filter)) },
                                            enabled = writable,
                                        ) { Icon(Icons.Outlined.Add, "Add item to ${section.title}") }
                                        if (!filter.active) IconButton(
                                            onClick = { edit(Editor.SectionForm(list, section)) },
                                            enabled = writable,
                                        ) { Icon(Icons.Outlined.MoreHoriz, "Edit section ${section.title}") }
                                    }
                                }
                            }
                            is DetailEntry.GroupHeading -> Text(
                                entry.title,
                                placement.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            is DetailEntry.EmptySection -> Text(
                                "No items in this section",
                                Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            is DetailEntry.Row -> {
                                val item = entry.row.item
                                val rowSelected = selected?.contains(item.id)
                                val toggle = {
                                    val current = ui.itemSelections[list.id]
                                    if (current != null) {
                                        ui.itemSelections[list.id] = if (item.id in current) current - item.id else current + item.id
                                    } else if (list.kind == "checklist") {
                                        model.action("item.update", payload("listId" to list.id, "itemId" to item.id, "checked" to !item.checked))
                                    }
                                    Unit
                                }
                                val index = entry.bucket.itemIds.indexOf(item.id)
                                val moveUp = entry.bucket.itemIds.getOrNull(index - 1)?.let { id ->
                                    { moveItem(entry, id, list.orderToken) }
                                }
                                val moveDown = entry.bucket.itemIds.getOrNull(index + 1)?.let { id ->
                                    { moveItem(entry, id, list.orderToken) }
                                }
                                val animatingDrag = drag.activeKey == entry.key
                                val moveTarget = reordering && dragTarget == entry.key
                                ItemRow(
                                    list,
                                    item,
                                    writable,
                                    toggle,
                                    { if (rowSelected == null) edit(Editor.ItemForm(list, item)) else toggle() },
                                    ordinal = ordinals[item.id],
                                    description = item.note.takeIf { showDetails }.orEmpty(),
                                    detail = if (showDetails) list.itemMetadata(item, filter, grouping) else "",
                                    alias = entry.row.aliasSummary,
                                    selected = rowSelected,
                                    highlighted = animatingDrag || moveTarget,
                                    dragHandle = if (!reordering) null else {
                                        {
                                            ReorderDragHandle(
                                                ReorderDragEntry(entry.key, entry) { entry.bucket.rows.mapTo(hashSetOf(), entry::siblingKey) },
                                                drag, item.text, writable && entry.bucket.itemIds.size > 1,
                                            )
                                        }
                                    },
                                    reorderMenu = if (!reordering) null else {
                                        { ItemReorderMenu(item.text, writable && list.orderToken != null, moveUp, moveDown) }
                                    },
                                    modifier = placement.then(
                                        if (animatingDrag) Modifier.zIndex(1f).graphicsLayer {
                                            translationY = drag.translation(entry.key)
                                            shadowElevation = 5.dp.toPx()
                                            shape = RoundedCornerShape(18.dp)
                                        } else Modifier,
                                    ),
                                )
                            }
                        }
                    }
                }
                if (showingCapture) {
                    ListDetailComposer(
                        capture, { if (writable) ui.itemCapture[list.id] = it },
                        connected = state.connected, writable = writable, focus = captureFocus, add = addItem,
                    )
                }
            }
            if (withRail) {
                VerticalDivider()
                ListDetailInfoRail(
                    list = list,
                    grouping = grouping,
                    jumps = jumps,
                    jump = { index -> scope.launch { scroll.animateScrollToItem(leading + index) } },
                    progress = progress,
                    writable = writable,
                    model = model,
                )
            }
        }
    }
}

@Composable
private fun ListDetailOptions(
    list: GatherList,
    state: GatherState,
    writable: Boolean,
    model: GatherViewModel,
    edit: (Editor) -> Unit,
    confirm: (Confirmation) -> Unit,
    organization: Boolean,
    itemViews: Boolean,
    canFilter: Boolean,
    filter: ListFilter,
    reordering: Boolean,
    showDetails: Boolean,
    visibleItems: List<ListItem>,
    canReorderGroups: Boolean,
    startReorder: () -> Unit,
    clearFilters: () -> Unit,
    chooseCover: () -> Unit,
    selectItems: (() -> Unit)?,
) {
    var more by remember(list.id) { mutableStateOf(false) }
    val ui = model.ui
    val openFilter = { draft: ListFilter -> edit(Editor.Filters(list, draft)) }
    Box {
        IconButton(onClick = { more = true }, modifier = Modifier.semantics { contentDescription = "List options" }) {
            if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.MoreHoriz, null)
        }
        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
            if (itemViews && selectItems != null) DropdownMenuItem(
                text = { Text("Select items") },
                leadingIcon = { Icon(Icons.Outlined.CheckBoxOutlineBlank, null) },
                onClick = {
                    more = false
                    selectItems()
                },
                enabled = writable,
            )
            if (itemViews && (visibleItems.size > 1 || canReorderGroups) && !reordering)
                DropdownMenuItem(
                    text = { Text("Reorder items") },
                    leadingIcon = { Icon(Icons.Outlined.SwapVert, null) },
                    onClick = {
                        more = false
                        startReorder()
                    },
                    enabled = writable,
                )
            else if (!itemViews && filter.active && list.items.isNotEmpty())
                DropdownMenuItem(
                    text = { Text("Edit order") },
                    onClick = {
                        more = false
                        confirm(
                            Confirmation("Edit order", "Show all items to edit their order.", "Show all items") {
                                val anchor = visibleItems.firstOrNull()
                                clearFilters()
                                if (anchor != null) ui.itemToReveal[list.id] = anchor.id
                            }
                        )
                    },
                )
            DropdownMenuItem(
                text = { Text("List details") },
                onClick = {
                    more = false
                    edit(Editor.ListForm(list))
                },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                enabled = writable,
            )
            if ("list-covers" in state.capabilities) DropdownMenuItem(
                text = { Text(if (list.coverId == null) "Add cover" else "Change cover") },
                leadingIcon = { Icon(Icons.Outlined.Image, null) },
                onClick = {
                    more = false
                    chooseCover()
                },
                enabled = writable,
            )
            if (organization && list.owner)
                DropdownMenuItem(
                    text = { Text("Number items") },
                    onClick = {
                        more = false
                        model.action(
                            "list.update",
                            payload(
                                "listId" to list.id,
                                "numbered" to !list.numbered,
                                "expectedOrderToken" to list.orderToken,
                            ),
                        )
                    },
                    trailingIcon = { if (list.numbered) Icon(Icons.Outlined.Check, "On") },
                    enabled = writable,
                )
            if (canFilter)
                DropdownMenuItem(
                    text = { Text("Filter this list") },
                    onClick = {
                        more = false
                        openFilter(filter)
                    },
                )
            if (list.hasItemDetails)
                DropdownMenuItem(
                    text = { Text(if (showDetails) "Hide item details" else "Show item details") },
                    onClick = {
                        more = false
                        ui.hiddenDetails[list.id] = showDetails
                    },
                )
            if (organization && (list.places.isNotEmpty() || list.recipients.isNotEmpty()))
                DropdownMenuItem(
                    text = { Text("Manage places and people") },
                    onClick = {
                        more = false
                        edit(Editor.Definitions(list))
                    },
                    enabled = writable,
                )
            DropdownMenuItem(
                text = { Text("Make a private copy") },
                onClick = {
                    more = false
                    model.action(
                        "list.clone",
                        payload("listId" to list.id),
                        true,
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                enabled = writable,
            )
            DropdownMenuItem(
                text = { Text("Add section") },
                onClick = {
                    more = false
                    edit(Editor.SectionForm(list))
                },
                leadingIcon = { Icon(Icons.Outlined.Add, null) },
                enabled = writable,
            )
            DropdownMenuItem(
                text = { Text(if (list.favorite) "Remove from favorites" else "Add to favorites") },
                leadingIcon = { Icon(if (list.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline, null) },
                onClick = {
                    more = false
                    model.action("list.update", payload("listId" to list.id, "favorite" to !list.favorite))
                },
                enabled = writable,
            )
            DropdownMenuItem(
                text = { Text("Share list") },
                leadingIcon = { Icon(Icons.Outlined.Group, null) },
                onClick = {
                    more = false
                    edit(Editor.Share(list))
                    model.loadSharing(list.id)
                },
                enabled = writable,
            )
            DropdownMenuItem(
                text = { Text("Refresh lists") },
                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                onClick = {
                    more = false
                    model.refresh()
                },
                enabled = !state.busy,
            )
            if (list.kind == "checklist" && !list.isTemplate && !list.archived && list.checkedCount > 0)
                DropdownMenuItem(
                    text = { Text("Reset checkmarks") },
                    leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                    onClick = {
                        more = false
                        confirm(Confirmation(
                            "Reset checkmarks?",
                            if (list.shared) "This resets checkmarks for everyone."
                            else "This clears checkmarks and keeps every item, quantity, and note.",
                            "Reset checkmarks",
                        ) { model.action("list.reset", payload("listId" to list.id)) })
                    },
                    enabled = writable,
                )
            DropdownMenuItem(
                text = {
                    Text(
                        if (list.archived) "Restore to library"
                        else "Archive for me"
                    )
                },
                onClick = {
                    more = false
                    if (list.archived)
                        model.action(
                            "list.update",
                            payload("listId" to list.id, "archived" to false),
                        )
                    else
                        confirm(
                            Confirmation(
                                "Archive this list for you?",
                                "You can restore it from Archive. Others can still see it in their library.",
                                "Archive",
                            ) {
                                model.action(
                                    "list.update",
                                    payload(
                                        "listId" to list.id,
                                        "archived" to true,
                                    ),
                                )
                            }
                        )
                },
                leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                enabled = writable,
            )
            if (list.owner)
                DropdownMenuItem(
                    text = {
                        Text(
                            "Delete list",
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        more = false
                        confirm(
                            Confirmation(
                                if (list.shared)
                                    "Delete this list for everyone?"
                                else "Delete this list?",
                                if (list.shared)
                                    "Everyone will lose access. This can’t be undone."
                                else
                                    "All its items and notes will be permanently deleted. This can’t be undone.",
                                "Delete",
                                true,
                            ) {
                                model.action(
                                    "list.delete",
                                    payload("listId" to list.id),
                                )
                            }
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                    enabled = writable,
                )
            else
                DropdownMenuItem(
                    text = {
                        Text(
                            "Leave shared list",
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        more = false
                        confirm(
                            Confirmation(
                                "Leave this list?",
                                "Your edits stay with the list. You’ll need a new invitation to return.",
                                "Leave",
                                true,
                            ) {
                                model.sharingAction("leave", list.id)
                            }
                        )
                    },
                    enabled = writable,
                )
        }
    }
}
