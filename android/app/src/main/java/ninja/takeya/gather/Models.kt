package ninja.takeya.gather

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class Category(
    val id: String,
    val name: String,
    val color: String = "sage",
    val position: Int = 0,
)

@Serializable data class Section(val id: String, val title: String, val position: Int = 0)

@Serializable
data class ListItem(
    val id: String,
    val text: String,
    val quantity: String = "",
    val note: String = "",
    val checked: Boolean = false,
    val sectionId: String? = null,
    val position: Int = 0,
)

@Serializable
data class GatherList(
    val id: String,
    val title: String,
    val description: String = "",
    val kind: String = "checklist",
    val repeatCadence: String = "manual",
    val icon: String = "list",
    val color: String = "sage",
    val categoryId: String? = null,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val isTemplate: Boolean = false,
    val isSample: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val position: Int = 0,
    val sections: List<Section> = emptyList(),
    val items: List<ListItem> = emptyList(),
    val role: String = "owner",
    val ownerUsername: String = "",
    val shared: Boolean = false,
) {
    val owner
        get() = role == "owner"

    val checkedCount
        get() = items.count { it.checked }
}

@Serializable
data class Workspace(
    val version: Int = 2,
    val categories: List<Category> = emptyList(),
    val lists: List<GatherList> = emptyList(),
    val createdListId: String? = null,
    val joinedListId: String? = null,
)

@Serializable
data class Session(
    val authenticated: Boolean = false,
    val userId: String? = null,
    val username: String? = null,
    val accessToken: String? = null,
)

@Serializable data class Member(val userId: String, val username: String)

@Serializable
data class Sharing(
    val owner: Boolean,
    val ownerUsername: String,
    val role: String,
    val members: List<Member> = emptyList(),
    val inviteActive: Boolean = false,
    val inviteExpiresAt: String? = null,
    val token: String? = null,
)

@Serializable
data class InvitePreview(
    val listId: String,
    val listTitle: String,
    val ownerUsername: String,
    val expiresAt: String,
    val alreadyMember: Boolean,
)

@Serializable
data class SearchMatch(
    val text: String,
    val field: String,
    val itemId: String? = null,
    val sectionId: String? = null,
)

@Serializable
data class SearchResult(
    val listId: String,
    val score: Double = 0.0,
    val match: SearchMatch,
    val matchType: String = "exact",
)

@Serializable
data class SearchResponse(
    val results: List<SearchResult> = emptyList(),
    val provider: String = "fuzzy",
    val status: String = "ready",
    val reason: String? = null,
)

fun payload(vararg entries: Pair<String, Any?>): JsonObject = buildJsonObject {
    entries.forEach { (key, value) ->
        put(
            key,
            when (value) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            },
        )
    }
}

fun visibleLists(workspace: Workspace, scope: String, categoryId: String?): List<GatherList> =
    workspace.lists
        .filter {
            (categoryId == null || it.categoryId == categoryId) &&
                when (scope) {
                    "favorites" -> it.favorite && !it.archived && !it.isTemplate
                    "templates" -> it.isTemplate && !it.archived
                    "archive" -> it.archived
                    "all" -> true
                    else -> !it.archived && !it.isTemplate
                }
        }
        .sortedBy { it.position }

fun sortedGroup(list: GatherList, sectionId: String?): List<ListItem> =
    list.items.filter { it.sectionId == sectionId }.sortedBy { it.position }

fun extractInvite(text: String?): String? {
    if (text == null) return null
    return runCatching {
            val uri = java.net.URI(text.trim())
            if (uri.scheme != "https" || uri.host != "gather.takeya.ninja") return null
            val fragment = uri.rawFragment ?: return null
            val value =
                fragment.split('&').firstOrNull { it.startsWith("invite=") }?.substringAfter('=')
                    ?: return null
            java.net.URLDecoder.decode(value, "UTF-8").takeIf {
                it.isNotBlank() && it.length <= 512
            }
        }
        .getOrNull()
}
