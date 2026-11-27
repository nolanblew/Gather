package ninja.takeya.gather

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Base64
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** All writes and refreshes share one mutex. Every completion is bound to its account epoch. */
data class GatherState(
    val starting: Boolean = true,
    val busy: Boolean = false,
    val username: String? = null,
    val workspace: Workspace = Workspace(),
    val selectedId: String? = null,
    val scope: String = "library",
    val categoryId: String? = null,
    val query: String = "",
    val search: SearchResponse? = null,
    val searching: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val sharing: Sharing? = null,
    val inviteToken: String? = null,
    val invite: InvitePreview? = null,
    val capture: String? = null,
    val captureId: Long = 0,
    val exportReady: Boolean = false,
    val appearance: String = "system",
    val capabilities: Set<String> = emptySet(),
    val workspaceReady: Boolean = false,
    val pendingChanges: Int = 0,
    val syncing: Boolean = false,
) {
    val canEditWorkspace
        get() = workspaceReady && (connected || "offline-actions" in capabilities)

    val selected
        get() = workspace.lists.firstOrNull { it.id == selectedId }
}

/** Accept only references still present in the authoritative account snapshot. */
internal fun GatherState.adoptWorkspace(
    result: Workspace,
    selectCreated: Boolean = false,
): GatherState {
    val candidate =
        if (selectCreated) result.createdListId ?: result.joinedListId ?: selectedId else selectedId
    val selection = candidate?.takeIf { id -> result.lists.any { it.id == id } }
    return copy(
        workspace = result,
        connected = true,
        workspaceReady = true,
        selectedId = selection,
        categoryId = categoryId?.takeIf { id -> result.categories.any { it.id == id } },
        sharing = sharing.takeIf { selection != null && selection == selectedId },
        search = search.takeIf { workspace == result },
    )
}

class GatherViewModel(application: Application) : AndroidViewModel(application) {
    val ui = GatherUiState()
    private val api = GatherApi()
    private val vault = TokenVault(application)
    private val offlineStore = OfflineWorkspaceStore(application)
    private val preferences = application.getSharedPreferences("appearance", 0)
    private val itemViewPreferences = application.getSharedPreferences("item-view", 0)
    private val covers = object : LruCache<Pair<String, String>, Bitmap>(16 * 1024) {
        override fun sizeOf(key: Pair<String, String>, value: Bitmap) = (value.allocationByteCount + 1023) / 1024
    }
    private val mutable =
        MutableStateFlow(
            GatherState(appearance = preferences.getString("theme", "system") ?: "system")
        )
    val state: StateFlow<GatherState> = mutable.asStateFlow()
    private val serial = Mutex()
    private val localWrites = Mutex()
    private var storedSession: StoredNativeSession? = vault.read()
    private val token get() = storedSession?.token
    private var accountId: String? = null
    private var offline = OfflineWorkspace()
    private var sessionConfirmed = false
    private var serverFeatures: Set<String>? = null
    private val epochs = SessionEpoch()
    private val epoch
        get() = epochs.current

    private var searchJob: Job? = null
    private var authJob: Job? = null
    private var refreshJob: Job? = null
    private var expiryJob: Job? = null


    // Kept out of both Compose state and saved instance bundles (backups can be 5 MiB).
    private data class PendingExport(val document: String, val epoch: Long, val username: String?)

    private var pendingExport: PendingExport? = null

    init {
        authJob = viewModelScope.launch {
            val session = storedSession
            if (session?.expired() == true) clearAccount("Your session ended. Sign in to continue.")
            else if (session != null) {
                scheduleSessionExpiry(session)
                val expected = epoch
                try {
                    restoreAccount(session, expected)
                    if (offline.outbox.isNotEmpty()) OfflineSyncJob.schedule(getApplication())
                } catch (failure: Exception) {
                    fail(failure, expected)
                }
                if (valid(expected)) requestSync()
            }
            if (storedSession?.userId != null || storedSession == null) mutable.update { it.copy(starting = false) }
        }
    }

    private fun valid(expected: Long) = epochs.isCurrent(expected)

    private fun fail(error: Throwable, expected: Long, interruptedWrite: Boolean = false) {
        if (error is CancellationException || !valid(expected)) return
        if (BuildConfig.DEBUG)
            android.util.Log.w(
                "GatherNetwork",
                error.javaClass.name + "\n" + error.stackTrace.joinToString("\n") { "at $it" },
            )
        if (error is ApiFailure && error.status == 401) {
            clearAccount("Your session ended. Sign in to continue.")
            return
        }
        val message =
            when {
                error is ApiFailure && error.code in setOf("ORDER_TOKEN_REQUIRED", "ORDER_CHANGED") ->
                    "The list order changed. Review the latest order and try again."
                error is ApiFailure -> error.message
                error is CoverPreparationFailure -> error.message.orEmpty()
                interruptedWrite -> "Could not confirm the save. Check the list before trying again."
                else -> "Can’t connect to Gather. Check your connection and try again."
            }
        mutable.update {
            it.copy(
                error = message,
                connected = (error is ApiFailure || error is CoverPreparationFailure) && it.connected,
            )
        }
    }

    private suspend fun restoreAccount(session: StoredNativeSession, expected: Long) {
        val id = session.userId ?: return
        val name = checkNotNull(session.username)
        if (accountId != id) {
            val snapshot = withContext(Dispatchers.IO) { offlineStore.read(id) }
            if (!valid(expected)) return
            accountId = id
            offline = snapshot
        }
        if (!valid(expected)) return
        mutable.update { it.copy(username = name, capabilities = offline.capabilities) }
        val record = offline
        record.projectForDisplay(name)?.let { projected ->
            publishOffline(expected, record, projected, notice = record.syncNotice)
        }
    }

    private suspend fun confirmSession(expected: Long) {
        val old = storedSession ?: return
        val session = api.decode<Session>(api.request("/api/native/auth/session", old.token))
        if (!valid(expected)) return
        if (!session.authenticated) {
            clearAccount("Your session ended. Sign in to continue.")
            return
        }
        val verified = StoredNativeSession(
            old.token, checkNotNull(session.userId), checkNotNull(session.username), session.expiresAt ?: old.expiresAt,
        )
        if (old.userId != null && old.userId != verified.userId) {
            clearAccount("Your session ended. Sign in to continue.")
            return
        }
        if (old != verified) vault.write(verified)
        storedSession = verified
        restoreAccount(verified, expected)
        if (valid(expected)) {
            sessionConfirmed = true
            scheduleSessionExpiry(verified)
        }
    }

    private suspend fun OfflineWorkspace.projectForDisplay(username: String): Workspace? =
        if (outbox.isEmpty()) base else withContext(Dispatchers.Default) { projected(username) }

    private fun publishOffline(
        expected: Long,
        record: OfflineWorkspace,
        workspace: Workspace,
        connected: Boolean? = null,
        receipt: Workspace? = null,
        selectCreated: Boolean = false,
        notice: String? = null,
    ) {
        if (!valid(expected) || offline !== record) return
        val projected = workspace.withoutReceipt().copy(
            createdListId = receipt?.createdListId,
            createdItemId = receipt?.createdItemId,
            joinedListId = receipt?.joinedListId,
            createdSubitemId = receipt?.createdSubitemId,
        )
        mutable.update { current ->
            current.adoptWorkspace(projected, selectCreated).copy(
                connected = connected ?: current.connected,
                capabilities = record.capabilities,
                pendingChanges = record.outbox.size,
                message = notice ?: current.message,
            )
        }
    }

    private suspend fun persistOffline(
        expected: Long,
        connected: Boolean? = null,
        receipt: Workspace? = null,
        selectCreated: Boolean = false,
        publish: Boolean = true,
        transform: (OfflineWorkspace) -> OfflineWorkspace,
    ): Boolean = localWrites.withLock {
        val id = accountId ?: return@withLock false
        if (!valid(expected)) return@withLock false
        val previous = offline
        val next = transform(previous)
        val projection = if (publish) next.projectForDisplay(state.value.username.orEmpty()) else null
        if (!valid(expected)) return@withLock false
        if (previous != next) {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { offlineStore.write(id, next) }
                if (valid(expected)) offline = next
            }
        }
        if (!valid(expected)) return@withLock false
        if (projection != null) {
            val notice = next.syncNotice.takeIf { it != previous.syncNotice }
            publishOffline(expected, offline, projection, connected, receipt, selectCreated, notice)
        }
        true
    }

    private suspend fun workspace(expected: Long): Workspace {
        val result = api.decode<Workspace>(api.request("/api/workspace", token))
        if (valid(expected)) {
            if (serverFeatures == null || !state.value.connected) {
                serverFeatures = try {
                    api.decode<ApiCapabilities>(api.request("/api/capabilities")).features.toSet()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (failure is ApiFailure && failure.status == 404) emptySet() else null
                }
            }
            persistOffline(expected, connected = true) {
                it.copy(base = result.withoutReceipt(), capabilities = serverFeatures ?: it.capabilities)
            }
        }
        return result
    }

    fun authenticate(username: String, password: String, register: Boolean) {
        if (state.value.busy) return
        epochs.advance()
        val expected = epoch
        authJob?.cancel()
        refreshJob?.cancel()
        expiryJob?.cancel()
        covers.evictAll()
        storedSession = null
        sessionConfirmed = false
        serverFeatures = null
        accountId = null
        offline = OfflineWorkspace()
        vault.clear()
        mutable.update {
            it.copy(
                busy = true,
                error = null,
                message = null,
                workspace = Workspace(),
                workspaceReady = false,
                pendingChanges = 0,
                syncing = false,
                username = null,
                connected = false,
                capabilities = emptySet(),
            )
        }
        authJob = viewModelScope.launch {
            try {
                val endpoint = if (register) "register" else "login"
                val credentials = payload("username" to username.trim(), "password" to password)
                val session = api.decode<Session>(api.request("/api/native/auth/$endpoint", body = credentials))
                if (!valid(expected)) return@launch
                val fresh = StoredNativeSession(
                    checkNotNull(session.accessToken), checkNotNull(session.userId),
                    checkNotNull(session.username), session.expiresAt,
                )
                vault.write(fresh)
                storedSession = fresh
                restoreAccount(fresh, expected)
                if (!valid(expected)) return@launch
                sessionConfirmed = true
                scheduleSessionExpiry(fresh)
                if (offline.outbox.isNotEmpty()) OfflineSyncJob.schedule(getApplication())
                requestSync()
            } catch (failure: Exception) {
                if (failure is ApiFailure && failure.status == 401 && valid(expected))
                    mutable.update { it.copy(error = failure.message) }
                else fail(failure, expected)
            } finally {
                if (valid(expected)) mutable.update { it.copy(busy = false, starting = false) }
            }
        }
    }

    private fun clearAccount(error: String? = null, message: String? = null) {
        ui.clearAccount()
        pendingExport = null
        refreshJob?.cancel()
        expiryJob?.cancel()
        covers.evictAll()
        epochs.advance()
        storedSession = null
        sessionConfirmed = false
        serverFeatures = null
        accountId = null
        offline = OfflineWorkspace()
        vault.clear()
        searchJob?.cancel()
        mutable.update {
            GatherState(
                starting = false,
                error = error,
                message = message,
                inviteToken = it.inviteToken,
                capture = it.capture,
                appearance = it.appearance,
            )
        }
    }

    private fun scheduleSessionExpiry(session: StoredNativeSession) {
        expiryJob?.cancel()
        val until = session.expiresAt?.let { value ->
            runCatching { java.time.Instant.parse(value).toEpochMilli() - System.currentTimeMillis() }.getOrNull()
        } ?: return
        expiryJob = viewModelScope.launch {
            delay(until.coerceAtLeast(1))
            if (token == session.token) hasCurrentSession()
        }
    }

    private fun hasCurrentSession(): Boolean {
        val session = storedSession ?: return false
        if (!session.expired()) return true
        clearAccount("Your session ended. Sign in to continue.")
        return false
    }

    fun logout() {
        if (state.value.busy) return
        val oldToken = token ?: return
        val expected = epoch
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            serial.withLock {
                if (!valid(expected)) return@withLock
                try {
                    api.request("/api/native/auth/logout", oldToken, payload())
                    if (valid(expected)) clearAccount(message = "Signed out on this phone.")
                } catch (failure: Exception) {
                    fail(failure, expected)
                } finally {
                    if (valid(expected)) mutable.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun refresh() {
        if (token == null || state.value.busy || state.value.starting) return
        requestSync()
    }

    internal suspend fun syncInBackground(): Boolean {
        authJob?.join()
        if (token == null || offline.outbox.isEmpty()) return false
        requestSync()?.join()
        return token != null && offline.outbox.isNotEmpty()
    }

    private fun requestSync(): Job? {
        if (!hasCurrentSession()) return null
        refreshJob?.takeIf { it.isActive }?.let { return it }
        val expected = epoch
        return viewModelScope.launch {
            var refreshSearch = false
            serial.withLock {
                if (!valid(expected)) return@withLock
                try {
                    if (!sessionConfirmed) confirmSession(expected)
                    if (!valid(expected) || accountId == null) return@withLock
                    if (offline.outbox.isNotEmpty()) mutable.update { it.copy(syncing = true) }
                    workspace(expected)
                    if ("offline-actions" in serverFeatures.orEmpty()) drainOutbox(expected)
                    refreshSearch = valid(expected) && state.value.query.isNotBlank()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (valid(expected) && state.value.workspaceReady && retryable(failure))
                        mutable.update { it.copy(connected = false) }
                    else fail(failure, expected)
                } finally {
                    if (valid(expected)) mutable.update { it.copy(syncing = false, starting = false) }
                }
            }
            if (refreshSearch) scheduleSearch()
            if (valid(expected) && state.value.connected && state.value.inviteToken != null && state.value.invite == null) previewInvite()
        }.also { refreshJob = it }
    }

    private fun retryable(failure: Exception): Boolean = failure !is ApiFailure ||
        failure.status in listOf(408, 425, 429) || failure.status >= 500

    private suspend fun drainOutbox(expected: Long) {
        val accessToken = token ?: return
        var rebases = 0
        while (valid(expected)) {
            val first = offline.outbox.firstOrNull() ?: break
            if (first.type == "workspace.import" && "offline-import" !in serverFeatures.orEmpty()) break
            val known = offline.base ?: break
            val action = first.submitted?.let { ActionPlan.Write(it) } ?: first.rebase(known)
            if (action is ActionPlan.Done) {
                finishAction(expected, first, resolvedIds = action.resolvedIds, notice = action.notice)
                continue
            }
            val submitted = (action as ActionPlan.Write).data
            if (first.submitted == null && !persistOffline(expected, publish = false) { record ->
                    record.copy(outbox = listOf(first.copy(submitted = submitted)) + record.outbox.drop(1))
                }) break
            try {
                val result = sendAction(first, submitted, accessToken)
                if (!valid(expected)) return
                finishAction(expected, first, result, result.resolvedIds)
                rebases = 0
            } catch (failure: ApiFailure) {
                if (failure.status == 401 || retryable(failure)) throw failure
                // Retry only after a definite rejection. A transport failure keeps the exact sent
                // body so the server can identify an operation whose acknowledgment was lost.
                val fresh = workspace(expected)
                if (!valid(expected)) return
                val revised = first.rebase(fresh)
                if (failure.code != "OPERATION_ID_REUSED" && revised is ActionPlan.Write && revised.data != submitted) {
                    persistOffline(expected, publish = false) { record ->
                        record.copy(outbox = listOf(first.copy(submitted = null)) + record.outbox.drop(1))
                    }
                    if (++rebases >= 4) return
                } else if (revised is ActionPlan.Done) {
                    finishAction(expected, first, resolvedIds = revised.resolvedIds, notice = revised.notice)
                } else {
                    finishAction(expected, first, notice = failure.message ?: "A change couldn’t sync.")
                }
            }
        }
    }

    private suspend fun sendAction(action: OfflineAction, data: JsonObject, accessToken: String): Workspace {
        val raw = if (action.type == "cover.put") api.uploadListCover(
            checkNotNull(data.string("listId")), data.coverBytes(), accessToken, action.id,
            data.string("expectedCoverId") ?: "none",
        ) else api.request(
            "/api/actions", accessToken, payload("type" to action.type, "operationId" to action.id, "payload" to data),
        )
        return api.decode(raw)
    }

    private fun JsonObject.coverBytes(): ByteArray = Base64.decode(checkNotNull(string("data")), Base64.NO_WRAP)

    private suspend fun finishAction(
        expected: Long,
        action: OfflineAction,
        result: Workspace? = null,
        resolvedIds: Map<String, String> = emptyMap(),
        notice: String? = null,
    ) {
        val coverDigest = action.data.string("digest").takeIf { action.type == "cover.put" }
        val coverId = result?.resolvedCoverId
        val aliases = if (coverDigest != null && coverId != null) resolvedIds + (coverDigest to coverId) else resolvedIds
        val imageAliases = if (result?.resolvedImages?.isNotEmpty() == true) {
            withContext(Dispatchers.Default) { result.imageAliasesFor(action) }
        } else emptyMap()
        persistOffline(expected, connected = true) { record ->
            check(record.outbox.firstOrNull()?.id == action.id)
            record.copy(
                base = result?.withoutReceipt() ?: record.base,
                outbox = record.outbox.drop(1).map { it.remap(aliases, imageAliases) },
                syncNotice = notice ?: record.syncNotice,
            )
        }
    }

    private fun Workspace.imageAliasesFor(action: OfflineAction): Map<String, String> {
        val descriptions = if (action.type == "workspace.import") {
            val ids = (action.data["contentIds"] as? JsonObject)?.values.orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            lists.filter { it.id in ids }.flatMap { list -> list.items.flatMap { it.description } }
        } else {
            lists.firstOrNull { it.id == action.data.string("listId") }
                ?.items?.firstOrNull { it.id == action.data.string("itemId") }?.description.orEmpty()
        }
        val urls = descriptions.mapNotNull { it.dataUrl }.associateBy(::workspaceHash)
        return resolvedImages.mapNotNull { (source, digest) -> urls[digest]?.let { source to it } }.toMap()
    }

    fun canRetrySession(): Boolean =
        token != null &&
            state.value.username == null &&
            !state.value.starting &&
            !state.value.busy &&
            !state.value.connected &&
            state.value.error != null

    fun select(id: String?) {
        val previous = state.value.selectedId
        if (previous != null && previous != id) {
            ui.itemSelections.remove(previous)
            if (ui.reorderListId == previous) ui.reorderListId = null
            if (ui.itemSearchListId == previous) ui.itemSearchListId = null
        }
        mutable.update { it.copy(selectedId = id, sharing = null) }
    }

    private fun itemSortKey(listId: String): String? = state.value.username?.let { username ->
        java.util.UUID.nameUUIDFromBytes(payload("account" to username, "listId" to listId).toString().toByteArray()).toString()
    }

    fun itemSort(list: GatherList): ItemSort {
        ui.itemSorts[list.id]?.let { return it }
        val stored = itemSortKey(list.id)?.let { itemViewPreferences.getString(it, null) }
        return ItemSort.entries.firstOrNull { it.name == stored } ?: list.defaultItemSort()
    }

    fun setItemSort(listId: String, sort: ItemSort) {
        itemSortKey(listId)?.let { key -> itemViewPreferences.edit { putString(key, sort.name) } }
        ui.itemSorts[listId] = sort
    }

    fun collection(scope: String) {
        mutable.update { it.copy(scope = scope, selectedId = null, sharing = null, search = null) }
        scheduleSearch()
    }

    fun category(id: String?) {
        mutable.update { it.copy(categoryId = id, search = null) }
        scheduleSearch()
    }

    fun query(query: String) {
        mutable.update { it.copy(query = query, search = null) }
        scheduleSearch()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val snapshot = state.value
        if (snapshot.query.isBlank() || token == null) {
            mutable.update { it.copy(search = null, searching = false) }
            return
        }
        val expected = epoch
        val local = localWorkspaceSearch(snapshot.workspace, snapshot.scope, snapshot.categoryId, snapshot.query)
        if (!snapshot.connected || snapshot.pendingChanges > 0) {
            mutable.update { it.copy(search = local, searching = false) }
            return
        }
        mutable.update { it.copy(search = local, searching = true) }
        searchJob =
            viewModelScope.launch {
                delay(350)
                try {
                    val response =
                        api.decode<SearchResponse>(
                            api.request(
                                "/api/search",
                                token,
                                payload(
                                    "query" to snapshot.query,
                                    "scope" to snapshot.scope,
                                    "categoryId" to snapshot.categoryId,
                                    "limit" to 60,
                                ),
                            )
                        )
                    if (
                        valid(expected) &&
                            state.value.query == snapshot.query &&
                            state.value.scope == snapshot.scope &&
                            state.value.categoryId == snapshot.categoryId
                    )
                        mutable.update { it.copy(search = response, searching = false) }
                } catch (e: Exception) {
                    if (e !is CancellationException && valid(expected)) {
                        if (e is ApiFailure && e.status == 401) fail(e, expected)
                        else mutable.update { it.copy(searching = false, connected = !retryable(e) && it.connected) }
                    }
                }
            }
    }

    fun action(
        type: String,
        data: JsonObject,
        selectCreated: Boolean = false,
        onSuccess: (() -> Unit)? = null,
    ) {
        val snapshot = state.value
        if (snapshot.busy || !snapshot.canEditWorkspace || !hasCurrentSession()) return
        if ("offline-actions" !in snapshot.capabilities) {
            mutation("/api/actions", payload("type" to type, "payload" to data), selectCreated, onSuccess)
            return
        }
        val expected = epoch
        val pending = makeOfflineAction(type, data, snapshot.workspace)
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                enqueueAction(expected, pending, selectCreated, onSuccess)
            } finally {
                if (valid(expected)) mutable.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun enqueueAction(
        expected: Long,
        action: OfflineAction,
        selectCreated: Boolean = false,
        after: (() -> Unit)? = null,
    ) {
        if (!valid(expected) || !hasCurrentSession()) return
        try {
            val receipt = Workspace(
                createdListId = when (action.type) {
                    "list.create" -> action.data.string("listId")
                    "list.clone" -> action.data.string("createdListId")
                    else -> null
                },
                createdItemId = action.data.string("itemId").takeIf { action.type in setOf("item.create", "item.createWithPeople") },
                createdSubitemId = action.data.string("subitemId").takeIf { action.type == "subitem.create" },
            )
            if (persistOffline(expected, receipt = receipt, selectCreated = selectCreated) {
                    it.copy(outbox = it.outbox + action)
                }) {
                OfflineSyncJob.schedule(getApplication())
                after?.invoke()
                if (state.value.query.isNotBlank()) scheduleSearch()
                requestSync()
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            if (valid(expected)) mutable.update { it.copy(error = "Couldn’t save this change on your device. Try again.") }
        }
    }

    private fun mutation(
        path: String,
        body: JsonElement,
        selectCreated: Boolean = false,
        after: (() -> Unit)? = null,
    ) = mutateWorkspace(selectCreated, after) {
        api.decode<Workspace>(api.request(path, token, body))
    }

    private fun mutateWorkspace(
        selectCreated: Boolean = false,
        after: (() -> Unit)? = null,
        request: suspend () -> Workspace,
    ) {
        if (state.value.busy || !state.value.connected) return
        val expected = epoch
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            serial.withLock {
                try {
                    if (!valid(expected)) return@withLock
                    val result = request()
                    if (valid(expected)) {
                        if (persistOffline(expected, connected = true, receipt = result, selectCreated = selectCreated) {
                                it.copy(base = result.withoutReceipt())
                            }) after?.invoke()
                    }
                } catch (e: Exception) {
                    fail(e, expected, true)
                    // Re-read the authoritative state after an unknown network outcome; never
                    // replay a write.
                    if (
                        e !is CancellationException &&
                            e !is CoverPreparationFailure &&
                            valid(expected) &&
                            (e !is ApiFailure || e.status in listOf(403, 404, 409))
                    )
                        runCatching { workspace(expected) }
                } finally {
                    if (valid(expected)) mutable.update { it.copy(busy = false) }
                }
            }
            if (valid(expected) && state.value.query.isNotBlank()) scheduleSearch()
        }
    }

    internal suspend fun loadListCover(listId: String, coverId: String): Bitmap? {
        if (!hasCurrentSession()) return null
        val expected = epoch
        val accessToken = token ?: return null
        fun canUseCover() = valid(expected) && state.value.workspace.lists.any { it.id == listId && it.coverId == coverId }
        if (!canUseCover()) return null
        val key = listId to coverId
        covers.get(key)?.let { return it }
        return try {
            val id = accountId ?: return null
            val queued = offline.outbox.lastOrNull {
                it.type == "cover.put" && it.data.string("digest") == coverId
            }?.data?.coverBytes()
            val cached = withContext(Dispatchers.IO) { offlineStore.readCover(id, listId, coverId) }
            val bytes = queued ?: cached ?: api.listCover(listId, coverId, accessToken)
            val bitmap = decodeListCover(bytes, coverId) ?: return null
            if (queued == null && cached == null && canUseCover()) {
                withContext(Dispatchers.IO) {
                    runCatching { offlineStore.writeCover(id, listId, coverId, bytes) }
                }
            }
            if (!canUseCover()) return null
            covers.put(key, bitmap)
            bitmap
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
    }

    fun uploadListCover(listId: String, image: Uri) {
        val snapshot = state.value
        val list = snapshot.workspace.lists.find { it.id == listId } ?: return
        val accessToken = token ?: return
        if (snapshot.busy || !snapshot.canEditWorkspace || "list-covers" !in snapshot.capabilities || !hasCurrentSession()) return
        val expected = epoch
        val resolver = getApplication<Application>().contentResolver
        if ("offline-actions" !in snapshot.capabilities) {
            mutateWorkspace(after = { report("List cover updated.") }) {
                val bytes = prepareListCover(resolver, image)
                if (!valid(expected)) throw CancellationException()
                api.decode<Workspace>(api.uploadListCover(listId, bytes, accessToken))
            }
            return
        }
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val bytes = prepareListCover(resolver, image)
                if (!valid(expected)) return@launch
                val digest = workspaceHash(bytes)
                val data = payload(
                    "listId" to listId, "data" to Base64.encodeToString(bytes, Base64.NO_WRAP),
                    "digest" to digest, "expectedCoverId" to list.coverId,
                )
                enqueueAction(expected, makeOfflineAction("cover.put", data, snapshot.workspace), after = {
                    report("List cover saved.")
                })
            } catch (failure: Exception) {
                fail(failure, expected)
            } finally {
                if (valid(expected)) mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun showSavedItem(listId: String, existingId: String?) {
        val snapshot = state.value.workspace
        val itemId = existingId ?: snapshot.createdItemId ?: return
        val list = snapshot.lists.find { it.id == listId } ?: return
        val item = list.items.find { it.id == itemId } ?: return
        if (ui.filters[listId]?.matches(item) == false || !list.matchesItemQuery(item, ui.itemQueries[listId].orEmpty())) {
            ui.hiddenSavedItems[listId] = itemId
        }
    }

    fun loadSharing(listId: String) =
        sharingCall("/api/sharing?listId=${java.net.URLEncoder.encode(listId, "UTF-8")}", null)

    fun sharingAction(kind: String, listId: String, userId: String? = null) {
        val body =
            if (userId == null) payload("listId" to listId)
            else payload("listId" to listId, "userId" to userId)
        if (kind == "leave") mutation("/api/sharing/leave", body)
        else sharingCall("/api/sharing/$kind", body)
    }

    fun invitationLink(listId: String): String? {
        val snapshot = state.value
        if (snapshot.busy || !snapshot.connected || snapshot.selectedId != listId) return null
        val sharing = snapshot.sharing ?: return null
        if (!sharing.owner || !sharing.inviteActive) return null
        if (
            !runCatching {
                    java.time.Instant.parse(sharing.inviteExpiresAt)
                        .isAfter(java.time.Instant.now())
                }
                .getOrDefault(false)
        )
            return null
        val invitation = sharing.token ?: return null
        return "https://gather.takeya.ninja/#invite=$invitation"
    }

    private fun sharingCall(path: String, body: JsonElement?) {
        if (state.value.busy || !state.value.connected) return
        val expected = epoch
        val listId =
            body?.jsonObject?.get("listId")?.jsonPrimitive?.content
                ?: path.substringAfter("listId=", "")
        mutable.update {
            it.copy(
                busy = true,
                error = null,
                sharing = if (body != null) it.sharing?.copy(token = null) else it.sharing,
            )
        }
        viewModelScope.launch {
            serial.withLock {
                if (!valid(expected)) return@withLock
                try {
                    val result = api.decode<Sharing>(api.request(path, token, body))
                    if (
                        valid(expected) &&
                            state.value.selectedId != null &&
                            listId == state.value.selectedId
                    )
                        mutable.update { it.copy(sharing = result) }
                } catch (e: Exception) {
                    fail(e, expected, body != null)
                    if (body != null && e !is CancellationException && valid(expected)) {
                        // Never retain or redisplay a token after an uncertain
                        // replacement/revocation.
                        runCatching {
                                api.decode<Sharing>(
                                    api.request("/api/sharing?listId=$listId", token)
                                )
                            }
                            .onSuccess { safe ->
                                if (valid(expected) && state.value.selectedId == listId)
                                    mutable.update {
                                        it.copy(sharing = safe.copy(token = null), connected = true)
                                    }
                            }
                    }
                } finally {
                    if (valid(expected)) mutable.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun incoming(text: String?, shared: Boolean = false) {
        val invite = extractInvite(text)
        if (invite != null) {
            mutable.update { it.copy(inviteToken = invite, invite = null) }
            if (token != null) previewInvite()
        } else if (shared && !text.isNullOrBlank()) {
            val captureId = state.value.captureId + 1
            ui.prepareCapture(captureId)
            mutable.update { it.copy(capture = text.take(4000), captureId = captureId) }
        }
    }

    fun dismissInvite() {
        mutable.update { it.copy(invite = null, inviteToken = null) }
    }

    fun dismissCapture() {
        mutable.update { it.copy(capture = null) }
    }

    fun previewInvite() {
        val invitationToken = state.value.inviteToken ?: return
        viewModelScope.launch {
            serial.withLock {
                val expected = epoch
                if (
                    token == null ||
                        state.value.username == null ||
                        state.value.inviteToken != invitationToken
                )
                    return@withLock
                try {
                    val result =
                        api.decode<InvitePreview>(
                            api.request(
                                "/api/invitations/preview",
                                token,
                                payload("token" to invitationToken),
                            )
                        )
                    if (valid(expected) && state.value.inviteToken == invitationToken)
                        mutable.update { it.copy(invite = result) }
                } catch (e: Exception) {
                    if (state.value.inviteToken == invitationToken) fail(e, expected)
                }
            }
        }
    }

    fun joinInvite() {
        val invite = state.value.inviteToken ?: return
        mutation("/api/invitations/join", payload("token" to invite), true) {
            if (state.value.inviteToken == invite) dismissInvite()
        }
    }

    fun changePassword(current: String, replacement: String) {
        if (state.value.busy || !state.value.connected) return
        epochs.advance()
        searchJob?.cancel()
        refreshJob?.cancel()
        val expected = epoch
        mutable.update { it.copy(busy = true, error = null, searching = false) }
        viewModelScope.launch {
            serial.withLock {
                if (!valid(expected)) return@withLock
                try {
                    val session =
                        api.decode<Session>(
                            api.request(
                                "/api/native/auth/password",
                                token,
                                payload("currentPassword" to current, "newPassword" to replacement),
                            )
                        )
                    if (valid(expected)) {
                        val fresh = StoredNativeSession(
                            checkNotNull(session.accessToken), checkNotNull(session.userId),
                            checkNotNull(session.username), session.expiresAt,
                        )
                        check(fresh.userId == accountId)
                        vault.write(fresh)
                        storedSession = fresh
                        scheduleSessionExpiry(fresh)
                        epochs.advance()
                        searchJob?.cancel()
                        mutable.update {
                            it.copy(
                                busy = false,
                                searching = false,
                                syncing = false,
                                message = "Password changed. Your other devices are signed out.",
                            )
                        }
                        requestSync()
                    }
                } catch (e: Exception) {
                    if (e is ApiFailure && e.status == 401 && valid(expected)) {
                        try {
                            val session =
                                api.decode<Session>(api.request("/api/native/auth/session", token))
                            if (valid(expected)) {
                                if (session.authenticated)
                                    mutable.update { it.copy(error = e.message) }
                                else clearAccount("Your session ended. Sign in to continue.")
                            }
                        } catch (probe: Exception) {
                            fail(probe, expected)
                        }
                    } else fail(e, expected, true)
                } finally {
                    if (valid(expected)) mutable.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun exportBackup() {
        val snapshot = state.value
        if (snapshot.busy || !snapshot.workspaceReady || !hasCurrentSession()) return
        val expected = epoch
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.Default) { snapshot.workspace.localBackup() }
                if (!valid(expected)) return@launch
                if (document.toByteArray(Charsets.UTF_8).size > BACKUP_LIMIT) {
                    mutable.update { it.copy(error = "This backup is larger than 5 MiB. Sync pending edits or remove some content before exporting.") }
                    return@launch
                }
                pendingExport = PendingExport(document, expected, snapshot.username)
                mutable.update { it.copy(exportReady = true) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                if (valid(expected)) mutable.update { it.copy(error = "Couldn’t prepare the backup. Try again.") }
            } finally {
                if (valid(expected)) mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun exportPickerLaunched() {
        mutable.update { it.copy(exportReady = false) }
    }

    fun consumeExport(): String? {
        val pending = pendingExport
        pendingExport = null
        mutable.update { it.copy(exportReady = false) }
        return pending?.takeIf { valid(it.epoch) && it.username == state.value.username }?.document
    }

    fun finishExport(uri: android.net.Uri?) {
        val document = consumeExport()
        if (uri == null) return
        if (document == null) {
            report("No backup was written. Please export again.")
            return
        }
        val expected = epoch
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(document.toByteArray(Charsets.UTF_8))
                    } ?: error("Couldn’t open that file.")
                }
                if (valid(expected)) report("Backup saved.")
            } catch (e: Exception) {
                if (e !is CancellationException && valid(expected))
                    report("Couldn’t save the backup. Try exporting again to another location.")
            }
        }
    }

    fun importBackup(document: String) {
        val snapshot = state.value
        if (snapshot.busy || !snapshot.workspaceReady || !hasCurrentSession()) return
        val queue = "offline-import" in snapshot.capabilities
        if (!queue && !snapshot.connected) return
        val expected = epoch
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            var legacy: JsonObject? = null
            try {
                val backup = withContext(Dispatchers.Default) {
                    if (document.toByteArray(Charsets.UTF_8).size > BACKUP_LIMIT)
                        throw BackupFailure("Choose a Gather backup no larger than 5 MiB.")
                    val parsed = api.json.parseToJsonElement(document).jsonObject
                    val imported = parseLocalBackup(parsed)
                    if (imported.version > snapshot.workspace.version)
                        throw BackupFailure("This backup needs a newer Gather server.")
                    if (imported.version < 1) throw BackupFailure("This backup uses an unsupported Gather version.")
                    if (queue) importPayload(parsed, imported) else parsed
                }
                if (!valid(expected)) return@launch
                if (queue) enqueueAction(expected, makeOfflineAction("workspace.import", backup, snapshot.workspace))
                else legacy = backup
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (valid(expected)) {
                    val message = if (failure is BackupFailure) failure.message else "Choose a valid Gather backup file."
                    mutable.update { it.copy(error = message) }
                }
            } finally {
                if (valid(expected)) mutable.update { it.copy(busy = false) }
            }
            if (valid(expected)) legacy?.let { mutation("/api/import", it) }
        }
    }

    fun report(message: String) {
        mutable.update { it.copy(message = message) }
    }

    fun dismissNotice() {
        mutable.update { it.copy(error = null, message = null) }
        if (offline.syncNotice != null) {
            val expected = epoch
            viewModelScope.launch {
                try {
                    persistOffline(expected, publish = false) { it.copy(syncNotice = null) }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    // The notice stays dismissed for this app session if disk is unavailable.
                }
            }
        }
    }

    fun appearance(value: String) {
        preferences.edit().putString("theme", value).apply()
        mutable.update { it.copy(appearance = value) }
    }
}
