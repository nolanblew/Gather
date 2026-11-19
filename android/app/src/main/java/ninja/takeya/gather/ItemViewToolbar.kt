@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ninja.takeya.gather

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun ItemViewToolbar(
    query: String,
    onQuery: (String) -> Unit,
    visibleCount: Int,
    selectedCount: Int?,
    reordering: Boolean,
    grouping: ItemGrouping,
    writable: Boolean,
    openView: () -> Unit,
    startSelection: () -> Unit,
    finishMode: () -> Unit,
    openGroupOrder: (() -> Unit)?,
    openBulk: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            reordering -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Manual order", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Button(onClick = finishMode) { Text("Done") }
                }
                if (visibleCount > 1) Text(
                    "Drag to reorder within a group.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (openGroupOrder != null) {
                    TextButton(onClick = openGroupOrder, enabled = writable) {
                        Icon(Icons.Outlined.SwapVert, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reorder groups")
                    }
                }
            }
            selectedCount != null -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("$selectedCount selected", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Button(onClick = finishMode) { Text("Done") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openBulk(true) }, enabled = selectedCount > 0 && writable) {
                        Text("Add places")
                    }
                    OutlinedButton(onClick = { openBulk(false) }, enabled = selectedCount > 0 && writable) {
                        Text("Add people")
                    }
                }
            }
            else -> {
                if (visibleCount > 0 || query.isNotBlank()) {
                    OutlinedTextField(
                        query,
                        { onQuery(it.take(100)) },
                        label = { Text("Filter items") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = if (query.isBlank()) null else {
                            { IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear item search") } }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("filter-items"),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = openView) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("View")
                    }
                    OutlinedButton(onClick = startSelection, enabled = visibleCount > 0 && writable) {
                        Icon(Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Select items")
                    }
                }
                if (grouping != ItemGrouping.None) {
                    val itemCount = if (visibleCount == 1) "1 item" else "$visibleCount items"
                    Text(
                        "Grouped by ${grouping.label.lowercase()} · $itemCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
