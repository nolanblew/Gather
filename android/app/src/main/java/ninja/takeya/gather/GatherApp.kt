@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ninja.takeya.gather

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Editor {
    data class ListForm(val list: GatherList? = null) : Editor

    data class ItemForm(
        val list: GatherList,
        val item: ListItem? = null,
        val sectionId: String? = null,
        val initialText: String = "",
        val filter: ListFilter = ListFilter(),
    ) : Editor

    data class SectionForm(val list: GatherList, val section: Section? = null) : Editor

    data class Filters(val list: GatherList, val filter: ListFilter, val view: Boolean = false) : Editor

    data class BulkAdd(val list: GatherList, val itemIds: Set<String>, val places: Boolean) : Editor

    data class GroupOrder(val listId: String, val grouping: ItemGrouping) : Editor

    data class Definitions(val list: GatherList) : Editor

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
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { image ->
        val listId = model.ui.pendingCoverListId
        model.ui.pendingCoverListId = null
        if (image != null && listId != null) model.uploadListCover(listId, image)
    }
    val chooseCover: (String) -> Unit = { listId ->
        model.ui.pendingCoverListId = listId
        coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    BackHandler(
        enabled =
            state.username != null &&
                editor == null &&
                confirm == null &&
                (state.selectedId != null || settings)
    ) {
        val listId = state.selectedId
        when {
            listId != null && model.ui.reorderListId == listId -> model.ui.reorderListId = null
            listId != null && model.ui.itemSelections.containsKey(listId) -> model.ui.itemSelections.remove(listId)
            listId != null && (model.ui.itemSearchListId == listId || !model.ui.itemQueries[listId].isNullOrBlank()) -> {
                model.ui.itemSearchListId = null
                model.ui.itemQueries.remove(listId)
            }
            listId != null -> model.select(null)
            else -> {
                settings = false
                model.collection("library")
            }
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
                is Editor.Filters -> state.workspace.lists.any { it.id == current.list.id }
                is Editor.Definitions -> state.workspace.lists.any { it.id == current.list.id }
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
                                    if (state.selected == null) TopAppBar(
                                        title = { Brand() },
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
                                                    { chooseCover(list.id) },
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
                                                        { chooseCover(list.id) },
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
fun ItemRow(
    list: GatherList,
    item: ListItem,
    enabled: Boolean,
    toggle: () -> Unit,
    edit: () -> Unit,
    ordinal: Int?,
    description: String,
    detail: String,
    alias: String? = null,
    selected: Boolean? = null,
    highlighted: Boolean = false,
    dragHandle: (@Composable () -> Unit)? = null,
    reorderMenu: (@Composable () -> Unit)? = null,
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
        shape = if (highlighted) RoundedCornerShape(10.dp) else androidx.compose.ui.graphics.RectangleShape,
        color = when {
            highlighted -> MaterialTheme.colorScheme.surfaceContainerHigh
            selected == true -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.background
        },
        border = if (highlighted) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.fillMaxWidth(),
    ) {
        val editAction = if (reorderMenu == null) Modifier.clickable(enabled = selected == null || enabled, onClick = edit)
            else Modifier
        Column {
            Row(
                editAction.fillMaxWidth().padding(start = 4.dp, end = 18.dp, top = 2.dp, bottom = 2.dp)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dragHandle != null) dragHandle()
                else if (list.kind == "checklist" || selected != null)
                    Checkbox(
                        checked = selected ?: item.checked,
                        onCheckedChange = { toggle() },
                        enabled = enabled,
                        modifier =
                            Modifier.semantics {
                                val action = when (selected) {
                                    true -> "Deselect"
                                    false -> "Select"
                                    null -> if (item.checked) "Uncheck" else "Check"
                                }
                                contentDescription = "$action ${item.text}"
                            },
                    )
                else if (ordinal == null)
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(7.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                val numberedNote = ordinal != null && list.kind != "checklist" && selected == null && dragHandle == null
                Column(
                    Modifier.weight(1f)
                        .padding(start = if (numberedNote) 12.dp else 0.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Text(
                        if (ordinal != null) "$ordinal. ${item.text}" else item.text,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration =
                            if (item.checked && list.kind == "checklist") TextDecoration.LineThrough
                            else null,
                        color = textColor,
                    )
                    if (description.isNotBlank())
                        Text(
                            description,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    if (detail.isNotBlank())
                        Text(
                            detail,
                            maxLines = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.25f) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    if (alias != null)
                        Text(
                            alias,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                }
                if (reorderMenu != null) reorderMenu()
                else if (selected == null)
                    Icon(
                        Icons.Outlined.ChevronRight,
                        "Edit ${item.text}",
                        Modifier.size(19.dp).padding(start = 3.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (highlighted) 0f else .6f),
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
