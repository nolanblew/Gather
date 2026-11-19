package ninja.takeya.gather

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private val LazyListItemInfo.center: Float get() = offset + size / 2f

internal class ItemDragState(private val scroll: LazyListState) {
    private data class Handle(val entry: DetailEntry.Row, val bounds: Rect)

    private val handles = mutableMapOf<String, Handle>()
    var rootPosition = Offset.Zero
    private var active by mutableStateOf<DetailEntry.Row?>(null)
    private var pointerY by mutableFloatStateOf(0f)
    private var startOffset = 0
    private var eligibleKeys = emptySet<String>()

    val activeKey get() = active?.key

    fun handle(key: String, entry: DetailEntry.Row, bounds: Rect) {
        handles[key] = Handle(entry, bounds)
    }

    fun removeHandle(key: String) {
        handles.remove(key)
    }

    fun handleAt(position: Offset): DetailEntry.Row? {
        val point = rootPosition + position
        return scroll.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info ->
            handles[info.key as? String]?.takeIf { it.bounds.contains(point) }?.entry
        }
    }

    fun start(entry: DetailEntry.Row, downY: Float) {
        val info = scroll.layoutInfo.visibleItemsInfo.find { it.key == entry.key } ?: return
        startOffset = downY.toInt() + scroll.layoutInfo.viewportStartOffset - info.center.toInt()
        pointerY = downY
        eligibleKeys = entry.bucket.rows.mapTo(hashSetOf(), entry::siblingKey)
        active = entry
    }

    fun update(y: Float) {
        pointerY = y
    }

    private val draggedCenter get() = pointerY + scroll.layoutInfo.viewportStartOffset - startOffset

    fun translation(key: String): Float {
        if (activeKey != key) return 0f
        val info = scroll.layoutInfo.visibleItemsInfo.find { it.key == key } ?: return 0f
        return draggedCenter - info.center
    }

    fun targetKey(): String? {
        val source = active ?: return null
        val center = draggedCenter
        return (scroll.layoutInfo.visibleItemsInfo.minByOrNull { abs(it.center - center) }?.key as? String)
            ?.takeIf { it != source.key && it in eligibleKeys }
    }

    fun scrollSpeed(edge: Float): Float {
        val layout = scroll.layoutInfo
        val fromTop = pointerY
        val fromBottom = layout.viewportEndOffset - layout.viewportStartOffset - pointerY
        return when {
            fromTop < edge -> ((fromTop - edge) / edge).coerceAtLeast(-1f)
            fromBottom < edge -> ((edge - fromBottom) / edge).coerceAtMost(1f)
            else -> 0f
        }
    }

    fun finish() {
        active = null
    }
}

@Composable
internal fun Modifier.itemDragInput(
    drag: ItemDragState,
    scroll: LazyListState,
    enabled: Boolean,
    orderToken: String?,
    onDrop: (DetailEntry.Row, String, String) -> Unit,
): Modifier {
    val currentToken by rememberUpdatedState(orderToken)
    val currentDrop by rememberUpdatedState(onDrop)
    val edgeSize = with(LocalDensity.current) { 64.dp.toPx() }
    val maxSpeed = with(LocalDensity.current) { 18.dp.toPx() }
    LaunchedEffect(drag.activeKey) {
        while (drag.activeKey != null) {
            withFrameNanos { }
            val speed = drag.scrollSpeed(edgeSize)
            if (speed != 0f) scroll.scrollBy(speed * maxSpeed)
        }
    }
    if (!enabled) return this
    return onGloballyPositioned { drag.rootPosition = it.positionInRoot() }
        .pointerInput(drag) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val entry = drag.handleAt(down.position) ?: return@awaitEachGesture
                val token = currentToken ?: return@awaitEachGesture
                if (entry.bucket.itemIds.size < 2) return@awaitEachGesture
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.find { it.id == down.id } ?: break
                        if (!change.pressed) {
                            val target = drag.targetKey()
                            drag.finish()
                            if (target != null) {
                                change.consume()
                                currentDrop(entry, target, token)
                            }
                            break
                        }
                        if (drag.activeKey == null && abs(change.position.y - down.position.y) > viewConfiguration.touchSlop) {
                            drag.start(entry, down.position.y)
                        }
                        if (drag.activeKey != null) {
                            change.consume()
                            drag.update(change.position.y)
                        }
                    }
                } finally {
                    drag.finish()
                }
            }
        }
}

@Composable
internal fun ItemDragHandle(entry: DetailEntry.Row, drag: ItemDragState, enabled: Boolean) {
    val text = entry.row.item.text
    DisposableEffect(entry.key, drag) {
        onDispose { drag.removeHandle(entry.key) }
    }
    Box(
        Modifier.size(48.dp).onGloballyPositioned { drag.handle(entry.key, entry, it.boundsInRoot()) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.DragIndicator,
            "Drag $text",
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f),
        )
    }
}

@Composable
internal fun ItemReorderMenu(
    text: String,
    enabled: Boolean,
    moveUp: (() -> Unit)?,
    moveDown: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Outlined.MoreHoriz, "Reorder options for $text")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Move up") },
                leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) },
                enabled = enabled && moveUp != null,
                onClick = {
                    expanded = false
                    moveUp?.invoke()
                },
            )
            DropdownMenuItem(
                text = { Text("Move down") },
                leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) },
                enabled = enabled && moveDown != null,
                onClick = {
                    expanded = false
                    moveDown?.invoke()
                },
            )
        }
    }
}
