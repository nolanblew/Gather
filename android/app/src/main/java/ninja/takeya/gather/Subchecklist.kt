package ninja.takeya.gather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
internal fun SubchecklistEditor(
    steps: List<Subitem>,
    enabled: Boolean,
    change: (List<Subitem>) -> Unit,
) {
    var editing by remember { mutableStateOf<Subitem?>(null) }
    val completed = steps.count { it.checked }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Subchecklist", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (steps.isNotEmpty()) Text("$completed of ${steps.size} done", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        for ((index, step) in steps.withIndex()) key(step.id) {
            SubchecklistStep(
                step, enabled, toggle = { checked -> change(steps.map { if (it.id == step.id) step.copy(checked = checked) else it }) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                edit = { editing = step },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, "Options for ${step.text}") }
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
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
        }
        TextButton(
            onClick = { editing = Subitem(UUID.randomUUID().toString(), "") },
            modifier = Modifier.padding(horizontal = 4.dp),
            enabled = enabled && steps.size < MAX_SUBITEMS,
        ) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add step")
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
    edit: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            step.checked, toggle, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = if (step.checked) "Uncheck step ${step.text}" else "Check step ${step.text}" },
        )
        Text(
            step.text,
            Modifier.weight(1f).then(if (edit == null) Modifier else Modifier.clickable(enabled = enabled, onClick = edit)).padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (step.checked) TextDecoration.LineThrough else null,
            color = if (step.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        actions?.invoke()
    }
}
