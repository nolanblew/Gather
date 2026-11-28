@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ninja.takeya.gather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FilterForm(editor: Editor.Filters, state: GatherState, model: GatherViewModel, dismiss: () -> Unit) {
    val list = state.workspace.lists.find { it.id == editor.list.id } ?: editor.list
    var filter by rememberEditorDraft("filter") { editor.filter }
    val initialSort = model.itemSort(list)
    var sort by rememberEditorDraft("item-sort") { initialSort }
    var grouping by rememberEditorDraft("item-grouping") { model.ui.itemGrouping[list.id] ?: ItemGrouping.None }
    val showPlaces = list.usesPlaces || editor.list.usesPlaces || editor.filter.place != null
    val showPeople = list.usesRecipients || editor.list.usesRecipients || editor.filter.person != null
    val showCompletion = list.kind == "checklist" &&
        (list.checkedCount > 0 || editor.list.checkedCount > 0 || editor.filter.notDone)
    LaunchedEffect(list.places, list.recipients, list.kind) {
        val place = filter.place?.takeIf { list.placeFilterLabel(it) != null }
        val person = filter.person?.takeIf { id -> id == FILTER_UNSET || list.recipients.any { it.id == id } }
        filter = filter.copy(place = place, person = person, notDone = filter.notDone && list.kind == "checklist")
    }
    SheetHeader(if (editor.view) "View" else "Filters")
    if (editor.view) {
        Choice("Sort by", sort.name, ItemSort.entries.map { it.name to it.label }) { sort = ItemSort.valueOf(it) }
        Text("Group by", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ItemGrouping.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = grouping == option,
                    onClick = { grouping = option },
                    shape = SegmentedButtonDefaults.itemShape(index, ItemGrouping.entries.size),
                    icon = {},
                ) { Text(option.label) }
            }
        }
    }
    if (showPlaces) {
        val unassigned = list.items.count { it.availability.mode == "unassigned" }
        val usedPlaces = list.items.flatMap { it.availability.placeIds }.toSet()
        FilterOptions(
            "Places",
            filter.place.orEmpty(),
            listOf("" to "All places") +
                list.places.filter { it.id in usedPlaces || it.id == filter.place || it.id == editor.filter.place }
                    .sortedBy { it.label.lowercase() }.map { it.id to it.label } +
                listOf(FILTER_ANYWHERE to "Anywhere only", FILTER_UNSET to "No places set"),
            unassigned,
            compact = editor.view,
        ) { filter = filter.copy(place = it.ifEmpty { null }) }
    }
    if (showPeople) {
        val usedPeople = list.items.flatMap { it.peopleIds }.toSet()
        FilterOptions(
            "For someone",
            filter.person.orEmpty(),
            listOf("" to (if (editor.view) "All people" else "No filter"), FILTER_UNSET to "Not set") +
                list.recipients.filter { it.id in usedPeople || it.id == filter.person || it.id == editor.filter.person }
                    .sortedBy { it.name.lowercase() }.map { it.id to it.name },
            compact = editor.view,
        ) { filter = filter.copy(person = it.ifEmpty { null }) }
    }
    if (showCompletion && list.kind == "checklist")
        FilterOptions(
            "Completion", if (filter.notDone) "not-done" else "",
            listOf("" to "All items", "not-done" to "Not done"),
            compact = editor.view,
        ) {
            filter = filter.copy(notDone = it == "not-done")
        }
    val count = list.viewItems(sort, filter, model.ui.itemQueries[list.id].orEmpty()).size
    Text(if (count == 1) "1 item matches" else "$count items match", style = MaterialTheme.typography.bodyMedium)
    if (list.numbered && count < list.items.size)
        Text("Numbers follow the full list order.", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(
            onClick = {
                if (filter.active) model.ui.filters[list.id] = filter
                else model.ui.filters.remove(list.id)
                if (editor.view) {
                    if (sort != initialSort) model.setItemSort(list.id, sort)
                    model.ui.itemGrouping[list.id] = grouping
                }
                model.ui.hiddenSavedItems.remove(list.id)
                dismiss()
            },
            modifier = Modifier.weight(1f),
        ) { Text("Apply") }
    }
}

@Composable
private fun FilterOptions(
    label: String,
    selection: String,
    options: List<Pair<String, String>>,
    unassigned: Int? = null,
    compact: Boolean = false,
    change: (String) -> Unit,
) {
    if (compact || options.size > 8) {
        Choice(label, selection, options, onChange = change)
        if (unassigned != null)
            Text("$unassigned without a place", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(Modifier.selectableGroup()) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        options.forEach { (id, name) ->
            SelectionRow(
                name,
                id == selection,
                single = true,
                trailing = unassigned?.takeIf { id == FILTER_UNSET }?.toString(),
            ) { change(id) }
            HorizontalDivider()
        }
    }
}

@Composable
fun DefinitionsForm(
    editor: Editor.Definitions,
    state: GatherState,
    model: GatherViewModel,
    dismiss: () -> Unit,
    confirm: (Confirmation) -> Unit,
) {
    val list = state.workspace.lists.find { it.id == editor.list.id } ?: editor.list
    var editingType by rememberEditorDraft("definition-type") { "" }
    var editingId by rememberEditorDraft("definition-id") { "" }
    var name by rememberEditorDraft("definition-name") { "" }
    var region by rememberEditorDraft("definition-region") { "" }
    var country by rememberEditorDraft("definition-country") { "" }
    var kind by rememberEditorDraft("definition-kind") { "other" }
    var options by rememberEditorDraft("definition-options") { false }
    val place = list.places.find { it.id == editingId }
    val person = list.recipients.find { it.id == editingId }
    val writable = state.canEditWorkspace && !state.busy
    if (editingType.isEmpty()) {
        SheetHeader("Places and people")
        if (list.places.isNotEmpty()) {
            Text("Places", style = MaterialTheme.typography.titleMedium)
            list.places.sortedBy { it.label.lowercase() }.forEach { value ->
                DetailChoice(value.label) {
                    editingType = "place"
                    editingId = value.id
                    name = value.name
                    region = value.region
                    country = value.countryCode
                    kind = value.kind
                    options = value.region.isNotBlank() || value.countryCode.isNotBlank() || value.kind != "other"
                }
            }
        }
        if (list.recipients.isNotEmpty()) {
            Text("For someone", style = MaterialTheme.typography.titleMedium)
            list.recipients.sortedBy { it.name.lowercase() }.forEach { value ->
                DetailChoice(value.name) {
                    editingType = "recipient"
                    editingId = value.id
                    name = value.name
                }
            }
        }
        DoneButton(dismiss)
        return
    }

    val isPlace = editingType == "place"
    val normalized = normalizeLabel(name)
    val unique = if (isPlace) list.places.none {
        it.id != editingId && it.name.equals(normalized, true) &&
            it.kind == kind && it.region.equals(normalizeLabel(region), true) &&
            it.countryCode.equals(country.trim(), true)
    } else list.recipients.none { it.id != editingId && it.name.equals(normalized, true) }
    val exists = place != null || person != null
    val validCountry = country.isBlank() || country.trim().matches(Regex("[a-zA-Z]{2}"))
    SheetHeader(if (isPlace) "Edit place" else "Edit person")
    FormText(name, { name = it }, if (isPlace) "Place name" else "Name", 100, enabled = !state.busy)
    if (isPlace && !options) TextButton(onClick = { options = true }, enabled = !state.busy) {
        Text("More options")
    }
    if (isPlace && options) {
        FormText(region, { region = it }, "Region", 100, enabled = !state.busy)
        FormText(country, { country = it }, "Country code", 2, enabled = !state.busy)
        Choice(
            "Kind",
            kind,
            listOf("other" to "Other", "city" to "City", "store" to "Store", "home" to "Home", "work" to "Work", "online" to "Online"),
            !state.busy,
        ) { kind = it }
    }
    if (!unique) DraftWarning(if (isPlace) "This place already exists. Choose it instead." else "This name already exists. Choose it instead.")
    if (!exists) DraftWarning("This name is no longer available.")
    if (!validCountry) DraftWarning("Enter a two-letter country code.")
    SaveButton(state, "Save", normalized.isNotEmpty() && unique && exists && validCountry) {
        val values = if (isPlace)
            payload("listId" to list.id, "placeId" to editingId, "name" to normalized, "region" to normalizeLabel(region),
                "countryCode" to country.trim().uppercase(java.util.Locale.ROOT), "kind" to kind)
        else payload("listId" to list.id, "recipientId" to editingId, "name" to normalized)
        model.action("$editingType.update", values, onSuccess = { editingType = "" })
    }
    TextButton(
        onClick = {
            val count = if (isPlace) list.items.count { editingId in it.availability.placeIds }
                else list.items.count { editingId in it.peopleIds }
            val label = place?.label ?: person?.name ?: return@TextButton
            val itemCount = if (count == 1) "1 item" else "$count items"
            val actionLabel = if (isPlace) "Delete place" else "Delete person"
            val token = if (place != null) list.placeAssignmentToken(place)
                else list.recipientAssignmentToken(checkNotNull(person))
            confirm(Confirmation(
                "Delete $label?",
                if (isPlace) "It will be removed from $itemCount. Items and other places will stay."
                else "They will be removed from $itemCount. Items will stay.",
                actionLabel,
                danger = true,
            ) {
                model.action(
                    "$editingType.delete",
                    payload("listId" to list.id, (if (isPlace) "placeId" else "recipientId") to editingId, "expectedAssignmentToken" to token),
                    onSuccess = { editingType = "" },
                )
            })
        },
        enabled = writable && exists,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (isPlace) "Delete place" else "Delete person") }
    TextButton(onClick = { editingType = "" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
}
