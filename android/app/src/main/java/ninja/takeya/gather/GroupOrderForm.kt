package ninja.takeya.gather

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

private data class GroupOrderSnapshot(val stored: List<String>, val realized: List<String>) {
    fun move(id: String, target: String): List<String>? {
        val moved = movedItemIds(realized, id, target) ?: return null
        val realizedKeys = realized.toSet()
        val reordered = moved.iterator()
        return (stored + (realized - stored.toSet())).map { key ->
            if (key in realizedKeys) reordered.next() else key
        }
    }
}

@Composable
internal fun ColumnScope.GroupOrderForm(
    editor: Editor.GroupOrder,
    state: GatherState,
    model: GatherViewModel,
    dismiss: () -> Unit,
) {
    val list = state.workspace.lists.find { it.id == editor.listId }
    val kind = editor.grouping.wireKind
    SheetHeader("Reorder groups", if (list?.shared == true) "Changes apply to everyone on this list." else "")
    if (list == null || kind == null) {
        DraftWarning("These groups are no longer available.")
        DoneButton(dismiss)
        return
    }
    val groups = remember(list, editor.grouping) { list.realizedGroupLabels(editor.grouping) }
    val snapshot = remember(list, groups) {
        GroupOrderSnapshot(list.storedGroupOrder(editor.grouping), groups.map { it.id })
    }
    val keys = remember(snapshot.realized) { snapshot.realized.toSet() }
    val writable = state.canEditWorkspace && !state.busy
    val canMove = writable && groups.size > 1
    val scroll = rememberLazyListState()
    val drag = remember(scroll) { ReorderDragState<String>(scroll) }
    val target by remember(drag) { derivedStateOf { drag.targetKey() } }
    var changedDuringDrag by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)

    fun save(ids: List<String>, from: GroupOrderSnapshot) {
        if (!writable) return
        changedDuringDrag = false
        model.action(
            "group.reorder",
            payload(
                "listId" to list.id,
                "kind" to kind,
                "groupIds" to ids,
                "expectedGroupIds" to from.stored,
            ),
        )
    }
    fun move(id: String, destination: String, from: GroupOrderSnapshot) {
        changedDuringDrag = from != snapshot
        if (!changedDuringDrag) from.move(id, destination)?.let { save(it, from) }
    }

    if (changedDuringDrag) DraftWarning("The groups changed. Review the latest order and try again.")
    if (groups.isEmpty()) Text("No groups to reorder.", style = MaterialTheme.typography.bodyMedium)
    else LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
            .reorderDragInput(drag, canMove, snapshot) { source, destination, from ->
                move(source, destination, from)
            },
        state = drag.scroll,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(groups, key = { _, group -> group.id }) { index, group ->
            val active = drag.activeKey == group.id
            val highlighted = active || target == group.id
            val above = groups.getOrNull(index - 1)
            val below = groups.getOrNull(index + 1)
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .animateItem(
                        fadeInSpec = GatherMotion.quick(),
                        placementSpec = GatherMotion.standard(),
                        fadeOutSpec = GatherMotion.quick(),
                    )
                    .then(
                        if (active) Modifier.zIndex(1f).graphicsLayer {
                            translationY = drag.translation(group.id)
                            shadowElevation = 5.dp.toPx()
                            this.shape = shape
                        } else Modifier,
                    ),
                shape = shape,
                border = BorderStroke(
                    1.dp,
                    if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReorderDragHandle(ReorderDragEntry(group.id, group.id) { keys }, drag, group.title, canMove)
                    Text(group.title, Modifier.weight(1f).padding(vertical = 8.dp))
                    IconButton(
                        onClick = { above?.let { move(group.id, it.id, snapshot) } },
                        enabled = canMove && above != null,
                    ) { Icon(Icons.Outlined.ArrowUpward, "Move ${group.title} up") }
                    IconButton(
                        onClick = { below?.let { move(group.id, it.id, snapshot) } },
                        enabled = canMove && below != null,
                    ) { Icon(Icons.Outlined.ArrowDownward, "Move ${group.title} down") }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        if (snapshot.stored.isNotEmpty()) {
            TextButton(onClick = { save(emptyList(), snapshot) }, enabled = writable, modifier = Modifier.weight(1f)) {
                Text("Reset order")
            }
        }
        Button(onClick = dismiss, enabled = !state.busy) { Text("Done") }
    }
}
