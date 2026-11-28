package ninja.takeya.gather

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val MAX_PEOPLE = 200
internal const val MAX_PLACE_SELECTIONS = 25

@Composable
internal fun AssignmentOptions(
    search: String,
    onSearch: (String) -> Unit,
    label: String,
    options: List<Pair<String, String>>,
    selectedIds: List<String>,
    newNames: List<String>,
    single: Boolean = false,
    limit: Int = MAX_PEOPLE,
    existingNames: List<String> = options.map { it.second },
    clearLabel: String? = null,
    enabled: Boolean = true,
    onChange: (List<String>, List<String>) -> Unit,
) {
    FormText(search, onSearch, label, 100, enabled = enabled)
    val query = normalizeLabel(search)
    val selectionFull = !single && selectedIds.size + newNames.size >= limit
    if (query.isBlank() && clearLabel != null && (selectedIds.isNotEmpty() || newNames.isNotEmpty())) {
        DetailChoice(clearLabel, null, enabled) { onChange(emptyList(), emptyList()) }
    }
    options.filter { (_, name) -> name.contains(query, ignoreCase = true) }.forEach { (id, name) ->
        val selected = id in selectedIds
        SelectionRow(name, selected, single, enabled && (selected || !selectionFull)) {
            if (single) onChange(listOf(id), emptyList())
            else onChange(if (selected) selectedIds - id else selectedIds + id, newNames)
        }
    }
    newNames.filter { it.contains(query, ignoreCase = true) }.forEach { name ->
        SelectionRow(name, true, single, enabled) {
            if (!single) onChange(selectedIds, newNames - name)
        }
    }
    if (query.isNotBlank() && (existingNames + newNames).none { it.equals(query, ignoreCase = true) }) {
        DetailChoice("Create “$query”", null, enabled && !selectionFull) {
            if (single) onChange(emptyList(), listOf(query))
            else onChange(selectedIds, newNames + query)
            onSearch("")
        }
    }
}

@Composable
internal fun BulkAddForm(editor: Editor.BulkAdd, state: GatherState, model: GatherViewModel, dismiss: () -> Unit) {
    val list = state.workspace.lists.find { it.id == editor.list.id } ?: editor.list
    val selected = list.items.filter { it.id in editor.itemIds }
    var search by rememberEditorDraft("bulk-search") { "" }
    var ids by rememberEditorDraft("bulk-assignment-ids") { emptyList<String>() }
    var names by rememberEditorDraft("bulk-assignment-names") { emptyList<String>() }
    val places = editor.places
    val options = if (places) list.places.map { it.id to it.label } else list.recipients.map { it.id to it.name }
    val missing = ids.any { id -> options.none { it.first == id } }
    val existingNames = if (places) list.places.map { it.name } else list.recipients.map { it.name }
    val anywhereIds = if (places) selected.filter { it.availability.mode == "anywhere" }.map { it.id } else emptyList()
    val anywhereCount = anywhereIds.size
    val overLimit = places && selected.any { item ->
        (item.availability.placeIds.toSet() + ids).size + names.size > MAX_PLACE_SELECTIONS
    }
    val itemCount = if (selected.size == 1) "1 item" else "${selected.size} items"
    SheetHeader(
        if (places) "Add places" else "Add people",
        if (places) "Keeps existing named places." else "Keeps existing people.",
    )
    if (anywhereCount > 0) {
        val affected = if (anywhereCount == 1) "1 item" else "$anywhereCount items"
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "$affected marked Anywhere will use the selected places instead.",
                Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
    AssignmentOptions(
        search, { search = it }, if (places) "Search or add a place" else "Search or add a name",
        options.sortedWith(compareByDescending<Pair<String, String>> { it.first in ids }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.second }),
        ids, names, limit = if (places) MAX_PLACE_SELECTIONS else MAX_PEOPLE,
        existingNames = existingNames,
        enabled = !state.busy,
        onChange = { selectedIds, newNames ->
            ids = selectedIds
            names = newNames
        },
    )
    ids.filter { id -> options.none { it.first == id } }.forEach { id ->
        DetailChoice("Remove unavailable ${if (places) "place" else "person"}", null, !state.busy) { ids = ids - id }
    }
    if (selected.isEmpty()) DraftWarning("These items are no longer available.")
    if (overLimit) DraftWarning("Each item can have up to $MAX_PLACE_SELECTIONS places.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(
            enabled = state.canEditWorkspace && !state.busy && selected.isNotEmpty() &&
                (ids.isNotEmpty() || names.isNotEmpty()) && !missing && !overLimit,
            modifier = Modifier.weight(1.5f),
            onClick = {
                val idsKey = if (places) "placeIds" else "recipientIds"
                val namesKey = if (places) "newPlaces" else "newRecipients"
                val expected = if (places) arrayOf("expectedAnywhereItemIds" to anywhereIds) else emptyArray()
                model.action(
                    "item.bulkAdd",
                    payload(
                        "listId" to list.id, "itemIds" to selected.map { it.id },
                        idsKey to ids, namesKey to names.map { payload("name" to it) },
                        *expected,
                    ),
                    onSuccess = {
                        model.report("${if (places) "Places" else "People"} added to $itemCount.")
                        dismiss()
                    },
                )
            },
        ) { Text("Add to $itemCount") }
    }
}
