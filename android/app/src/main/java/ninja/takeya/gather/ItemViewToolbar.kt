@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ninja.takeya.gather

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ItemViewModeControls(
    visibleCount: Int,
    selectedCount: Int?,
    reordering: Boolean,
    writable: Boolean,
    finishMode: () -> Unit,
    openGroupOrder: (() -> Unit)?,
    openBulk: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
}
