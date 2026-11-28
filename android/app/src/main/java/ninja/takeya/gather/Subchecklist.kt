@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ninja.takeya.gather

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
internal fun SubchecklistEditor(
    steps: List<Subitem>,
    change: (List<Subitem>) -> Unit,
    draft: Subitem?,
    changeDraft: (Subitem?) -> Unit,
    enabled: Boolean,
) {
    var editing by remember { mutableStateOf<Subitem?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ItemEditorLabel("Subchecklist")
        Surface(
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = MaterialTheme.colorScheme.background,
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Column {
                    for ((index, step) in steps.withIndex()) key(step.id) {
                        SubchecklistStep(
                            step, enabled, toggle = { checked -> change(steps.map { if (it.id == step.id) step.copy(checked = checked) else it }) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp),
                            compact = true,
                            edit = { editing = step },
                            actions = {
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(38.dp)) {
                                        Icon(Icons.Outlined.MoreVert, "Options for ${step.text}", Modifier.size(18.dp))
                                    }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("Edit step") }, onClick = { menu = false; editing = step })
                                        if (index > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = {
                                            menu = false
                                            change(steps.toMutableList().apply { add(index - 1, removeAt(index)) })
                                        })
                                        if (index < steps.lastIndex) DropdownMenuItem(text = { Text("Move down") }, onClick = {
                                            menu = false
                                            change(steps.toMutableList().apply { add(index + 1, removeAt(index)) })
                                        })
                                        DropdownMenuItem(text = { Text("Remove step") }, onClick = {
                                            menu = false
                                            change(steps.filterNot { it.id == step.id })
                                        })
                                    }
                                }
                            },
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (draft == null) {
                        TextButton(
                            onClick = { changeDraft(Subitem(UUID.randomUUID().toString(), "")) },
                            modifier = Modifier.heightIn(min = 37.dp).padding(horizontal = 3.dp),
                            enabled = enabled && steps.size < MAX_SUBITEMS,
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 5.dp),
                        ) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add step")
                        }
                    } else {
                        val focus = remember(draft.id) { FocusRequester() }
                        val keyboard = LocalSoftwareKeyboardController.current
                        LaunchedEffect(focus) {
                            focus.requestFocus()
                            keyboard?.show()
                        }
                        val save = {
                            if (draft.text.isNotBlank()) {
                                change(steps + draft.copy(text = draft.text.trim()))
                                changeDraft(null)
                                keyboard?.hide()
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(start = 14.dp, end = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicTextField(
                                draft.text, { changeDraft(draft.copy(text = it.take(500))) },
                                modifier = Modifier.weight(1f).focusRequester(focus).testTag("field-Step")
                                    .semantics { contentDescription = "Step" },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { save() }),
                                decorationBox = { inner ->
                                    Box {
                                        if (draft.text.isEmpty()) Text("Step", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                        inner()
                                    }
                                },
                            )
                            IconButton(onClick = save, enabled = enabled && draft.text.isNotBlank(), modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Outlined.Check, "Save step", Modifier.size(20.dp))
                            }
                            IconButton(onClick = { changeDraft(null); keyboard?.hide() }, modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Outlined.Close, "Cancel step", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { step ->
        val existing = steps.any { it.id == step.id }
        var text by remember(step.id) { mutableStateOf(step.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (existing) "Edit step" else "Add step") },
            text = { FormText(text, { text = it }, "Step", 500, single = false, minLines = 1) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val replacement = step.copy(text = text.trim())
                    change(if (existing) steps.map { if (it.id == step.id) replacement else it } else steps + replacement)
                    editing = null
                }) { Text(if (existing) "Apply" else "Add step") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SubchecklistStep(
    step: Subitem,
    enabled: Boolean,
    toggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    edit: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            step.checked, toggle, enabled = enabled,
            modifier = (if (compact) Modifier.size(36.dp) else Modifier).semantics { contentDescription = if (step.checked) "Uncheck step ${step.text}" else "Check step ${step.text}" },
        )
        Text(
            step.text,
            Modifier.weight(1f).then(if (edit == null) Modifier else Modifier.clickable(enabled = enabled, onClick = edit)).padding(vertical = if (compact) 5.dp else 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (step.checked && !compact) TextDecoration.LineThrough else null,
            color = if (step.checked && !compact) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        actions?.invoke()
    }
}
