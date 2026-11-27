package ninja.takeya.gather

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The server snapshot and outbox are committed together, including any in-flight request. */
@Serializable
internal data class OfflineWorkspace(
    val base: Workspace? = null,
    val outbox: List<OfflineAction> = emptyList(),
    val capabilities: Set<String> = emptySet(),
    val syncNotice: String? = null,
) {
    fun projected(username: String): Workspace {
        var workspace = base ?: Workspace()
        val aliases = mutableMapOf<String, String>()
        for (action in outbox) {
            val remapped = action.remap(aliases)
            when (val next = remapped.rebase(workspace)) {
                is ActionPlan.Write -> workspace = workspace.applyOffline(remapped, next.data, username)
                is ActionPlan.Done -> aliases.putAll(next.resolvedIds)
            }
        }
        return workspace
    }
}

@Serializable
internal data class OfflineAction(
    val id: String,
    val type: String,
    val data: JsonObject,
    val createdAt: String,
    val move: MoveIntent? = null,
    val submitted: JsonObject? = null,
    val reorderBucket: ReorderBucket? = null,
)

@Serializable
internal data class MoveIntent(val before: String?, val after: String?)

@Serializable
internal data class ReorderBucket(val sectionId: String?, val checked: Boolean?)

internal class OfflineWorkspaceStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "workspaces")
    private val json = Json { ignoreUnknownKeys = true }

    private fun accountKey(accountId: String) = workspaceHash(BuildConfig.SERVER_URL.trimEnd('/') + "\n" + accountId)

    private fun file(accountId: String) = AtomicFile(File(directory, "${accountKey(accountId)}.json"))

    private fun coverDirectory(accountId: String, listId: String) =
        File(File(directory, accountKey(accountId)), workspaceHash(listId))

    fun readCover(accountId: String, listId: String, digest: String): ByteArray? =
        try {
            AtomicFile(File(coverDirectory(accountId, listId), workspaceHash(digest))).readFully()
        } catch (_: FileNotFoundException) {
            null
        }

    fun writeCover(accountId: String, listId: String, digest: String, bytes: ByteArray) {
        val folder = coverDirectory(accountId, listId)
        val name = workspaceHash(digest)
        writeAtomic(AtomicFile(File(folder, name)), bytes)
        folder.listFiles()?.filter { it.name != name && !it.name.endsWith(".new") }?.forEach { it.delete() }
    }

    fun read(accountId: String): OfflineWorkspace =
        try {
            json.decodeFromString<OfflineWorkspace>(file(accountId).readFully().toString(Charsets.UTF_8))
        } catch (_: FileNotFoundException) {
            OfflineWorkspace()
        }

    fun write(accountId: String, value: OfflineWorkspace) {
        writeAtomic(file(accountId), json.encodeToString(value).toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomic(target: AtomicFile, bytes: ByteArray) {
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (failure: Exception) {
            target.failWrite(stream)
            throw failure
        }
    }
}

internal fun workspaceHash(value: String): String =
    workspaceHash(value.toByteArray(Charsets.UTF_8))

internal fun workspaceHash(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }

internal fun Workspace.withoutReceipt() = copy(
    createdItemId = null,
    createdListId = null,
    createdSubitemId = null,
    joinedListId = null,
    resolvedIds = emptyMap(),
    resolvedImages = emptyMap(),
    resolvedCoverId = null,
)

internal fun localWorkspaceSearch(workspace: Workspace, scope: String, categoryId: String?, query: String): SearchResponse {
    val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    fun matches(text: String) = words.all { text.contains(it, ignoreCase = true) }
    val matches = visibleLists(workspace, scope, categoryId).mapNotNull { list ->
        val match = when {
            matches(list.title) -> SearchMatch(list.title, "title")
            matches(list.description) -> SearchMatch(list.description.take(180), "description")
            else -> list.sections.firstOrNull { matches(it.title) }?.let { SearchMatch(it.title, "section", sectionId = it.id) }
                ?: list.items.firstNotNullOfOrNull { item ->
                    val text = listOf(item.text, item.descriptionText, item.quantity, item.subitems.joinToString(" ") { it.text })
                        .firstOrNull(::matches)
                    text?.let { SearchMatch(it.take(180), "item", item.id, item.sectionId) }
                }
        }
        match?.let { SearchResult(list.id, match = it) }
    }
    return SearchResponse(results = matches.take(60), provider = "local")
}
