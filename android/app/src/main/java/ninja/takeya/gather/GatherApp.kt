@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ninja.takeya.gather

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Editor {
    data class ListForm(val list: GatherList? = null) : Editor

    data class ItemForm(
        val list: GatherList,
        val item: ListItem? = null,
        val sectionId: String? = null,
    ) : Editor

    data class SectionForm(val list: GatherList, val section: Section? = null) : Editor

    data class CategoryForm(val category: Category? = null) : Editor

    data class Share(val list: GatherList) : Editor

    data object Password : Editor

    data object Capture : Editor
}

data class Confirmation(
    val title: String,
    val message: String,
    val label: String,
    val danger: Boolean = false,
    val action: () -> Unit,
)

@Composable
fun GatherApp(model: GatherViewModel, folds: List<FoldingFeature> = emptyList()) {
    val state by model.state.collectAsStateWithLifecycle()
    var editor by model.ui::editor
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    var settings by model.ui::settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(
        enabled =
            state.username != null &&
                editor == null &&
                confirm == null &&
                (state.selectedId != null || settings)
    ) {
        if (state.selectedId != null) model.select(null)
        else {
            settings = false
            model.collection("library")
        }
    }
    var importing by remember { mutableStateOf<String?>(null) }
    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            model.finishExport(uri)
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    runCatching {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    val data = stream.readNBytes(5 * 1024 * 1024 + 1)
                                    require(data.size <= 5 * 1024 * 1024)
                                    String(data, Charsets.UTF_8)
                                } ?: error("Missing file")
                            }
                        }
                        .onSuccess { importing = it }
                        .onFailure {
                            model.report(
                                "Couldn’t open that backup. Choose a Gather JSON file no larger than 5 MiB."
                            )
                        }
                }
        }
    LaunchedEffect(state.username) {
        if (state.username == null) {
            editor = null
            confirm = null
            settings = false
            importing = null
        } else model.ui.auth.clear()
    }
    LaunchedEffect(state.captureId, state.username) {
        if (state.capture != null && state.username != null) model.ui.openCapture(state.captureId)
    }
    LaunchedEffect(state.workspace, editor) {
        val current = editor
        val available =
            when (current) {
                is Editor.ListForm ->
                    current.list == null || state.workspace.lists.any { it.id == current.list.id }
                is Editor.ItemForm ->
                    state.workspace.lists
                        .firstOrNull { it.id == current.list.id }
                        ?.let { list ->
                            current.item == null || list.items.any { it.id == current.item.id }
                        } ?: false
                is Editor.SectionForm ->
                    state.workspace.lists
                        .firstOrNull { it.id == current.list.id }
                        ?.let { list ->
                            current.section == null ||
                                list.sections.any { it.id == current.section.id }
                        } ?: false
                is Editor.Share -> state.workspace.lists.any { it.id == current.list.id }
                is Editor.CategoryForm ->
                    current.category == null ||
                        state.workspace.categories.any { it.id == current.category.id }
                else -> true
            }
        if (!available) {
            editor = null
            confirm = null
            model.report("This content is no longer available. The view has been refreshed.")
        }
    }
    LaunchedEffect(state.exportReady) {
        if (state.exportReady) {
            model.exportPickerLaunched()
            exportLauncher.launch("Gather-backup-${LocalDate.now()}.json")
        }
    }
    GatherTheme(state.appearance) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            GatherWindowFrame(folds) {
                if (state.starting)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Brand()
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Text(
                                "Opening Gather…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                else if (state.username == null) GatherReveal { AuthScreen(state, model) }
                else
                    GatherReveal {
                        val writable = state.connected && !state.busy
                        val pageTarget =
                            GatherPage(
                                when {
                                    state.selected != null -> "detail:${state.selected!!.id}"
                                    settings -> "settings"
                                    else -> state.scope
                                },
                                state,
                            )
                        val layoutDirection = LocalLayoutDirection.current
                        val focusManager = LocalFocusManager.current
                        LaunchedEffect(pageTarget.key) { focusManager.clearFocus() }
                        GatherWorkspaceScaffold(
                            state = state,
                            settings = settings,
                            onDestination = { id ->
                                settings = id == "settings"
                                if (settings) model.select(null) else model.collection(id)
                            },
                            onNewList = { editor = Editor.ListForm() },
                            topBar = {
                                Column {
                                    TopAppBar(
                                        title = {
                                            if (state.selected != null)
                                                Text(
                                                    "List",
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                            else Brand()
                                        },
                                        navigationIcon = {
                                            if (state.selected != null)
                                                IconButton(onClick = { model.select(null) }) {
                                                    Icon(
                                                        Icons.AutoMirrored.Outlined.ArrowBack,
                                                        "Back to library",
                                                    )
                                                }
                                        },
                                        actions = {
                                            AnimatedVisibility(
                                                visible = state.busy,
                                                enter = fadeIn(GatherMotion.quick()),
                                                exit = fadeOut(GatherMotion.quick()),
                                                label = "Refreshing",
                                            ) {
                                                CircularProgressIndicator(
                                                    Modifier.size(22.dp).padding(2.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            }
                                            IconButton(
                                                onClick = model::refresh,
                                                enabled = !state.busy,
                                            ) {
                                                Icon(Icons.Outlined.Refresh, "Refresh lists")
                                            }
                                            if (state.selected != null)
                                                IconButton(
                                                    onClick = {
                                                        editor = Editor.Share(state.selected!!)
                                                        model.loadSharing(state.selected!!.id)
                                                    },
                                                    enabled = writable,
                                                ) {
                                                    Icon(Icons.Outlined.Group, "Share list")
                                                }
                                        },
                                        colors =
                                            TopAppBarDefaults.topAppBarColors(
                                                containerColor =
                                                    MaterialTheme.colorScheme.background
                                            ),
                                    )
                                    AnimatedVisibility(
                                        visible = !state.connected,
                                        enter =
                                            expandVertically(
                                                GatherMotion.standard(),
                                                expandFrom = Alignment.Top,
                                            ) + fadeIn(GatherMotion.quick()),
                                        exit =
                                            shrinkVertically(
                                                GatherMotion.standard(),
                                                shrinkTowards = Alignment.Top,
                                            ) + fadeOut(GatherMotion.quick()),
                                        label = "Connection status",
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                )
                                                .padding(horizontal = 20.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                Icons.Outlined.CloudOff,
                                                null,
                                                Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                "No connection · Read-only",
                                                style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.weight(1f),
                                            )
                                            TextButton(onClick = model::refresh) { Text("Retry") }
                                        }
                                    }
                                    Notice(state, model)
                                }
                            },
                        ) { padding ->
                            GatherPanes(
                                modifier = Modifier.padding(padding),
                                allowSplit = !settings,
                                list = {
                                    Box(
                                        Modifier.fillMaxSize().testTag("page-${state.scope}"),
                                        contentAlignment = Alignment.TopCenter,
                                    ) {
                                        LibraryScreen(state, model, { editor = it })
                                    }
                                },
                                detail = {
                                    AnimatedContent(
                                        targetState = pageTarget,
                                        contentKey = { it.key },
                                        transitionSpec = { gatherFade() },
                                        label = "Detail pane",
                                    ) { page ->
                                        Box(
                                            Modifier.fillMaxSize()
                                                .gatherPageInput(page.key == pageTarget.key)
                                                .testTag(
                                                    "page-${if (page.isDetail) page.key else "overview"}"
                                                ),
                                            contentAlignment = Alignment.TopCenter,
                                        ) {
                                            val list = page.state.selected
                                            if (list != null)
                                                DetailScreen(
                                                    list,
                                                    page.state,
                                                    writable,
                                                    model,
                                                    { editor = it },
                                                    { confirm = it },
                                                    compactHeader = true,
                                                )
                                            else WorkspaceOverview(page.state, model)
                                        }
                                    }
                                },
                            ) {
                                AnimatedContent(
                                    targetState = pageTarget,
                                    contentKey = { it.key },
                                    transitionSpec = {
                                        val forward =
                                            when {
                                                targetState.isDetail -> true
                                                initialState.isDetail -> false
                                                else -> targetState.order > initialState.order
                                            }
                                        val direction = if (forward) 1 else -1
                                        gatherPage(
                                            if (layoutDirection == LayoutDirection.Rtl) -direction
                                            else direction
                                        )
                                    },
                                    label = "Page",
                                ) { page ->
                                    val pageState = page.state
                                    Box(
                                        Modifier.fillMaxSize()
                                            .gatherPageInput(page.key == pageTarget.key)
                                            .testTag("page-${page.key}")
                                    ) {
                                        when {
                                            page.isDetail ->
                                                pageState.selected?.let { list ->
                                                    DetailScreen(
                                                        list,
                                                        pageState,
                                                        writable,
                                                        model,
                                                        { editor = it },
                                                        { confirm = it },
                                                    )
                                                }
                                            page.key == "settings" ->
                                                SettingsScreen(
                                                    pageState,
                                                    model,
                                                    { editor = Editor.Password },
                                                    model::exportBackup,
                                                    {
                                                        importLauncher.launch(
                                                            arrayOf(
                                                                "application/json",
                                                                "text/plain",
                                                                "application/octet-stream",
                                                            )
                                                        )
                                                    },
                                                    { confirm = it },
                                                )
                                            else -> LibraryScreen(pageState, model, { editor = it })
                                        }
                                    }
                                }
                            }
                        }
                        editor?.let { current ->
                            EditorSheet(
                                current,
                                state,
                                model,
                                onDismiss = {
                                    editor = null
                                    if (current == Editor.Capture) model.dismissCapture()
                                },
                                onConfirm = { confirm = it },
                            )
                        }
                        state.invite?.let { invite ->
                            GatherAlertDialog(
                                onDismissRequest = model::dismissInvite,
                                icon = { Icon(Icons.Outlined.Group, null) },
                                title = { Text("You’re invited") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(
                                            invite.listTitle,
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                        Text(
                                            "${invite.ownerUsername} invited you to edit this list. Opening this invitation hasn’t joined the list."
                                        )
                                        Text(
                                            if (invite.alreadyMember)
                                                "You already have access as ${state.username}."
                                            else
                                                "Joining as ${state.username}. Item edits and checkmarks are shared. Your favorites, categories, and archive stay personal.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(onClick = model::joinInvite, enabled = writable) {
                                        Text(if (invite.alreadyMember) "Open list" else "Join list")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = model::dismissInvite) { Text("Not now") }
                                },
                            )
                        }
                        if (
                            state.inviteToken != null && state.invite == null && state.error != null
                        )
                            TextButton(
                                onClick = model::dismissInvite,
                                modifier = Modifier.navigationBarsPadding().padding(16.dp),
                            ) {
                                Text("Dismiss invitation")
                            }
                        importing?.let { raw ->
                            GatherAlertDialog(
                                onDismissRequest = { importing = null },
                                icon = { Icon(Icons.Outlined.FileDownload, null) },
                                title = { Text("Import backup copies?") },
                                text = {
                                    Text(
                                        "Adds private copies with new IDs. Existing lists and sharing stay as they are. Accounts and access aren’t imported."
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            model.importBackup(raw)
                                            importing = null
                                        },
                                        enabled = writable,
                                    ) {
                                        Text("Import copies")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { importing = null }) { Text("Cancel") }
                                },
                            )
                        }
                        confirm?.let { c ->
                            GatherAlertDialog(
                                onDismissRequest = { confirm = null },
                                title = { Text(c.title) },
                                text = { Text(c.message) },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            confirm = null
                                            c.action()
                                        },
                                        enabled = writable,
                                        colors =
                                            ButtonDefaults.textButtonColors(
                                                contentColor =
                                                    if (c.danger) MaterialTheme.colorScheme.error
                                                    else MaterialTheme.colorScheme.primary
                                            ),
                                    ) {
                                        Text(c.label)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { confirm = null }) { Text("Cancel") }
                                },
                            )
                        }
                    }
            }
        }
    }
}

@Composable
fun Brand() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_gather),
            null,
            Modifier.size(30.dp),
            tint = Color.Unspecified,
        )
        Text("Gather", style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
fun Notice(state: GatherState, model: GatherViewModel) {
    val notice = (state.error ?: state.message)?.let { it to (state.error != null) }
    AnimatedContent(
        targetState = notice,
        transitionSpec = { gatherFade(resize = true) },
        label = "Notice",
    ) { displayed ->
        if (displayed != null) {
            Surface(
                color =
                    if (displayed.second) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                modifier =
                    Modifier.fillMaxWidth().gatherPageInput(displayed == notice).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                Row(
                    Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        displayed.first,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = model::dismissNotice) {
                        Icon(Icons.Outlined.Close, "Dismiss message")
                    }
                }
            }
        } else Spacer(Modifier.fillMaxWidth().height(0.dp))
    }
}

@Composable
fun LibraryScreen(state: GatherState, model: GatherViewModel, edit: (Editor) -> Unit) {
    var options by remember { mutableStateOf(false) }
    val filtered =
        remember(state.workspace, state.scope, state.categoryId) {
            visibleLists(state.workspace, state.scope, state.categoryId)
        }
    val lists =
        if (state.query.isBlank()) filtered
        else
            state.search?.results?.mapNotNull { result ->
                state.workspace.lists.firstOrNull { it.id == result.listId }
            } ?: emptyList()
    BoxWithConstraints(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
        val gutter = if (maxWidth < 360.dp) 12.dp else 22.dp
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            state = model.ui.listScroll("library:${state.scope}"),
            contentPadding =
                PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (state.scope) {
                                "favorites" -> "Favorites"
                                "templates" -> "Templates"
                                "archive" -> "Archive"
                                "all" -> "All lists"
                                else -> "Lists"
                            },
                            style = MaterialTheme.typography.headlineLarge,
                        )
                    }
                    Box {
                        IconButton(onClick = { options = true }) {
                            Icon(Icons.Outlined.MoreHoriz, "Library options")
                        }
                        DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                            DropdownMenuItem(
                                text = { Text("Archive") },
                                onClick = {
                                    options = false
                                    model.collection("archive")
                                },
                                leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("All lists") },
                                onClick = {
                                    options = false
                                    model.collection("all")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("New category") },
                                onClick = {
                                    options = false
                                    edit(Editor.CategoryForm())
                                },
                                enabled = state.connected && !state.busy,
                            )
                            state.workspace.categories
                                .sortedBy { it.position }
                                .forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text("Edit ${cat.name}") },
                                        onClick = {
                                            options = false
                                            edit(Editor.CategoryForm(cat))
                                        },
                                        enabled = state.connected && !state.busy,
                                    )
                                }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    state.query,
                    model::query,
                    modifier =
                        Modifier.fillMaxWidth().testTag("library-search").semantics {
                            contentDescription = "Search lists and items"
                        },
                    label = { Text("Search") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (state.query.isNotBlank())
                            IconButton(onClick = { model.query("") }) {
                                Icon(Icons.Outlined.Close, "Clear search")
                            }
                    },
                )
            }
            if (state.workspace.categories.isNotEmpty())
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.categoryId == null,
                            onClick = { model.category(null) },
                            label = { Text("All categories") },
                        )
                        state.workspace.categories
                            .sortedBy { it.position }
                            .forEach { cat ->
                                FilterChip(
                                    selected = state.categoryId == cat.id,
                                    onClick = { model.category(cat.id) },
                                    label = { Text(cat.name) },
                                    leadingIcon = {
                                        Box(
                                            Modifier.size(9.dp)
                                                .background(tint(cat.color), CircleShape)
                                        )
                                    },
                                )
                            }
                    }
                }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.query.isBlank())
                            "${lists.size} ${if(lists.size==1)"list" else "lists"}"
                        else "Search results",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.searching)
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else if (state.search != null)
                        Text(
                            if (state.search.status == "ready") "Items & notes included"
                            else if (state.search.status == "warming")
                                "Text matches · Smart search is starting"
                            else "Text matches · Smart search unavailable",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
            if (lists.isEmpty() && !state.searching)
                item {
                    EmptyState(
                        if (state.query.isNotBlank()) "No results"
                        else if (state.categoryId != null) "No lists in this category"
                        else
                            when (state.scope) {
                                "favorites" -> "No favorites yet"
                                "templates" -> "No templates yet"
                                "archive" -> "No archived lists"
                                else -> "No lists yet"
                            },
                        if (state.query.isNotBlank()) "Try another search or change your filters."
                        else if (state.categoryId != null) "Choose another category."
                        else
                            when (state.scope) {
                                "favorites" -> "Tap the star on a list to find it here."
                                "templates" -> "New lists here are saved as templates."
                                "archive" -> ""
                                else -> ""
                            },
                        if (state.scope == "templates") Icons.Outlined.ContentCopy
                        else Icons.Outlined.Inventory2,
                    )
                }
            items(lists, key = { it.id }) { list ->
                ListCard(
                    list,
                    state.search?.results?.firstOrNull { it.listId == list.id },
                    onOpen = { model.select(list.id) },
                    onFavorite = {
                        model.action(
                            "list.update",
                            payload("listId" to list.id, "favorite" to !list.favorite),
                        )
                    },
                    enabled = state.connected && !state.busy,
                    selected = state.selectedId == list.id,
                    modifier =
                        Modifier.animateItem(
                            fadeInSpec = GatherMotion.quick(),
                            placementSpec = GatherMotion.standard(),
                            fadeOutSpec = GatherMotion.quick(),
                        ),
                )
            }
        }
    }
}

@Composable
fun WorkspaceOverview(state: GatherState, model: GatherViewModel) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(28.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ListAlt,
                    null,
                    Modifier.padding(24.dp).size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text("Select a list", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun EmptyState(title: String, description: String, icon: ImageVector) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(76.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (description.isNotEmpty())
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
    }
}

fun listIcon(name: String): ImageVector =
    when (name) {
        "basket",
        "shopping-cart" -> Icons.Outlined.ShoppingBasket
        "gift" -> Icons.Outlined.CardGiftcard
        "plane",
        "luggage" -> Icons.Outlined.Luggage
        "heart" -> Icons.Outlined.FavoriteBorder
        "bulb",
        "lightbulb" -> Icons.Outlined.Lightbulb
        "home" -> Icons.Outlined.Home
        "briefcase" -> Icons.Outlined.WorkOutline
        "book" -> Icons.AutoMirrored.Outlined.MenuBook
        else -> Icons.AutoMirrored.Outlined.ListAlt
    }

@Composable
fun ListCard(
    list: GatherList,
    result: SearchResult?,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    enabled: Boolean,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.semantics { this.selected = selected },
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 360.dp
            Column(Modifier.padding(if (compact) 12.dp else 18.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(if (compact) 36.dp else 48.dp)
                            .background(
                                tint(list.color),
                                RoundedCornerShape(if (compact) 12.dp else 17.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            listIcon(list.icon),
                            null,
                            Modifier.size(if (compact) 20.dp else 25.dp),
                        )
                    }
                    Spacer(Modifier.width(if (compact) 8.dp else 14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            list.title,
                            style =
                                if (compact) MaterialTheme.typography.titleMedium
                                else MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (list.kind == "notes" && compact)
                                "${list.items.size} ${if (list.items.size == 1) "note" else "notes"}"
                            else if (list.kind == "notes")
                                "Notes & ideas · ${list.items.size} entries"
                            else "${list.checkedCount} of ${list.items.size} done",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    IconButton(
                        onClick = onFavorite,
                        enabled = enabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            if (list.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                            if (list.favorite) "Remove ${list.title} from favorites"
                            else "Favorite ${list.title}",
                            tint =
                                if (list.favorite) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                if (result != null)
                    Text(
                        "${result.match.field.replaceFirstChar{it.uppercase()}} · ${result.match.text}",
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = if (compact) 8.dp else 12.dp),
                    )
                else if (!compact && list.description.isNotBlank())
                    Text(
                        list.description,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                if (!compact && list.kind == "checklist" && list.items.isNotEmpty()) {
                    val progress by
                        animateFloatAsState(
                            list.checkedCount.toFloat() / list.items.size,
                            animationSpec = GatherMotion.standard(),
                            label = "List progress",
                        )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(top = 16.dp)
                                .height(4.dp)
                                .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = tint(list.color),
                    )
                }
                if (
                    list.shared ||
                        list.isTemplate ||
                        list.archived ||
                        list.repeatCadence != "manual"
                )
                    Row(
                        Modifier.padding(top = if (compact) 8.dp else 12.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (list.shared) {
                            Icon(Icons.Outlined.Group, null, Modifier.size(15.dp))
                            Text(
                                if (list.owner) "Shared by you" else "From ${list.ownerUsername}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (list.isTemplate)
                            Text("TEMPLATE", style = MaterialTheme.typography.labelSmall)
                        if (list.archived)
                            Text("ARCHIVED", style = MaterialTheme.typography.labelSmall)
                        if (list.repeatCadence != "manual")
                            Text(
                                list.repeatCadence.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                            )
                    }
            }
        }
    }
}

@Composable
fun DetailScreen(
    list: GatherList,
    state: GatherState,
    writable: Boolean,
    model: GatherViewModel,
    edit: (Editor) -> Unit,
    confirm: (Confirmation) -> Unit,
    compactHeader: Boolean = false,
) {
    var more by remember { mutableStateOf(false) }
    val progress by
        animateFloatAsState(
            if (list.items.isEmpty()) 0f else list.checkedCount.toFloat() / list.items.size,
            animationSpec = GatherMotion.standard(),
            label = "Checklist progress",
        )
    val unsectioned = remember(list) { sortedGroup(list, null) }
    LazyColumn(
        Modifier.widthIn(max = 840.dp).fillMaxSize(),
        state = model.ui.listScroll("detail:${list.id}"),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Surface(
                color = tint(list.color),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(if (compactHeader) 14.dp else 22.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeader) 6.dp else 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            listIcon(list.icon),
                            null,
                            Modifier.size(if (compactHeader) 24.dp else 32.dp),
                        )
                        if (compactHeader) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                list.title,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        } else Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                model.action(
                                    "list.update",
                                    payload("listId" to list.id, "favorite" to !list.favorite),
                                )
                            },
                            enabled = writable,
                        ) {
                            Icon(
                                if (list.favorite) Icons.Outlined.Star
                                else Icons.Outlined.StarOutline,
                                if (list.favorite) "Remove from favorites" else "Add to favorites",
                            )
                        }
                        Box {
                            IconButton(onClick = { more = true }) {
                                Icon(Icons.Outlined.MoreHoriz, "List options")
                            }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(
                                    text = { Text("Edit list details") },
                                    onClick = {
                                        more = false
                                        edit(Editor.ListForm(list))
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                    enabled = writable,
                                )
                                DropdownMenuItem(
                                    text = { Text("Make a private copy") },
                                    onClick = {
                                        more = false
                                        model.action(
                                            "list.clone",
                                            payload("listId" to list.id),
                                            true,
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    enabled = writable,
                                )
                                DropdownMenuItem(
                                    text = { Text("Add section") },
                                    onClick = {
                                        more = false
                                        edit(Editor.SectionForm(list))
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.Add, null) },
                                    enabled = writable,
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (list.archived) "Restore to library"
                                            else "Archive for me"
                                        )
                                    },
                                    onClick = {
                                        more = false
                                        if (list.archived)
                                            model.action(
                                                "list.update",
                                                payload("listId" to list.id, "archived" to false),
                                            )
                                        else
                                            confirm(
                                                Confirmation(
                                                    "Archive this list for you?",
                                                    "You can restore it from Archive. Others can still see it in their library.",
                                                    "Archive",
                                                ) {
                                                    model.action(
                                                        "list.update",
                                                        payload(
                                                            "listId" to list.id,
                                                            "archived" to true,
                                                        ),
                                                    )
                                                }
                                            )
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                                    enabled = writable,
                                )
                                if (list.owner)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Delete list",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            more = false
                                            confirm(
                                                Confirmation(
                                                    if (list.shared)
                                                        "Delete this list for everyone?"
                                                    else "Delete this list?",
                                                    if (list.shared)
                                                        "Everyone will lose access. This can’t be undone."
                                                    else
                                                        "All its items and notes will be permanently deleted. This can’t be undone.",
                                                    "Delete",
                                                    true,
                                                ) {
                                                    model.action(
                                                        "list.delete",
                                                        payload("listId" to list.id),
                                                    )
                                                }
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                        enabled = writable,
                                    )
                                else
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Leave shared list",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            more = false
                                            confirm(
                                                Confirmation(
                                                    "Leave this list?",
                                                    "Your edits stay with the list. You’ll need a new invitation to return.",
                                                    "Leave",
                                                    true,
                                                ) {
                                                    model.sharingAction("leave", list.id)
                                                }
                                            )
                                        },
                                        enabled = writable,
                                    )
                            }
                        }
                    }
                    if (!compactHeader)
                        Text(list.title, style = MaterialTheme.typography.headlineLarge)
                    if (list.description.isNotBlank())
                        Text(
                            list.description,
                            style =
                                if (compactHeader) MaterialTheme.typography.bodyMedium
                                else MaterialTheme.typography.bodyLarge,
                        )
                    Text(
                        when {
                            list.shared && list.owner -> "Shared · You own this list"
                            list.shared -> "Shared · Owned by ${list.ownerUsername}"
                            else -> "Private · Only you have access"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuggestionChip(
                            onClick = { edit(Editor.ListForm(list)) },
                            label = {
                                Text(if (list.kind == "notes") "Notes & ideas" else "Checklist")
                            },
                            enabled = writable,
                        )
                        if (list.isTemplate)
                            SuggestionChip(onClick = {}, label = { Text("Template") })
                        if (list.repeatCadence != "manual")
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(list.repeatCadence.replaceFirstChar { it.uppercase() })
                                },
                            )
                    }
                    if (list.kind == "checklist" && !list.isTemplate) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${list.checkedCount} of ${list.items.size} complete",
                                modifier = Modifier.weight(1f),
                                style =
                                    if (compactHeader) MaterialTheme.typography.labelMedium
                                    else MaterialTheme.typography.labelLarge,
                            )
                            if (list.checkedCount > 0 && !list.archived)
                                TextButton(
                                    onClick = {
                                        confirm(
                                            Confirmation(
                                                "Reset checkmarks?",
                                                if (list.shared)
                                                    "This resets checkmarks for everyone."
                                                else
                                                    "This clears checkmarks and keeps every item, quantity, and note.",
                                                "Reset checkmarks",
                                            ) {
                                                model.action(
                                                    "list.reset",
                                                    payload("listId" to list.id),
                                                )
                                            }
                                        )
                                    },
                                    enabled = writable,
                                    contentPadding =
                                        PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        "Reset checkmarks",
                                        style =
                                            if (compactHeader) MaterialTheme.typography.labelMedium
                                            else MaterialTheme.typography.labelLarge,
                                    )
                                }
                        }
                        if (list.items.isNotEmpty())
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .height(if (compactHeader) 4.dp else 5.dp)
                                        .clip(CircleShape),
                                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = .6f),
                            )
                    }
                    if (list.isTemplate) {
                        Text(
                            "Start a separate private copy with unchecked items. People on this template won’t have access to the copy.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = {
                                val date =
                                    LocalDate.now()
                                        .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                                model.action(
                                    "list.clone",
                                    payload(
                                        "listId" to list.id,
                                        "title" to "${list.title} — $date".take(200),
                                    ),
                                    true,
                                )
                            },
                            enabled = writable,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start list")
                        }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (list.kind == "notes") "Notes & ideas" else "Items",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { edit(Editor.ItemForm(list)) }, enabled = writable) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add item")
                }
            }
        }
        if (list.items.isEmpty() && list.sections.isEmpty())
            item {
                EmptyState(
                    "No items yet",
                    "",
                    if (list.kind == "notes") Icons.Outlined.Lightbulb
                    else Icons.Outlined.CheckCircleOutline,
                )
            }
        items(unsectioned, key = { it.id }) { item ->
            ItemRow(
                list,
                item,
                writable,
                {
                    if (list.kind == "checklist")
                        model.action(
                            "item.update",
                            payload(
                                "listId" to list.id,
                                "itemId" to item.id,
                                "checked" to !item.checked,
                            ),
                        )
                },
                { edit(Editor.ItemForm(list, item)) },
                modifier =
                    Modifier.animateItem(
                        fadeInSpec = GatherMotion.quick(),
                        placementSpec = GatherMotion.standard(),
                        fadeOutSpec = GatherMotion.quick(),
                    ),
            )
        }
        list.sections
            .sortedBy { it.position }
            .forEach { section ->
                item(key = "section-${section.id}") {
                    Row(
                        Modifier.animateItem(
                                fadeInSpec = GatherMotion.quick(),
                                placementSpec = GatherMotion.standard(),
                                fadeOutSpec = GatherMotion.quick(),
                            )
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { edit(Editor.ItemForm(list, sectionId = section.id)) },
                            enabled = writable,
                        ) {
                            Icon(Icons.Outlined.Add, "Add item to ${section.title}")
                        }
                        IconButton(
                            onClick = { edit(Editor.SectionForm(list, section)) },
                            enabled = writable,
                        ) {
                            Icon(Icons.Outlined.MoreHoriz, "Edit section ${section.title}")
                        }
                    }
                }
                items(sortedGroup(list, section.id), key = { it.id }) { item ->
                    ItemRow(
                        list,
                        item,
                        writable,
                        {
                            if (list.kind == "checklist")
                                model.action(
                                    "item.update",
                                    payload(
                                        "listId" to list.id,
                                        "itemId" to item.id,
                                        "checked" to !item.checked,
                                    ),
                                )
                        },
                        { edit(Editor.ItemForm(list, item)) },
                        modifier =
                            Modifier.animateItem(
                                fadeInSpec = GatherMotion.quick(),
                                placementSpec = GatherMotion.standard(),
                                fadeOutSpec = GatherMotion.quick(),
                            ),
                    )
                }
                if (sortedGroup(list, section.id).isEmpty())
                    item(key = "empty-${section.id}") {
                        Text(
                            "No items in this section",
                            Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        item {
            OutlinedButton(
                onClick = { edit(Editor.ItemForm(list)) },
                enabled = writable,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Add an item")
            }
        }
        if (list.repeatCadence != "manual")
            item {
                Text(
                    "${list.repeatCadence.replaceFirstChar { it.uppercase() }} · Does not reset automatically.",
                    Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

@Composable
fun ItemRow(
    list: GatherList,
    item: ListItem,
    enabled: Boolean,
    toggle: () -> Unit,
    edit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor by
        animateColorAsState(
            if (item.checked && list.kind == "checklist") MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            animationSpec = GatherMotion.quick(),
            label = "Item completion",
        )
    Surface(
        onClick = edit,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 5.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
                .heightIn(min = 54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (list.kind == "checklist")
                Checkbox(
                    checked = item.checked,
                    onCheckedChange = { toggle() },
                    enabled = enabled,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "${if(item.checked)"Uncheck" else "Check"} ${item.text}"
                        },
                )
            else
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(7.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration =
                        if (item.checked && list.kind == "checklist") TextDecoration.LineThrough
                        else null,
                    color = textColor,
                )
                if (item.note.isNotBlank())
                    Text(
                        item.note,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
            }
            if (item.quantity.isNotBlank())
                Surface(
                    color = tint(list.color),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(start = 10.dp),
                ) {
                    Text(
                        item.quantity,
                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            Icon(
                Icons.Outlined.ChevronRight,
                "Edit ${item.text}",
                Modifier.size(20.dp).padding(start = 4.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
fun SettingsScreen(
    state: GatherState,
    model: GatherViewModel,
    password: () -> Unit,
    export: () -> Unit,
    import: () -> Unit,
    confirm: (Confirmation) -> Unit,
) {
    BoxWithConstraints(Modifier.widthIn(max = 1120.dp).fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (maxWidth >= 760.dp) 2 else 1),
            modifier = Modifier.fillMaxSize(),
            state = model.ui.settingsScroll,
            contentPadding = PaddingValues(22.dp, 8.dp, 22.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Settings", style = MaterialTheme.typography.headlineLarge)
            }
            item {
                SettingsCard("Account", Icons.Outlined.PersonOutline) {
                    Text(state.username ?: "", style = MaterialTheme.typography.titleLarge)
                    SettingsRow(
                        "Change password",
                        "Signs out your other devices",
                        Icons.Outlined.Lock,
                        password,
                        state.connected && !state.busy,
                    )
                    SettingsRow(
                        "Sign out",
                        "This phone only",
                        Icons.AutoMirrored.Outlined.Logout,
                        {
                            confirm(
                                Confirmation(
                                    "Sign out?",
                                    "Lists stay safely on Gather. You’ll need your username and password to return.",
                                    "Sign out",
                                ) {
                                    model.logout()
                                }
                            )
                        },
                        !state.busy,
                    )
                }
            }
            item {
                SettingsCard("Appearance", Icons.Outlined.Palette) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                                "system" to "System",
                                "light" to "Paper",
                                "cloud" to "Cloud",
                                "dark" to "Forest",
                                "midnight" to "Midnight",
                            )
                            .forEach { (id, label) ->
                                FilterChip(
                                    selected = state.appearance == id,
                                    onClick = { model.appearance(id) },
                                    label = { Text(label) },
                                )
                            }
                    }
                }
            }
            item {
                SettingsCard("Backups", Icons.Outlined.Inventory2) {
                    SettingsRow(
                        "Export backup",
                        "Save the lists you own as JSON",
                        Icons.Outlined.FileUpload,
                        export,
                        state.connected && !state.busy,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsRow(
                        "Import backup copies",
                        "Add private copies from a Gather file",
                        Icons.Outlined.FileDownload,
                        import,
                        state.connected && !state.busy,
                    )
                    Text(
                        "Backups include owned lists, categories, notes, and checkmarks. Joined lists, accounts, and sharing access are excluded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.workspace.lists.any { it.isSample })
                item {
                    SettingsCard("Sample lists", Icons.Outlined.CleaningServices) {
                        SettingsRow(
                            "Remove sample lists",
                            "Only removes the starter examples",
                            Icons.Outlined.DeleteOutline,
                            {
                                confirm(
                                    Confirmation(
                                        "Remove sample lists?",
                                        "Your own lists stay. The starter examples will be permanently removed.",
                                        "Remove samples",
                                        true,
                                    ) {
                                        model.action("samples.remove", payload())
                                    }
                                )
                            },
                            state.connected && !state.busy,
                        )
                    }
                }
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} · Android 14+",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            content()
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    click: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = click)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
    }
}
