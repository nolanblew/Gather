package ninja.takeya.gather

import java.util.UUID
import kotlinx.serialization.json.*

internal class BackupFailure(message: String) : IllegalArgumentException(message)

internal const val BACKUP_LIMIT = 5 * 1024 * 1024

internal fun Workspace.localBackup(): String {
    val categories = categories.map { category ->
        payload("id" to category.id, "name" to category.name, "color" to category.color, "position" to category.position)
    }
    val lists = lists.filter(GatherList::owner).map { list ->
        val fields = payload(
            "id" to list.id, "title" to list.title, "description" to list.description,
            "kind" to list.kind, "repeatCadence" to list.repeatCadence, "icon" to list.icon, "color" to list.color,
            "categoryId" to list.categoryId, "favorite" to list.favorite, "archived" to list.archived,
            "isTemplate" to list.isTemplate, "isSample" to list.isSample,
            "createdAt" to list.createdAt, "updatedAt" to list.updatedAt, "position" to list.position,
            "sections" to list.sections.map { payload("id" to it.id, "title" to it.title, "position" to it.position) },
            "items" to list.items.map(ListItem::backupPayload),
        ).toMutableMap()
        if (version >= 3) {
            if (list.numbered) fields["numbered"] = JsonPrimitive(true)
            if (list.places.isNotEmpty()) fields["places"] = JsonArray(list.places.map {
                payload("id" to it.id, "name" to it.name, "kind" to it.kind, "region" to it.region, "countryCode" to it.countryCode)
            })
            if (list.recipients.isNotEmpty())
                fields["recipients"] = JsonArray(list.recipients.map { payload("id" to it.id, "name" to it.name) })
            if (list.personGroupOrder.isNotEmpty()) fields["personGroupOrder"] = JsonArray(list.personGroupOrder.map(::JsonPrimitive))
            if (list.placeGroupOrder.isNotEmpty()) fields["placeGroupOrder"] = JsonArray(list.placeGroupOrder.map(::JsonPrimitive))
        }
        JsonObject(fields)
    }
    return payload("version" to version, "categories" to categories, "lists" to lists).toString()
}

private fun ListItem.backupPayload(): JsonObject {
    val fields = payload(
        "id" to id, "text" to text, "quantity" to quantity, "note" to note,
        "checked" to checked, "sectionId" to sectionId, "position" to position,
    ).toMutableMap()
    if (availability.mode != "unassigned") fields["availability"] = availability.toPayload()
    if (peopleIds.size == 1) fields["recipientId"] = JsonPrimitive(peopleIds.single())
    if (peopleIds.size > 1) fields["recipientIds"] = JsonArray(peopleIds.map(::JsonPrimitive))
    if (description.isNotEmpty()) fields["description"] = JsonArray(description.map(DescriptionBlock::toPayload))
    if (subitems.isNotEmpty()) fields["subitems"] = JsonArray(subitems.map(Subitem::toPayload))
    return JsonObject(fields)
}

private val backupJson = Json { ignoreUnknownKeys = true }

internal fun parseLocalBackup(document: JsonObject): Workspace {
    require(document["version"] is JsonPrimitive && document["categories"] is JsonArray && document["lists"] is JsonArray)
    return backupJson.decodeFromJsonElement<Workspace>(document).also { imported ->
        val ids = imported.backupIds()
        require(ids.all(String::isNotBlank) && ids.size == ids.toSet().size)
        require(imported.categories.size <= 100 && imported.lists.size <= 1_000 && imported.lists.sumOf { it.items.size } <= 20_000)
        val categories = imported.categories.mapTo(mutableSetOf()) { it.id }
        for (list in imported.lists) {
            require(list.sections.size <= 100 && list.items.size <= 5_000 && (list.categoryId == null || list.categoryId in categories))
            val sections = list.sections.mapTo(mutableSetOf()) { it.id }
            val places = list.places.mapTo(mutableSetOf()) { it.id }
            val people = list.recipients.mapTo(mutableSetOf()) { it.id }
            val placeGroups = places.map { "place:$it" } + listOf("unassigned", "anywhere")
            val personGroups = people.map { "person:$it" } + "unassigned"
            require(list.placeGroupOrder.all { it in placeGroups } && list.personGroupOrder.all { it in personGroups })
            list.items.forEach { item ->
                require(item.sectionId == null || item.sectionId in sections)
                require(item.availability.placeIds.all { it in places } && item.peopleIds.all { it in people })
                require(item.subitems.size <= 100 && item.description.size <= 100)
            }
        }
    }
}

internal fun importPayload(document: JsonObject, imported: Workspace): JsonObject =
    payload("workspace" to document, "contentIds" to JsonObject(imported.backupIds().associateWith { JsonPrimitive(UUID.randomUUID().toString()) }))

private fun Workspace.backupIds(): List<String> = categories.map { it.id } + lists.flatMap { list ->
    listOf(list.id) + list.sections.map { it.id } + list.places.map { it.id } + list.recipients.map { it.id } +
        list.items.flatMap { item -> listOf(item.id) + item.subitems.map { it.id } }
}

internal fun Workspace.importedLocally(data: JsonObject, username: String): Workspace {
    val source = backupJson.decodeFromJsonElement<Workspace>(data.getValue("workspace"))
    val ids = data.getValue("contentIds").jsonObject.mapValues { it.value.jsonPrimitive.content }
    fun mapped(old: String): String = checkNotNull(ids[old])
    val importedCategoryIds = source.categories.mapTo(mutableSetOf()) { mapped(it.id) }
    val importedListIds = source.lists.mapTo(mutableSetOf()) { mapped(it.id) }
    if (categories.any { it.id in importedCategoryIds } || lists.any { it.id in importedListIds }) return this
    val categoryStart = (categories.maxOfOrNull { it.position } ?: -1) + 1
    val listStart = (lists.maxOfOrNull { it.position } ?: -1) + 1
    val importedCategories = source.categories.sortedBy { it.position }.mapIndexed { i, category ->
        category.copy(id = mapped(category.id), position = categoryStart + i)
    }
    val importedLists = source.lists.sortedBy { it.position }.mapIndexed { i, list ->
        list.remapContent(ids).copy(
            id = mapped(list.id), position = listStart + i, isSample = false,
            categoryId = list.categoryId?.let(::mapped), role = "owner", ownerUsername = username, shared = false, coverId = null,
        ).withLocalOrderToken()
    }
    return copy(categories = categories + importedCategories, lists = lists + importedLists)
}
