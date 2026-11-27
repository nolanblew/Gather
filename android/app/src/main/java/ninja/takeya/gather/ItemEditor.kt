package ninja.takeya.gather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private enum class ItemPanel { Item, AddDetail, Places, Person, Section, Order }

@Composable
fun ItemForm(
    editor: Editor.ItemForm,
    state: GatherState,
    model: GatherViewModel,
    dismiss: () -> Unit,
    confirm: (Confirmation) -> Unit,
) {
    val list = state.workspace.lists.firstOrNull { it.id == editor.list.id } ?: editor.list
    val original = editor.item
    val latest = list.items.firstOrNull { it.id == original?.id }
    var duplicating by rememberEditorDraft("duplicating") { false }
    var needsPerson by rememberEditorDraft("duplicate-needs-person") { false }
    val item = original.takeUnless { duplicating }
    var text by rememberEditorDraft("text") { original?.text ?: editor.initialText }
    var quantity by rememberEditorDraft("quantity") { original?.quantity.orEmpty() }
    var note by rememberEditorDraft("note") { original?.note.orEmpty() }
    var descriptionSource by rememberEditorDraft("description-source") { original.descriptionDraft() }
    var descriptionDraft by rememberEditorDraft("description-draft") { descriptionSource }
    var subitemSource by rememberEditorDraft("subitem-source") { original?.subitems.orEmpty() }
    var subitems by rememberEditorDraft("subitems") { subitemSource }
    var expectedDescription by rememberEditorDraft("expected-description") { original?.description.orEmpty() }
    var expectedNote by rememberEditorDraft("expected-note") { original?.note.orEmpty() }
    var expectedSubitems by rememberEditorDraft("expected-subitems") { original?.subitems.orEmpty() }
    var preparingImage by rememberEditorDraft("preparing-description-image") { false }
    var section by rememberEditorDraft("section") { original?.sectionId ?: editor.sectionId.orEmpty() }
    var availability by rememberEditorDraft("availability") {
        original?.availability ?: editor.filter.draftAvailability()
    }
    var recipients by rememberEditorDraft("recipients") {
        original?.peopleIds ?: listOfNotNull(editor.filter.person.takeUnless { it == FILTER_UNSET })
    }
    var expectedAvailability by rememberEditorDraft("expected-availability") {
        original?.availability ?: Availability()
    }
    var expectedRecipients by rememberEditorDraft("expected-recipients") { original?.peopleIds.orEmpty() }
    var newPlaces by rememberEditorDraft("new-places") { emptyList<String>() }
    var newPeople by rememberEditorDraft("new-people") { emptyList<String>() }
    var personPickerBaseline by rememberEditorDraft("person-picker-baseline") { recipients to newPeople }
    var descriptionVisible by rememberEditorDraft("description-visible") { note.isNotBlank() || descriptionDraft.isNotEmpty() }
    var quantityVisible by rememberEditorDraft("quantity-visible") { quantity.isNotBlank() }
    var sectionVisible by rememberEditorDraft("section-visible") { section.isNotBlank() }
    var panel by rememberEditorDraft("panel") { ItemPanel.Item }
    var search by rememberEditorDraft("detail-search") { "" }
    var actions by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val select = { next: ItemPanel ->
        if (next == ItemPanel.Person) personPickerBaseline = recipients to newPeople
        search = ""
        panel = next
        scope.launch { model.ui.editorScroll.scrollTo(0) }
        Unit
    }
    val enabled = !state.busy && state.canEditWorkspace
    val organization = state.workspace.version >= 3
    val multiplePeople = state.workspace.version >= 4
    val richDescriptions = "item-descriptions" in state.capabilities
    val hasSubchecklist = list.kind == "checklist" && "checklist-subitems" in state.capabilities
    val placesVisible = availability.mode != "unassigned" ||
        (original == null && editor.filter.place == FILTER_UNSET)
    val personVisible = recipients.isNotEmpty() || newPeople.isNotEmpty() ||
        (original == null && editor.filter.person == FILTER_UNSET)
    val placeName = { id: String ->
        (list.places.find { it.id == id } ?: editor.list.places.find { it.id == id })?.label
            ?: "Place"
    }
    val personName = { id: String ->
        (list.recipients.find { it.id == id } ?: editor.list.recipients.find { it.id == id })?.name
            ?: "Person"
    }
    val missingPlaces = availability.placeIds.filter { id -> list.places.none { it.id == id } }
    val missingRecipients = recipients.filter { id -> list.recipients.none { it.id == id } }
    val missingSection = section.isNotBlank() && list.sections.none { it.id == section }
    val changingPlaces = !availability.equivalent(expectedAvailability) || newPlaces.isNotEmpty()
    val changingPerson = recipients.toSet() != expectedRecipients.toSet() || newPeople.isNotEmpty()
    val changingSection = section.ifBlank { null } != item?.sectionId
    val placesConflict =
        item != null && latest != null && changingPlaces &&
            !latest.availability.equivalent(expectedAvailability)
    val personConflict =
        item != null && latest != null && changingPerson && latest.peopleIds.toSet() != expectedRecipients.toSet()
    val changingDescription = richDescriptions && descriptionDraft.map { it.block } != descriptionSource.map { it.block }
    val changingSubitems = hasSubchecklist && subitems != subitemSource
    val descriptionConflict = item != null && latest != null && changingDescription &&
        (latest.description != expectedDescription || latest.note != expectedNote)
    val subitemConflict = item != null && latest != null && changingSubitems && latest.subitems != expectedSubitems

    fun showDescription() {
        descriptionVisible = true
        if (descriptionDraft.none { it.block.type != "image" } && descriptionDraft.size < MAX_DESCRIPTION_BLOCKS) {
            descriptionDraft = listOf(DescriptionEntry()) + descriptionDraft
        }
    }

    when (panel) {
        ItemPanel.AddDetail -> {
            SheetHeader("Add detail")
            val placeChoice = "Place" to { select(ItemPanel.Places) }
            val personChoice = "For someone" to { select(ItemPanel.Person) }
            val choices = buildList {
                if (organization && !placesVisible && editor.list.usesPlaces) add(placeChoice)
                if (organization && !personVisible && editor.list.usesRecipients) add(personChoice)
                if (!descriptionVisible) add("Description" to {
                    showDescription()
                    select(ItemPanel.Item)
                })
                if (organization && !placesVisible && !editor.list.usesPlaces) add(placeChoice)
                if (organization && !personVisible && !editor.list.usesRecipients) add(personChoice)
                if (!quantityVisible) add("Quantity" to {
                    quantityVisible = true
                    select(ItemPanel.Item)
                })
                if (!sectionVisible)
                    add("Section" to {
                        if (list.sections.isEmpty()) select(ItemPanel.Section)
                        else {
                            sectionVisible = true
                            select(ItemPanel.Item)
                        }
                    })
            }
            choices.forEach { (label, action) ->
                DetailChoice(label, if (label == "For someone") "Who this item is for" else null, action)
            }
            DoneButton { select(ItemPanel.Item) }
        }
        ItemPanel.Section -> {
            SheetHeader("Section", "Add a section from List options to use it here.")
            DoneButton { select(ItemPanel.Item) }
        }
        ItemPanel.Places -> {
            SheetHeader("Choose places", "Any one of these places works.")
            FormText(search, { search = it }, "Search places", 100)
            val query = normalizeLabel(search)
            val selectionFull = availability.placeIds.size + newPlaces.size >= MAX_PLACE_SELECTIONS
            if (query.isBlank()) {
                SelectionRow("Anywhere", availability.mode == "anywhere", single = true) {
                    availability = Availability("anywhere")
                    newPlaces = emptyList()
                }
                SelectionRow("No places set", availability.mode == "unassigned", single = true) {
                    availability = Availability()
                    newPlaces = emptyList()
                }
            }
            fun choosePlaces(ids: List<String>, names: List<String>) {
                availability = if (ids.isEmpty() && names.isEmpty()) Availability()
                    else Availability("selected", ids)
                newPlaces = names
            }
            val sorted = list.places.sortedWith(
                compareByDescending<Place> { it.id in availability.placeIds }
                    .thenByDescending { place -> list.items.count { place.id in it.availability.placeIds } }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            )
            sorted.filter { it.label.contains(query, ignoreCase = true) }.forEach { place ->
                val selected = place.id in availability.placeIds
                SelectionRow(place.label, selected, enabled = selected || !selectionFull) {
                    val ids = if (selected) availability.placeIds - place.id else availability.placeIds + place.id
                    choosePlaces(ids, newPlaces)
                }
            }
            missingPlaces.forEach { id ->
                DetailChoice("Remove ${placeName(id)}", "This place is no longer available.") {
                    choosePlaces(availability.placeIds - id, newPlaces)
                }
            }
            newPlaces.filter { it.contains(query, ignoreCase = true) }.forEach { name ->
                SelectionRow(name, true) { choosePlaces(availability.placeIds, newPlaces - name) }
            }
            if (query.isNotBlank() && sorted.none { it.name.equals(query, ignoreCase = true) } &&
                newPlaces.none { it.equals(query, ignoreCase = true) }
            ) DetailChoice("Create “$query”", null, enabled = !selectionFull) {
                choosePlaces(availability.placeIds, newPlaces + query)
                search = ""
            }
            if (selectionFull) Text("You can choose up to $MAX_PLACE_SELECTIONS places.")
            DoneButton { select(ItemPanel.Item) }
        }
        ItemPanel.Person -> {
            SheetHeader("For someone", text.ifBlank { "Who is this item for?" })
            val sorted = list.recipients.sortedWith(
                compareByDescending<Recipient> { it.id in recipients }
                    .thenByDescending { person -> list.items.count { person.id in it.peopleIds } }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            AssignmentOptions(
                search, { search = it }, "Search people", sorted.map { it.id to it.name },
                recipients, newPeople, single = !multiplePeople,
                clearLabel = "Remove person".takeUnless { multiplePeople },
                enabled = enabled,
                onChange = { ids, names ->
                    recipients = ids
                    newPeople = names
                },
            )
            missingRecipients.forEach { id ->
                DetailChoice("Remove ${personName(id)}", "This person is no longer available.") {
                    recipients = recipients - id
                }
            }
            if (multiplePeople) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        recipients = personPickerBaseline.first
                        newPeople = personPickerBaseline.second
                        select(ItemPanel.Item)
                    }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { select(ItemPanel.Item) }, modifier = Modifier.weight(1f)) { Text("Done") }
                }
            } else {
                DoneButton { select(ItemPanel.Item) }
            }
        }
        ItemPanel.Order -> if (latest != null) {
            SheetHeader("Edit order")
            val group = sortedGroup(list, latest.sectionId)
            val index = group.indexOfFirst { it.id == latest.id }
            val move = { offset: Int ->
                model.action(
                    "item.move",
                    list.orderedPayload("itemId" to latest.id, "sectionId" to latest.sectionId, "index" to index + offset),
                )
            }
            OutlinedButton(onClick = { move(-1) }, enabled = index > 0 && enabled) {
                Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(18.dp))
                Text("Move up")
            }
            OutlinedButton(
                onClick = { move(1) },
                enabled = index in 0 until group.lastIndex && enabled,
            ) {
                Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(18.dp))
                Text("Move down")
            }
            DoneButton { select(ItemPanel.Item) }
        }
        ItemPanel.Item -> {
            SheetHeader(if (duplicating) "Duplicate item" else if (item == null) "Add item" else "Item details")
            if (!state.connected && state.canEditWorkspace) {
                Text("Changes will sync when you reconnect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FormText(text, { text = it }, "Item", 500, false, enabled, minLines = 1)
            if (descriptionVisible && richDescriptions)
                DescriptionEditor(descriptionDraft, { descriptionDraft = it }, state.workspace.lists, enabled, preparingImage, { preparingImage = it })
            else if (descriptionVisible)
                FormText(note, { note = it }, "Description", 4000, false, enabled, minLines = 2)
            else TextButton(onClick = ::showDescription, enabled = enabled) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add description")
            }
            if (hasSubchecklist) SubchecklistEditor(subitems, enabled) { subitems = it }
            if (organization && placesVisible) {
                val names = availability.placeIds.map(placeName) + newPlaces
                val summary = when (availability.mode) {
                    "anywhere" -> "Anywhere"
                    "selected" -> names.joinToString(" or ")
                    else -> "No places set"
                }
                DetailChoice("Places", summary, enabled = enabled) { select(ItemPanel.Places) }
            }
            if (organization && personVisible)
                DetailChoice("For someone", (recipients.map(personName) + newPeople).joinToString(", ").ifEmpty { "Not set" }, enabled = enabled) {
                    select(ItemPanel.Person)
                }
            if (quantityVisible) FormText(quantity, { quantity = it }, "Quantity", 100, enabled = enabled)
            if (sectionVisible)
                Choice(
                    "Section",
                    section,
                    listOf("" to "Unsectioned") + list.sections.sortedBy { it.position }.map { it.id to it.title },
                    enabled,
                ) { section = it }
            missingPlaces.forEach { DraftWarning("${placeName(it)} is no longer available. Choose another or remove it.") }
            missingRecipients.forEach { DraftWarning("${personName(it)} is no longer available. Choose another or remove it.") }
            if (missingSection) DraftWarning("This section is no longer available. Choose another or remove it.")
            if (placesConflict) {
                val saved = latest.availability.placeIds.joinToString(" or ", transform = placeName)
                    .ifEmpty { if (latest.availability.mode == "anywhere") "Anywhere" else "No places set" }
                DraftWarning("Places changed to $saved.")
                DetailChoice("Use saved places") {
                    expectedAvailability = latest.availability
                    availability = latest.availability
                    newPlaces = emptyList()
                }
                DetailChoice("Keep my places") { expectedAvailability = latest.availability }
            }
            if (personConflict) {
                val saved = latest.peopleIds.joinToString(", ", transform = personName).ifEmpty { "Not set" }
                DraftWarning("For someone changed to $saved.")
                DetailChoice(if (multiplePeople) "Use saved people" else "Use saved person") {
                    expectedRecipients = latest.peopleIds
                    recipients = latest.peopleIds
                    newPeople = emptyList()
                }
                DetailChoice(if (multiplePeople) "Keep my people" else "Keep my person") { expectedRecipients = latest.peopleIds }
            }
            if (descriptionConflict) {
                DraftWarning("The description changed while you were editing.")
                DetailChoice("Use saved description") {
                    expectedDescription = latest.description
                    expectedNote = latest.note
                    note = latest.note
                    descriptionSource = latest.descriptionDraft()
                    descriptionDraft = descriptionSource
                    showDescription()
                }
                DetailChoice("Keep my description") {
                    expectedDescription = latest.description
                    expectedNote = latest.note
                    note = latest.note
                }
            }
            if (subitemConflict) {
                DraftWarning("The subchecklist changed while you were editing.")
                DetailChoice("Use saved steps") {
                    expectedSubitems = latest.subitems
                    subitemSource = latest.subitems
                    subitems = subitemSource
                }
                DetailChoice("Keep my steps") { expectedSubitems = latest.subitems }
            }
            val hasMoreDetails = !descriptionVisible || !quantityVisible || !sectionVisible ||
                (organization && (!placesVisible || !personVisible))
            if (hasMoreDetails)
                TextButton(onClick = { select(ItemPanel.AddDetail) }, enabled = enabled) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add detail")
                }
            SaveButton(
                state,
                if (state.connected) "Save" else "Save on this device",
                text.isNotBlank() && (missingPlaces.isEmpty() || (item != null && !changingPlaces)) &&
                    (missingRecipients.isEmpty() || (item != null && !changingPerson)) &&
                    (!missingSection || (item != null && !changingSection)) && !placesConflict && !personConflict &&
                    !descriptionConflict && !subitemConflict && !preparingImage &&
                    (!needsPerson || recipients.isNotEmpty() || newPeople.isNotEmpty()),
            ) {
                val action = if (item == null) "item.create" else "item.update"
                val description = if (richDescriptions && (item == null || changingDescription)) descriptionDraft.descriptionWrite() else null
                val fields = list.itemWritePayload(
                        item, text.trim(), description?.note ?: note, quantity, section.ifBlank { null },
                        availability, recipients, newPlaces, newPeople,
                        if (item == null) Availability() else expectedAvailability,
                        if (item == null) emptyList() else expectedRecipients,
                        multiplePeople,
                    ).toMutableMap()
                if (item != null && description != null) {
                    fields.remove("note")
                    if (description.note != expectedNote) {
                        fields += payload("note" to description.note, "expectedNote" to expectedNote)
                    }
                }
                if (description != null && description.blocks != expectedDescription.takeIf { item != null }.orEmpty()) {
                    fields += payload("description" to description.blocks.map { it.toPayload() })
                    if (item != null) fields += payload("expectedDescription" to expectedDescription.map { it.toPayload() })
                }
                if (hasSubchecklist && (changingSubitems || item == null && subitems.isNotEmpty())) {
                    fields += payload("subitems" to subitems.map { it.toPayload() })
                    if (item != null) fields += payload("expectedSubitems" to expectedSubitems.map { it.toPayload() })
                }
                model.action(
                    if (multiplePeople) "${action}WithPeople" else action,
                    JsonObject(fields),
                    onSuccess = {
                        if (item == null && editor.initialText.isNotEmpty()) model.ui.itemCapture.remove(list.id)
                        model.showSavedItem(list.id, item?.id)
                        dismiss()
                    },
                )
            }
            if (item != null) {
                Box {
                    TextButton(onClick = { actions = true }, enabled = enabled) { Text("Item actions") }
                    DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit order") },
                            onClick = {
                                actions = false
                                if (multiplePeople) {
                                    model.ui.itemSelections.remove(list.id)
                                    model.ui.reorderListId = list.id
                                    model.ui.itemToReveal[list.id] = item.id
                                    dismiss()
                                } else if (model.ui.filters[list.id]?.active == true) {
                                    confirm(Confirmation("Edit order", "Show all items to edit their order.", "Show all items") {
                                        model.ui.filters.remove(list.id)
                                        model.ui.hiddenSavedItems.remove(list.id)
                                        model.ui.itemToReveal[list.id] = item.id
                                        select(ItemPanel.Order)
                                    })
                                } else select(ItemPanel.Order)
                            },
                            enabled = enabled,
                        )
                        DropdownMenuItem(text = { Text("Duplicate item") }, onClick = {
                            actions = false
                            duplicating = true
                            subitems = subitems.map { it.copy(id = UUID.randomUUID().toString(), checked = false) }
                        })
                        if (organization)
                            DropdownMenuItem(text = { Text("Duplicate for someone else") }, onClick = {
                                actions = false
                                duplicating = true
                                needsPerson = true
                                subitems = subitems.map { it.copy(id = UUID.randomUUID().toString(), checked = false) }
                                recipients = emptyList()
                                newPeople = emptyList()
                                select(ItemPanel.Person)
                            })
                        DropdownMenuItem(
                            text = { Text("Delete item", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                actions = false
                                confirm(
                                    Confirmation(
                                        "Delete this item?",
                                        "“${item.text}” will be removed${if (list.shared) " for everyone" else ""}.",
                                        "Delete item",
                                        true,
                                    ) {
                                        model.action("item.delete", list.orderedPayload("itemId" to item.id), onSuccess = dismiss)
                                    }
                                )
                            },
                            enabled = enabled,
                        )
                    }
                }
            }
            TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

@Composable
internal fun DetailChoice(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
) = DetailChoice(title, summary, true, onClick)

@Composable
internal fun DetailChoice(title: String, summary: String?, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 7.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
    }
}

@Composable
internal fun SelectionRow(
    label: String,
    selected: Boolean,
    single: Boolean = false,
    enabled: Boolean = true,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val action = if (single) Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        else Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(action),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (single) RadioButton(selected, onClick = null, enabled = enabled)
        else Checkbox(selected, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp))
        if (trailing != null)
            Text(trailing, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
internal fun DoneButton(done: () -> Unit) {
    Button(onClick = done, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
internal fun DraftWarning(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
