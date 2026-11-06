@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ninja.takeya.gather

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@Composable
fun EditorSheet(
    editor: Editor,
    state: GatherState,
    model: GatherViewModel,
    onDismiss: () -> Unit,
    onConfirm: (Confirmation) -> Unit,
) {
    key(editor, if (editor == Editor.Capture) state.captureId else null) {
        val window = LocalGatherWindow.current
        val dialog = window.width >= 600.dp || window.height < 480.dp || window.folds.isNotEmpty()
        val itemEditor = editor is Editor.ItemForm
        val maximumFormWidth = if (itemEditor) 440.dp else 560.dp
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val visibility = remember { Animatable(0f) }
        LaunchedEffect(dialog) { if (dialog) visibility.animateTo(1f, GatherMotion.standard()) }
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        val currentDismiss by rememberUpdatedState(onDismiss)
        var closing by remember { mutableStateOf(false) }
        val dismiss: () -> Unit = {
            if (!closing) {
                closing = true
                focusManager.clearFocus()
                scope.launch {
                    try {
                        if (dialog) visibility.animateTo(0f, GatherMotion.standard())
                        else sheetState.hide()
                        currentDismiss()
                    } finally {
                        closing = false
                    }
                }
            }
        }
        val form: @Composable () -> Unit = {
            CompositionLocalProvider(LocalEditorDraft provides model.ui.editorDraft) {
                Column(
                    Modifier.widthIn(max = maximumFormWidth)
                        .fillMaxWidth()
                        .gatherPageInput(!closing)
                        .verticalScroll(model.ui.editorScroll)
                        .padding(start = 24.dp, end = 24.dp, top = 0.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (itemEditor) 10.dp else 15.dp),
                ) {
                    if (state.error != null) Notice(state, model)
                    when (editor) {
                        is Editor.ListForm -> ListForm(editor.list, state, model, dismiss)
                        is Editor.ItemForm -> ItemForm(editor, state, model, dismiss, onConfirm)
                        is Editor.SectionForm ->
                            SectionForm(editor, state, model, dismiss, onConfirm)
                        is Editor.CategoryForm ->
                            CategoryForm(editor.category, state, model, dismiss, onConfirm)
                        is Editor.Share -> ShareForm(editor.list, state, model, dismiss, onConfirm)
                        Editor.Password -> PasswordForm(state, model, dismiss)
                        Editor.Capture -> CaptureForm(state, model, dismiss)
                    }
                }
            }
        }
        if (dialog) {
            Dialog(
                onDismissRequest = { if (!state.busy) dismiss() },
                properties =
                    DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
            ) {
                GatherDialogRegion(onDismiss = { if (!state.busy) dismiss() }) { maximumHeight ->
                    Surface(
                        modifier =
                            Modifier.widthIn(max = maximumFormWidth)
                                .fillMaxWidth()
                                .heightIn(max = maximumHeight)
                                .graphicsLayer {
                                    alpha = visibility.value
                                    translationY = (1f - visibility.value) * 12.dp.toPx()
                                }
                                .testTag("editor-dialog")
                                .pointerInput(Unit) {
                                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                                },
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.background,
                        tonalElevation = 6.dp,
                    ) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                IconButton(
                                    onClick = dismiss,
                                    enabled = !state.busy && !closing,
                                    modifier = Modifier.testTag("editor-close"),
                                ) {
                                    Icon(Icons.Outlined.Close, "Close editor")
                                }
                            }
                            Box(Modifier.weight(1f, fill = false)) { form() }
                        }
                    }
                }
            }
        } else {
            ModalBottomSheet(
                onDismissRequest = { if (!state.busy) dismiss() },
                sheetState = sheetState,
                sheetMaxWidth = maximumFormWidth,
                containerColor = MaterialTheme.colorScheme.background,
                dragHandle = { BottomSheetDefaults.DragHandle() },
            ) {
                Box(Modifier.fillMaxWidth().imePadding(), contentAlignment = Alignment.TopCenter) {
                    form()
                }
            }
        }
    }
}

@Composable
fun SheetHeader(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle.isNotBlank())
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
fun FormText(
    value: String,
    change: (String) -> Unit,
    label: String,
    max: Int,
    single: Boolean = true,
    enabled: Boolean = true,
    minLines: Int = if (single) 1 else 3,
    maxLines: Int = if (single) 1 else 8,
) {
    OutlinedTextField(
        value,
        { change(it.take(max)) },
        label = { Text(label) },
        modifier =
            Modifier.fillMaxWidth()
                .testTag(
                    when (label) {
                        "List name" -> "editor-title"
                        "Item" -> "editor-item-text"
                        else -> "field-$label"
                    }
                ),
        singleLine = single,
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(16.dp),
        enabled = enabled,
        supportingText =
            if (value.length > max * .8) {
                { Text("${value.length} / $max") }
            } else null,
    )
}

@Composable
fun Choice(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by
        animateFloatAsState(if (expanded) 180f else 0f, GatherMotion.quick(), label = "Choice menu")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    options.firstOrNull { it.first == value }?.second ?: value,
                    Modifier.weight(1f),
                )
                Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(arrowRotation))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (key, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            expanded = false
                            onChange(key)
                        },
                        trailingIcon = { if (key == value) Icon(Icons.Outlined.Check, null) },
                    )
                }
            }
        }
    }
}

@Composable
fun SaveButton(
    state: GatherState,
    label: String = "Save changes",
    enabled: Boolean = true,
    save: () -> Unit,
) {
    Button(
        onClick = save,
        enabled = enabled && state.connected && !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("editor-save"),
        shape = RoundedCornerShape(16.dp),
    ) {
        AnimatedContent(
            targetState = state.busy to label,
            transitionSpec = { gatherFade() },
            label = "Save action",
        ) { (busy, actionLabel) ->
            if (busy)
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            else Text(actionLabel)
        }
    }
}

@Composable
fun Palette(value: String, change: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Color",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("sage", "peach", "lavender", "sky", "sand", "rose").forEach { color ->
                Surface(
                    onClick = { change(color) },
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = tint(color),
                    border =
                        if (value == color) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        else null,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (value == color)
                            Icon(Icons.Outlined.Check, color.replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }
    }
}

@Composable
fun ListForm(list: GatherList?, state: GatherState, model: GatherViewModel, dismiss: () -> Unit) {
    var title by rememberEditorDraft("title") { list?.title ?: "" }
    var description by rememberEditorDraft("description") { list?.description ?: "" }
    var kind by rememberEditorDraft("kind") { list?.kind ?: "checklist" }
    var cadence by rememberEditorDraft("cadence") { list?.repeatCadence ?: "manual" }
    var color by rememberEditorDraft("color") { list?.color ?: "sage" }
    var icon by rememberEditorDraft("icon") { list?.icon ?: "list" }
    var category by rememberEditorDraft("category") { list?.categoryId ?: "" }
    LaunchedEffect(state.workspace.categories) {
        if (category.isNotBlank() && state.workspace.categories.none { it.id == category })
            category = ""
    }
    var template by
        rememberEditorDraft("template") { list?.isTemplate ?: (state.scope == "templates") }
    SheetHeader(
        if (list == null) "New list" else "List details",
        if (list?.owner == false)
            "You can edit this list’s content. Its owner manages the type and template settings."
        else "",
    )
    FormText(title, { title = it }, "List name", 200, enabled = !state.busy)
    FormText(description, { description = it }, "Description (optional)", 4000, false, !state.busy)
    Choice(
        "List type",
        kind,
        listOf("checklist" to "Checklist", "notes" to "Notes & ideas"),
        !state.busy && list?.owner != false,
    ) {
        kind = it
    }
    Choice(
        "Repeat label",
        cadence,
        listOf("manual" to "Manual", "daily" to "Daily", "weekly" to "Weekly"),
        !state.busy && list?.owner != false,
    ) {
        cadence = it
    }
    Text(
        "Repeat labels don’t reset lists automatically.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Choice(
        "Category",
        category,
        listOf("" to "Uncategorized") +
            state.workspace.categories.sortedBy { it.position }.map { it.id to it.name },
        !state.busy,
    ) {
        category = it
    }
    Palette(color) { color = it }
    Choice(
        "Icon",
        icon,
        listOf(
            "list" to "Everyday list",
            "basket" to "Shopping basket",
            "gift" to "Gift",
            "plane" to "Travel",
            "bulb" to "Idea",
            "home" to "Home",
            "briefcase" to "Work",
            "book" to "Reading",
            "heart" to "Favorites",
        ),
        !state.busy,
    ) {
        icon = it
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Keep as a template", style = MaterialTheme.typography.titleMedium)
            Text(
                "Create new lists from this template.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(template, { template = it }, enabled = !state.busy && list?.owner != false)
    }
    SaveButton(state, if (list == null) "Create list" else "Save changes", title.isNotBlank()) {
        val values =
            mutableListOf<Pair<String, Any?>>(
                "title" to title.trim(),
                "description" to description,
                "color" to color,
                "icon" to icon,
                "categoryId" to category.ifBlank { null },
            )
        if (list?.owner != false)
            values.addAll(
                listOf("kind" to kind, "repeatCadence" to cadence, "isTemplate" to template)
            )
        if (list != null) values.add("listId" to list.id)
        model.action(
            if (list == null) "list.create" else "list.update",
            payload(*values.toTypedArray()),
            list == null,
            dismiss,
        )
    }
    TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
}

@Composable
fun ItemForm(
    editor: Editor.ItemForm,
    state: GatherState,
    model: GatherViewModel,
    dismiss: () -> Unit,
    confirm: (Confirmation) -> Unit,
) {
    val list = state.workspace.lists.firstOrNull { it.id == editor.list.id } ?: editor.list
    val item = editor.item
    var text by rememberEditorDraft("text") { item?.text ?: "" }
    var quantity by rememberEditorDraft("quantity") { item?.quantity ?: "" }
    var note by rememberEditorDraft("note") { item?.note ?: "" }
    var section by rememberEditorDraft("section") { item?.sectionId ?: editor.sectionId ?: "" }
    LaunchedEffect(list.sections) {
        if (section.isNotBlank() && list.sections.none { it.id == section }) section = ""
    }
    SheetHeader(if (item == null) "Add item" else "Item details", list.title)
    FormText(text, { text = it }, "Item", 500, false, !state.busy, minLines = 1)
    FormText(quantity, { quantity = it }, "Quantity (optional)", 100, enabled = !state.busy)
    FormText(note, { note = it }, "Notes (optional)", 4000, false, !state.busy, minLines = 2)
    if (list.sections.isNotEmpty())
        Choice(
            "Section",
            section,
            listOf("" to "No section") +
                list.sections.sortedBy { it.position }.map { it.id to it.title },
            !state.busy,
        ) {
            section = it
        }
    SaveButton(state, if (item == null) "Add item" else "Save changes", text.isNotBlank()) {
        val values =
            mutableListOf<Pair<String, Any?>>(
                "listId" to list.id,
                "text" to text.trim(),
                "quantity" to quantity,
                "note" to note,
                "sectionId" to section.ifBlank { null },
            )
        if (item != null) values.add("itemId" to item.id)
        model.action(
            if (item == null) "item.create" else "item.update",
            payload(*values.toTypedArray()),
            onSuccess = dismiss,
        )
    }
    if (item != null) {
        val group = sortedGroup(list, item.sectionId)
        val index = group.indexOfFirst { it.id == item.id }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    model.action(
                        "item.move",
                        payload(
                            "listId" to list.id,
                            "itemId" to item.id,
                            "sectionId" to item.sectionId,
                            "index" to index - 1,
                        ),
                        onSuccess = dismiss,
                    )
                },
                enabled = index > 0 && !state.busy && state.connected,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(18.dp))
                Text("Move up")
            }
            OutlinedButton(
                onClick = {
                    model.action(
                        "item.move",
                        payload(
                            "listId" to list.id,
                            "itemId" to item.id,
                            "sectionId" to item.sectionId,
                            "index" to index + 1,
                        ),
                        onSuccess = dismiss,
                    )
                },
                enabled = index >= 0 && index < group.lastIndex && !state.busy && state.connected,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(18.dp))
                Text("Move down")
            }
        }
        TextButton(
            onClick = {
                confirm(
                    Confirmation(
                        "Delete this item?",
                        "“${item.text}” will be removed${if(list.shared)" for everyone" else ""}.",
                        "Delete item",
                        true,
                    ) {
                        model.action(
                            "item.delete",
                            payload("listId" to list.id, "itemId" to item.id),
                            onSuccess = dismiss,
                        )
                    }
                )
            },
            enabled = !state.busy && state.connected,
            colors =
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delete item")
        }
    }
    TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
}

@Composable
fun SectionForm(
    editor: Editor.SectionForm,
    state: GatherState,
    model: GatherViewModel,
    dismiss: () -> Unit,
    confirm: (Confirmation) -> Unit,
) {
    val list = state.workspace.lists.firstOrNull { it.id == editor.list.id } ?: editor.list
    val section = editor.section
    var title by rememberEditorDraft("title") { section?.title ?: "" }
    SheetHeader(if (section == null) "Add section" else "Section details", "")
    FormText(title, { title = it }, "Section name", 200, enabled = !state.busy)
    SaveButton(state, if (section == null) "Add section" else "Save changes", title.isNotBlank()) {
        model.action(
            if (section == null) "section.create" else "section.update",
            if (section == null) payload("listId" to list.id, "title" to title.trim())
            else payload("listId" to list.id, "sectionId" to section.id, "title" to title.trim()),
            onSuccess = dismiss,
        )
    }
    if (section != null) {
        val sections = list.sections.sortedBy { it.position }
        val index = sections.indexOfFirst { it.id == section.id }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    model.action(
                        "section.move",
                        payload(
                            "listId" to list.id,
                            "sectionId" to section.id,
                            "index" to index - 1,
                        ),
                        onSuccess = dismiss,
                    )
                },
                enabled = index > 0 && !state.busy && state.connected,
                modifier = Modifier.weight(1f),
            ) {
                Text("Move up")
            }
            OutlinedButton(
                onClick = {
                    model.action(
                        "section.move",
                        payload(
                            "listId" to list.id,
                            "sectionId" to section.id,
                            "index" to index + 1,
                        ),
                        onSuccess = dismiss,
                    )
                },
                enabled = index < sections.lastIndex && !state.busy && state.connected,
                modifier = Modifier.weight(1f),
            ) {
                Text("Move down")
            }
        }
        TextButton(
            onClick = {
                confirm(
                    Confirmation(
                        "Remove this section?",
                        "Its items stay and move to the unsectioned group.",
                        "Remove section",
                    ) {
                        model.action(
                            "section.delete",
                            payload("listId" to list.id, "sectionId" to section.id),
                            onSuccess = dismiss,
                        )
                    }
                )
            },
            enabled = !state.busy && state.connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Remove section")
        }
    }
    TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
}

@Composable
fun CategoryForm(
    category: Category?,
    state: GatherState,
    model: GatherViewModel,
    dismiss: () -> Unit,
    confirm: (Confirmation) -> Unit,
) {
    var name by rememberEditorDraft("name") { category?.name ?: "" }
    var color by rememberEditorDraft("color") { category?.color ?: "sage" }
    SheetHeader(
        if (category == null) "New category" else "Category details",
        "Categories are personal to you.",
    )
    FormText(name, { name = it }, "Category name", 80, enabled = !state.busy)
    Palette(color) { color = it }
    SaveButton(
        state,
        if (category == null) "Create category" else "Save changes",
        name.isNotBlank(),
    ) {
        model.action(
            if (category == null) "category.create" else "category.update",
            if (category == null) payload("name" to name.trim(), "color" to color)
            else payload("categoryId" to category.id, "name" to name.trim(), "color" to color),
            onSuccess = dismiss,
        )
    }
    if (category != null)
        TextButton(
            onClick = {
                confirm(
                    Confirmation(
                        "Remove this category?",
                        "Your lists stay and become uncategorized.",
                        "Remove category",
                    ) {
                        model.action(
                            "category.delete",
                            payload("categoryId" to category.id),
                            onSuccess = {
                                model.category(null)
                                dismiss()
                            },
                        )
                    }
                )
            },
            enabled = !state.busy && state.connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Remove category")
        }
    TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
}

@Composable
fun ShareForm(
    list: GatherList,
    state: GatherState,
    model: GatherViewModel,
    dismiss: () -> Unit,
    confirm: (Confirmation) -> Unit,
) {
    val sharing = state.sharing
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    SheetHeader("Share list", list.title)
    Text(
        "Item edits and checkmarks are shared. Favorites, categories, and archive stay personal.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (sharing == null) {
        if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
        else OutlinedButton(onClick = { model.loadSharing(list.id) }) { Text("Try again") }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.PersonOutline, null)
                Column {
                    Text(sharing.ownerUsername, style = MaterialTheme.typography.titleMedium)
                    Text("Owner", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        sharing.members.forEach { member ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(member.username, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Editor",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sharing.owner)
                    TextButton(
                        onClick = {
                            confirm(
                                Confirmation(
                                    "Remove ${member.username}?",
                                    "They’ll lose access immediately. Their edits stay.",
                                    "Remove person",
                                    true,
                                ) {
                                    model.sharingAction("remove", list.id, member.userId)
                                }
                            )
                        },
                        enabled = !state.busy && state.connected,
                    ) {
                        Text("Remove")
                    }
            }
        }
        if (sharing.owner) {
            Text("Invite one person", style = MaterialTheme.typography.titleLarge)
            Text(
                "Each link works once and expires after seven days. Making a new link stops the previous pending one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sharing.token?.let {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Invitation ready", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Share it with someone you trust to edit this list.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    model.invitationLink(list.id)?.let { currentLink ->
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, currentLink)
                                                },
                                                "Share Gather invitation",
                                            )
                                        )
                                    }
                                },
                                enabled = !state.busy && state.connected,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share link")
                            }
                            OutlinedButton(
                                onClick = {
                                    model.invitationLink(list.id)?.let { currentLink ->
                                        clipboard.setText(AnnotatedString(currentLink))
                                        model.report("Invitation link copied.")
                                    }
                                },
                                enabled = !state.busy && state.connected,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Copy link")
                            }
                        }
                    }
                }
            }
            SaveButton(
                state,
                if (sharing.inviteActive) "Make a new link" else "Create invitation",
            ) {
                if (sharing.inviteActive)
                    confirm(
                        Confirmation(
                            "Replace the pending invitation?",
                            "The previous link will stop working. People who already joined keep access.",
                            "Make new link",
                        ) {
                            model.sharingAction("invite", list.id)
                        }
                    )
                else model.sharingAction("invite", list.id)
            }
            if (sharing.inviteActive)
                TextButton(
                    onClick = {
                        confirm(
                            Confirmation(
                                "Revoke this invitation?",
                                "The pending link will stop working. Accepted members keep access.",
                                "Revoke link",
                            ) {
                                model.sharingAction("revoke", list.id)
                            }
                        )
                    },
                    enabled = !state.busy && state.connected,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Revoke pending link")
                }
        } else {
            Text(
                "You can edit content and make a private copy. ${sharing.ownerUsername} manages invitations and people.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = {
                    confirm(
                        Confirmation(
                            "Leave this list?",
                            "Your edits stay. You’ll need a new invitation to return.",
                            "Leave list",
                            true,
                        ) {
                            model.sharingAction("leave", list.id)
                            dismiss()
                        }
                    )
                },
                enabled = !state.busy && state.connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Leave list")
            }
        }
    }
    TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Done")
    }
}

@Composable
fun PasswordForm(state: GatherState, model: GatherViewModel, dismiss: () -> Unit) {
    var current by rememberEditorDraft("current") { "" }
    var replacement by rememberEditorDraft("replacement") { "" }
    var repeat by rememberEditorDraft("repeat") { "" }
    var submitted by rememberEditorDraft("submitted") { false }
    LaunchedEffect(state.message) {
        if (submitted && state.message?.startsWith("Password changed") == true) dismiss()
    }
    SheetHeader(
        "Change password",
        "Changing it signs out your other devices. Your lists and sharing stay.",
    )
    listOf(
            Triple("Current password", current, { s: String -> current = s }),
            Triple("New password", replacement, { s: String -> replacement = s }),
            Triple("Repeat new password", repeat, { s: String -> repeat = s }),
        )
        .forEach { (label, value, change) ->
            OutlinedTextField(
                value,
                { change(it.take(128)) },
                label = { Text(label) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                enabled = !state.busy,
            )
        }
    Text(
        "At least 6 characters using two of: uppercase, lowercase, numbers, or symbols.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (repeat.isNotEmpty() && repeat != replacement)
        Text(
            "Passwords don’t match.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    SaveButton(
        state,
        "Change password",
        current.isNotBlank() && replacement.length >= 6 && repeat == replacement,
    ) {
        submitted = true
        model.changePassword(current, replacement)
    }
    TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
}

@Composable
fun CaptureForm(state: GatherState, model: GatherViewModel, dismiss: () -> Unit) {
    var text by rememberEditorDraft("text") { state.capture?.take(500) ?: "" }
    var note by rememberEditorDraft("note") { state.capture?.drop(500) ?: "" }
    var selected by
        rememberEditorDraft("selected") {
            state.workspace.lists.firstOrNull { !it.archived && !it.isTemplate }?.id ?: ""
        }
    val choices =
        state.workspace.lists
            .filter { !it.archived && !it.isTemplate }
            .sortedBy { it.position }
            .map { it.id to it.title }
    LaunchedEffect(choices) { if (choices.none { it.first == selected }) selected = "" }
    SheetHeader("Add shared text", "Save shared text to a list.")
    if (choices.isEmpty()) Text("Create a list first, then share the text again.")
    else {
        Choice("Save to", selected, listOf("" to "Choose a list") + choices, !state.busy) {
            selected = it
        }
        FormText(text, { text = it }, "Item", 500, false, !state.busy)
        FormText(note, { note = it }, "Notes (optional)", 4000, false, !state.busy)
        SaveButton(
            state,
            "Add to list",
            text.isNotBlank() && choices.any { it.first == selected },
        ) {
            model.action(
                "item.create",
                payload("listId" to selected, "text" to text.trim(), "note" to note),
                onSuccess = dismiss,
            )
        }
    }
    TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Not now")
    }
}
