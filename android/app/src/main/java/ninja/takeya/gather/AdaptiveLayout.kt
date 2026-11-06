package ninja.takeya.gather

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.window.layout.FoldingFeature

enum class GatherNavigation {
    Bottom,
    Rail,
    Drawer,
}

data class GatherFold(
    val left: Dp,
    val top: Dp,
    val right: Dp,
    val bottom: Dp,
    val vertical: Boolean,
)

data class GatherRegion(val x: Dp, val y: Dp, val width: Dp, val height: Dp)

/** Dimensions are the current safe app window, never the physical display or a device name. */
data class GatherWindow(
    val width: Dp = 0.dp,
    val height: Dp = 0.dp,
    val folds: List<GatherFold> = emptyList(),
    val originInWindow: Offset = Offset.Zero,
) {
    val verticalFold
        get() = folds.firstOrNull { it.vertical && it.left > 0.dp && it.right < width }

    val horizontalFold
        get() = folds.firstOrNull { !it.vertical && it.top > 0.dp && it.bottom < height }

    val navigation: GatherNavigation
        get() =
            when {
                width >= 1200.dp && height >= 480.dp && verticalFold == null ->
                    GatherNavigation.Drawer
                width >= 600.dp || (height < 480.dp && width >= 480.dp) -> GatherNavigation.Rail
                else -> GatherNavigation.Bottom
            }

    val regions: List<GatherRegion>
        get() {
            val vertical = verticalFold
            val horizontal = horizontalFold
            return when {
                vertical != null ->
                    listOf(
                        GatherRegion(0.dp, 0.dp, vertical.left, height),
                        GatherRegion(vertical.right, 0.dp, width - vertical.right, height),
                    )
                horizontal != null ->
                    listOf(
                        GatherRegion(0.dp, 0.dp, width, horizontal.top),
                        GatherRegion(0.dp, horizontal.bottom, width, height - horizontal.bottom),
                    )
                else -> listOf(GatherRegion(0.dp, 0.dp, width, height))
            }
        }

    val primaryRegion
        get() = regions.maxBy { it.width.value * it.height.value }
}

val LocalGatherWindow = staticCompositionLocalOf { GatherWindow() }

/** Only the keyboard overlapping this pane is relevant to descendant imePadding. */
@Composable
internal fun GatherInsetPane(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val window = LocalGatherWindow.current
    val density = LocalDensity.current
    var bottomGap by remember { mutableStateOf(0.dp) }
    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                bottomGap =
                    with(density) {
                        val safeBottom = window.originInWindow.y + window.height.toPx()
                        (safeBottom - coordinates.positionInWindow().y - coordinates.size.height)
                            .coerceAtLeast(0f)
                            .toDp()
                    }
            }
            .consumeWindowInsets(PaddingValues(bottom = bottomGap)),
        contentAlignment = Alignment.TopCenter,
    ) {
        content()
    }
}

/** Full-window modal host positions its surface and touch bounds in one safe physical region. */
@Composable
internal fun GatherDialogRegion(onDismiss: () -> Unit, content: @Composable (Dp) -> Unit) {
    val window = LocalGatherWindow.current
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .imePadding()
    ) {
        val region =
            window.regions
                .mapNotNull {
                    val width = minOf(it.width, maxWidth - it.x)
                    val height = minOf(it.height, maxHeight - it.y)
                    if (width > 0.dp && height > 0.dp) it.copy(width = width, height = height)
                    else null
                }
                .maxByOrNull { it.width.value * it.height.value }
                ?: GatherRegion(0.dp, 0.dp, maxWidth, maxHeight)
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.AbsoluteAlignment.TopLeft,
        ) {
            Box(
                Modifier.matchParentSize().pointerInput(onDismiss) {
                    detectTapGestures { onDismiss() }
                }
            )
            Box(
                Modifier.absoluteOffset(region.x, region.y)
                    .size(region.width, region.height)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                content((region.height - 32.dp).coerceAtLeast(48.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GatherAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        GatherDialogRegion(onDismissRequest) { maximumHeight ->
            Surface(
                modifier =
                    Modifier.widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .heightIn(max = maximumHeight)
                        .testTag("gather-dialog")
                        .pointerInput(Unit) {
                            awaitPointerEventScope { while (true) awaitPointerEvent() }
                        },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (icon != null)
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                icon()
                            }
                        if (title != null)
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                        if (text != null)
                            CompositionLocalProvider(
                                LocalContentColor provides
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                            }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (dismissButton != null) dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}

@Composable
fun GatherWindowFrame(folds: List<FoldingFeature>, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .onGloballyPositioned { origin = it.positionInWindow() }
    ) {
        val windowFolds =
            folds
                .filter { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
                .map { fold ->
                    with(density) {
                        GatherFold(
                            (fold.bounds.left - origin.x).toDp().coerceIn(0.dp, maxWidth),
                            (fold.bounds.top - origin.y).toDp().coerceIn(0.dp, maxHeight),
                            (fold.bounds.right - origin.x).toDp().coerceIn(0.dp, maxWidth),
                            (fold.bounds.bottom - origin.y).toDp().coerceIn(0.dp, maxHeight),
                            fold.orientation == FoldingFeature.Orientation.VERTICAL,
                        )
                    }
                }
        CompositionLocalProvider(
            LocalGatherWindow provides GatherWindow(maxWidth, maxHeight, windowFolds, origin)
        ) {
            content()
        }
    }
}

/** Single surfaces (auth, settings) stay wholly within one unobstructed fold region. */
@Composable
fun GatherSafeSinglePane(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val window = LocalGatherWindow.current
    val region = window.primaryRegion
    Box(modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.AbsoluteAlignment.TopLeft) {
        Box(
            Modifier.absoluteOffset(region.x, region.y).size(region.width, region.height),
            contentAlignment = Alignment.TopCenter,
        ) {
            GatherInsetPane(Modifier.fillMaxSize()) { content() }
        }
    }
}

/** Pane splitting uses the content's own coordinates, after navigation and app-bar insets. */
@Composable
internal fun GatherPanes(
    modifier: Modifier = Modifier,
    allowSplit: Boolean = true,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    single: @Composable () -> Unit,
) {
    val root = LocalGatherWindow.current
    val density = LocalDensity.current
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() },
        contentAlignment = androidx.compose.ui.AbsoluteAlignment.TopLeft,
    ) {
        // Window features are relative to the safe root; subtract this content's displacement.
        val contentWidth = maxWidth
        val localX = with(density) { (origin.x - root.originInWindow.x).toDp() }
        val localY = with(density) { (origin.y - root.originInWindow.y).toDp() }
        val fontFactor = density.fontScale.coerceIn(1f, 1.5f)
        val minimumList = 260.dp * fontFactor
        val minimumDetail = 340.dp * fontFactor
        val vertical =
            root.verticalFold
                ?.let { it.copy(left = it.left - localX, right = it.right - localX) }
                ?.takeIf { it.left > 0.dp && it.right < maxWidth }
        val horizontal =
            root.horizontalFold
                ?.let { it.copy(top = it.top - localY, bottom = it.bottom - localY) }
                ?.takeIf { it.top > 0.dp && it.bottom < maxHeight }
        when {
            allowSplit &&
                vertical != null &&
                vertical.left >= (if (rightToLeft) minimumDetail else minimumList) &&
                maxWidth - vertical.right >= (if (rightToLeft) minimumList else minimumDetail) -> {
                Box(
                    Modifier.fillMaxSize().testTag("layout-list-detail"),
                    contentAlignment = androidx.compose.ui.AbsoluteAlignment.TopLeft,
                ) {
                    Box(
                        Modifier.width(vertical.left)
                            .fillMaxHeight()
                            .testTag(if (rightToLeft) "detail-pane" else "library-pane"),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        GatherInsetPane(Modifier.fillMaxSize()) {
                            if (rightToLeft) detail() else list()
                        }
                    }
                    Box(
                        Modifier.absoluteOffset(x = vertical.right)
                            .width(contentWidth - vertical.right)
                            .fillMaxHeight()
                            .testTag(if (rightToLeft) "library-pane" else "detail-pane"),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        GatherInsetPane(Modifier.fillMaxSize()) {
                            if (rightToLeft) list() else detail()
                        }
                    }
                }
            }
            allowSplit &&
                horizontal != null &&
                horizontal.top >= 220.dp &&
                maxHeight - horizontal.bottom >= 220.dp -> {
                Column(Modifier.fillMaxSize().testTag("layout-tabletop")) {
                    Box(
                        Modifier.height(horizontal.top).fillMaxWidth().testTag("library-pane"),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        GatherInsetPane(Modifier.fillMaxSize()) { list() }
                    }
                    Spacer(Modifier.height(horizontal.bottom - horizontal.top))
                    Box(
                        Modifier.weight(1f).fillMaxWidth().testTag("detail-pane"),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        GatherInsetPane(Modifier.fillMaxSize()) { detail() }
                    }
                }
            }
            vertical != null || horizontal != null -> {
                val local = GatherWindow(maxWidth, maxHeight, listOfNotNull(vertical, horizontal))
                val region = local.primaryRegion
                Box(
                    Modifier.absoluteOffset(region.x, region.y).size(region.width, region.height),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    GatherInsetPane(Modifier.fillMaxSize()) { single() }
                }
            }
            allowSplit && maxWidth >= minimumList + minimumDetail + 1.dp && maxHeight >= 400.dp -> {
                Row(
                    Modifier.widthIn(max = 1480.dp)
                        .fillMaxSize()
                        .align(Alignment.TopCenter)
                        .testTag("layout-list-detail")
                ) {
                    Box(
                        Modifier.width(
                                (contentWidth * .38f)
                                    .coerceIn(minimumList, contentWidth - minimumDetail - 1.dp)
                                    .coerceAtMost(420.dp * fontFactor)
                            )
                            .fillMaxHeight()
                            .testTag("library-pane"),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        GatherInsetPane(Modifier.fillMaxSize()) { list() }
                    }
                    androidx.compose.material3.VerticalDivider()
                    Box(
                        Modifier.weight(1f).fillMaxHeight().testTag("detail-pane"),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        GatherInsetPane(Modifier.fillMaxSize()) { detail() }
                    }
                }
            }
            else ->
                Box(
                    Modifier.fillMaxSize().testTag("layout-single"),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    GatherInsetPane(Modifier.fillMaxSize()) { single() }
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GatherWorkspaceScaffold(
    state: GatherState,
    settings: Boolean,
    onDestination: (String) -> Unit,
    onNewList: () -> Unit,
    topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val window = LocalGatherWindow.current
    val destinations =
        listOf(
            Triple("library", "Lists", Icons.AutoMirrored.Outlined.ListAlt),
            Triple("favorites", "Favorites", Icons.Outlined.StarOutline),
            Triple("templates", "Templates", Icons.Outlined.ContentCopy),
            Triple("settings", "Settings", Icons.Outlined.Tune),
        )
    val current = if (settings) "settings" else state.scope
    val navigationHeight = window.horizontalFold?.top ?: window.height
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val fontGrowth = (density.fontScale - 1f).coerceAtLeast(0f)
    val railLabelStyle = MaterialTheme.typography.labelMedium
    val drawerLabelStyle = LocalTextStyle.current
    val railLabelWidth =
        with(density) {
            destinations
                .maxOf { (_, label, _) ->
                    textMeasurer.measure(label, style = railLabelStyle, softWrap = false).size.width
                }
                .toDp()
        }
    val drawerLabelWidth =
        with(density) {
            destinations
                .maxOf { (_, label, _) ->
                    textMeasurer
                        .measure(label, style = drawerLabelStyle, softWrap = false)
                        .size
                        .width
                }
                .toDp()
        }
    val railWidth = maxOf(80.dp + 48.dp * fontGrowth, railLabelWidth + 16.dp)
    // Drawer padding (24), item padding (40), icon (24), and icon-to-label gap (12).
    val drawerWidth = maxOf(216.dp + 84.dp * fontGrowth, drawerLabelWidth + 100.dp)
    Row(Modifier.fillMaxSize()) {
        if (window.navigation != GatherNavigation.Bottom) {
            if (window.navigation == GatherNavigation.Drawer) {
                PermanentDrawerSheet(
                    Modifier.width(drawerWidth)
                        .height(navigationHeight)
                        .testTag("navigation-drawer"),
                    windowInsets = WindowInsets(0),
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Brand()
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onNewList,
                            modifier =
                                Modifier.fillMaxWidth().testTag("new-list").semantics {
                                    contentDescription = "New list"
                                },
                        ) {
                            Icon(Icons.Outlined.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("New list")
                        }
                        destinations.forEach { (id, label, icon) ->
                            NavigationDrawerItem(
                                label = { Text(label) },
                                selected = current == id,
                                onClick = { onDestination(id) },
                                icon = { Icon(icon, null) },
                            )
                        }
                    }
                }
            } else {
                NavigationRail(
                    modifier =
                        Modifier.width(railWidth)
                            .height(navigationHeight)
                            .testTag("navigation-rail"),
                    windowInsets = WindowInsets(0),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = onNewList,
                            modifier =
                                Modifier.padding(horizontal = 6.dp)
                                    .fillMaxWidth()
                                    .testTag("new-list")
                                    .semantics { contentDescription = "New list" },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.Add, null)
                                Text(
                                    "New list",
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                        destinations.forEach { (id, label, icon) ->
                            NavigationRailItem(
                                modifier = Modifier.fillMaxWidth(),
                                selected = current == id,
                                onClick = { onDestination(id) },
                                icon = { Icon(icon, null) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
            VerticalDivider()
        }
        Scaffold(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            topBar = topBar,
            bottomBar = {
                if (window.navigation == GatherNavigation.Bottom && state.selected == null) {
                    NavigationBar(
                        Modifier.testTag("navigation-bottom"),
                        windowInsets = WindowInsets(0),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        destinations.forEach { (id, label, icon) ->
                            NavigationBarItem(
                                selected = current == id,
                                onClick = { onDestination(id) },
                                icon = { Icon(icon, null) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (
                    !settings &&
                        state.selected == null &&
                        window.navigation == GatherNavigation.Bottom
                ) {
                    ExtendedFloatingActionButton(
                        onClick = onNewList,
                        modifier =
                            Modifier.testTag("new-list").semantics {
                                contentDescription = "New list"
                            },
                        icon = { Icon(Icons.Outlined.Add, null) },
                        text = { Text("New list") },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            },
            content = content,
        )
    }
}
