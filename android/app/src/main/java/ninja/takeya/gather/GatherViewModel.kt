package ninja.takeya.gather

import android.app.Application
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
) {
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
    private val preferences = application.getSharedPreferences("appearance", 0)
    private val mutable =
        MutableStateFlow(
            GatherState(appearance = preferences.getString("theme", "system") ?: "system")
        )
    val state: StateFlow<GatherState> = mutable.asStateFlow()
    private val serial = Mutex()
    private var token: String? = vault.read()
    private val epochs = SessionEpoch()
    private val epoch
        get() = epochs.current

    private var searchJob: Job? = null
    private var authJob: Job? = null
    private var refreshJob: Job? = null

    // Kept out of both Compose state and saved instance bundles (backups can be 5 MiB).
    private data class PendingExport(val document: String, val epoch: Long, val username: String?)

    private var pendingExport: PendingExport? = null

    init {
        authJob =
            viewModelScope.launch {
                if (token == null) mutable.update { it.copy(starting = false) } else verifySession()
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
            if (error is ApiFailure) error.message
            else "Can’t connect to Gather. Check your connection and try again."
        mutable.update {
            it.copy(
                error =
                    if (interruptedWrite && error !is ApiFailure)
                        "$message The request may have reached Gather. Refresh before trying it again."
                    else message,
                connected = error is ApiFailure && it.connected,
            )
        }
    }

    private suspend fun workspace(expected: Long) {
        val result = api.decode<Workspace>(api.request("/api/workspace", token))
        if (valid(expected)) mutable.update { current -> current.adoptWorkspace(result) }
    }

    private suspend fun verifySession() {
        val expected = epoch
        try {
            val session = api.decode<Session>(api.request("/api/native/auth/session", token))
            if (!valid(expected)) return
            if (!session.authenticated) {
                clearAccount()
                return
            }
            mutable.update { it.copy(username = session.username) }
            serial.withLock { if (valid(expected)) workspace(expected) }
            if (state.value.inviteToken != null) previewInvite()
        } catch (e: Exception) {
            fail(e, expected)
        } finally {
            if (valid(expected)) mutable.update { it.copy(starting = false) }
        }
    }

    fun authenticate(username: String, password: String, register: Boolean) {
        if (state.value.busy) return
        epochs.advance()
        val expected = epoch
        authJob?.cancel()
        refreshJob?.cancel()
        token = null
        vault.clear()
        mutable.update {
            it.copy(
                busy = true,
                error = null,
                message = null,
                workspace = Workspace(),
                username = null,
                connected = false,
            )
        }
        authJob =
            viewModelScope.launch {
                try {
                    val endpoint = if (register) "register" else "login"
                    val credentials = payload("username" to username.trim(), "password" to password)
                    val response = api.request("/api/native/auth/$endpoint", body = credentials)
                    val session = api.decode<Session>(response)
                    if (!valid(expected)) return@launch
                    val fresh = session.accessToken ?: error("Missing secure session")
                    vault.write(fresh)
                    token = fresh
                    mutable.update { it.copy(username = session.username) }
                    serial.withLock { if (valid(expected)) workspace(expected) }
                    if (state.value.inviteToken != null) previewInvite()
                } catch (e: Exception) {
                    if (e is ApiFailure && e.status == 401 && valid(expected))
                        mutable.update { it.copy(error = e.message) }
                    else fail(e, expected)
                } finally {
                    if (valid(expected)) mutable.update { it.copy(busy = false, starting = false) }
                }
            }
    }

    private fun clearAccount(error: String? = null, message: String? = null) {
        ui.clearAccount()
        pendingExport = null
        refreshJob?.cancel()
        epochs.advance()
        token = null
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

    fun logout() {
        if (state.value.busy) return
        val oldToken = token
        val expected = epoch
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            serial.withLock {
                if (!valid(expected)) return@withLock
                try {
                    api.request("/api/native/auth/logout", oldToken, payload())
                    if (valid(expected)) clearAccount(message = "Signed out on this phone.")
                } catch (e: Exception) {
                    fail(e, expected)
                } finally {
                    if (valid(expected)) mutable.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun refresh() {
        if (token == null || state.value.busy || state.value.starting) return
        if (refreshJob?.isActive == true) return
        if (state.value.username == null) {
            mutable.update { it.copy(starting = true, error = null) }
            authJob = viewModelScope.launch { verifySession() }
            return
        }
        val expected = epoch
        refreshJob =
            viewModelScope.launch {
                serial.withLock {
                    if (!valid(expected)) return@withLock
                    try {
                        workspace(expected)
                        if (valid(expected)) mutable.update { it.copy(error = null) }
                    } catch (e: Exception) {
                        fail(e, expected)
                    }
                }
                if (valid(expected) && state.value.query.isNotBlank()) scheduleSearch()
            }
    }

    fun canRetrySession(): Boolean =
        token != null &&
            state.value.username == null &&
            !state.value.starting &&
            !state.value.busy &&
            !state.value.connected &&
            state.value.error != null

    fun select(id: String?) {
        mutable.update { it.copy(selectedId = id, sharing = null) }
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
        mutable.update { it.copy(searching = true) }
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
                        fail(e, expected)
                        mutable.update { it.copy(searching = false) }
                    }
                }
            }
    }

    fun action(
        type: String,
        data: JsonObject,
        selectCreated: Boolean = false,
        onSuccess: (() -> Unit)? = null,
    ) =
        mutation(
            "/api/actions",
            payload("type" to type).let { JsonObject(it + ("payload" to data)) },
            selectCreated,
            onSuccess,
        )

    private fun mutation(
        path: String,
        body: JsonElement,
        selectCreated: Boolean = false,
        after: (() -> Unit)? = null,
    ) {
        if (state.value.busy || !state.value.connected) return
        val expected = epoch
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            serial.withLock {
                try {
                    if (!valid(expected)) return@withLock
                    val result = api.decode<Workspace>(api.request(path, token, body))
                    if (valid(expected)) {
                        mutable.update { current -> current.adoptWorkspace(result, selectCreated) }
                        after?.invoke()
                    }
                } catch (e: Exception) {
                    fail(e, expected, true)
                    // Re-read the authoritative state after an unknown network outcome; never
                    // replay a write.
                    if (
                        e !is CancellationException &&
                            valid(expected) &&
                            (e !is ApiFailure || e.status in listOf(403, 404))
                    )
                        runCatching { workspace(expected) }
                } finally {
                    if (valid(expected)) mutable.update { it.copy(busy = false) }
                }
            }
            if (valid(expected) && state.value.query.isNotBlank()) scheduleSearch()
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
                        val fresh = session.accessToken ?: error("Missing secure session")
                        vault.write(fresh)
                        token = fresh
                        epochs.advance()
                        searchJob?.cancel()
                        mutable.update {
                            it.copy(
                                busy = false,
                                searching = false,
                                message = "Password changed. Your other devices are signed out.",
                            )
                        }
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
        if (state.value.busy || !state.value.connected) return
        val expected = epoch
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            serial.withLock {
                if (!valid(expected)) return@withLock
                try {
                    val document = api.request("/api/export", token)
                    if (valid(expected)) {
                        pendingExport = PendingExport(document, expected, state.value.username)
                        mutable.update { it.copy(exportReady = true) }
                    }
                } catch (e: Exception) {
                    fail(e, expected)
                } finally {
                    if (valid(expected)) mutable.update { it.copy(busy = false) }
                }
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
        try {
            require(document.toByteArray().size <= 5 * 1024 * 1024) {
                "This backup is larger than 5 MiB."
            }
            mutation("/api/import", api.json.parseToJsonElement(document))
        } catch (e: Exception) {
            mutable.update {
                it.copy(
                    error =
                        if (document.toByteArray().size > 5 * 1024 * 1024)
                            "Choose a Gather backup no larger than 5 MiB. Your lists haven’t changed."
                        else
                            "This isn’t a valid Gather backup. Your lists haven’t changed. Choose another file."
                )
            }
        }
    }

    fun report(message: String) {
        mutable.update { it.copy(message = message) }
    }

    fun dismissNotice() {
        mutable.update { it.copy(error = null, message = null) }
    }

    fun appearance(value: String) {
        preferences.edit().putString("theme", value).apply()
        mutable.update { it.copy(appearance = value) }
    }
}
