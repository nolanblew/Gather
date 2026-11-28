package ninja.takeya.gather

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

private val actionJson = Json { ignoreUnknownKeys = true }
private val createKeys = mapOf(
    "category.create" to "categoryId",
    "list.create" to "listId",
    "list.clone" to "createdListId",
    "section.create" to "sectionId",
    "item.create" to "itemId",
    "item.createWithPeople" to "itemId",
    "place.create" to "placeId",
    "recipient.create" to "recipientId",
    "subitem.create" to "subitemId",
)

private fun newId() = UUID.randomUUID().toString()

internal fun makeOfflineAction(type: String, data: JsonObject, workspace: Workspace): OfflineAction {
    val fields = data.toMutableMap()
    createKeys[type]?.let { fields.putIfAbsent(it, JsonPrimitive(newId())) }
    val list = workspace.lists.find { it.id == data.string("listId") }
    if (type == "list.clone" && list != null) {
        val ids = list.sections.map { it.id } + list.items.flatMap { item -> listOf(item.id) + item.subitems.map { it.id } } +
            list.places.map { it.id } + list.recipients.map { it.id }
        fields["contentIds"] = JsonObject(ids.associateWith { JsonPrimitive(newId()) })
    }
    for ((key, idKey) in listOf("newPlaces" to "placeId", "newRecipients" to "recipientId")) {
        (data[key] as? JsonArray)?.let { drafts ->
            fields[key] = JsonArray(drafts.map { draft -> JsonObject(draft.jsonObject + (idKey to JsonPrimitive(newId()))) })
        }
    }
    (data["newRecipient"] as? JsonObject)?.let { draft ->
        fields["newRecipient"] = JsonObject(draft + ("recipientId" to JsonPrimitive(newId())))
    }
    val item = list?.items?.find { it.id == data.string("itemId") }
    if (item != null && type in setOf("item.update", "item.updateWithPeople") && ("description" in data || "note" in data)) {
        fields.putIfAbsent("expectedDescription", descriptionPayload(item.description))
        fields.putIfAbsent("expectedNote", JsonPrimitive(item.note))
    }
    val siblings = list?.moveSiblings(type, data)
    val visible = data.strings("itemIds")
    val moved = list?.items?.firstOrNull { it.id == data.string("movedItemId") }
    val index = data.number("index")
    val move = when {
        siblings != null && index != null -> moveIntent(siblings, index)
        type == "item.reorderVisible" && moved != null && moved.id in visible ->
            moveIntent(visible.filterNot { it == moved.id }, visible.indexOf(moved.id))
        else -> null
    }
    val anchor = moved ?: if (type == "item.reorderVisible") list?.items?.firstOrNull { it.id in visible } else null
    val bucket = anchor?.let {
        ReorderBucket(it.sectionId, it.checked.takeIf { list?.kind == "checklist" && !list.numbered && !list.isTemplate })
    }
    return OfflineAction(newId(), type, JsonObject(fields), Instant.now().toString(), move, reorderBucket = bucket)
}

private fun moveIntent(siblingIds: List<String>, index: Int): MoveIntent {
    val destination = index.coerceIn(0, siblingIds.size)
    return MoveIntent(before = siblingIds.getOrNull(destination), after = siblingIds.getOrNull(destination - 1))
}

private fun MoveIntent?.destination(siblingIds: List<String>, fallback: Int): Int {
    val before = this?.before?.let(siblingIds::indexOf)?.takeIf { it >= 0 }
    val after = this?.after?.let(siblingIds::indexOf)?.takeIf { it >= 0 }
    return before ?: after?.plus(1) ?: fallback.coerceIn(0, siblingIds.size)
}

private fun GatherList.moveSiblings(type: String, data: JsonObject): List<String>? = when (type) {
    "section.move" -> sections.sortedBy { it.position }.map { it.id }.filterNot { it == data.string("sectionId") }
    "item.move" -> sortedGroup(this, data.string("sectionId")).map { it.id }.filterNot { it == data.string("itemId") }
    "subitem.move" -> items.find { it.id == data.string("itemId") }?.subitems?.map { it.id }
        ?.filterNot { it == data.string("subitemId") }
    else -> null
}

internal sealed interface ActionPlan {
    data class Write(val data: JsonObject) : ActionPlan
    data class Done(val notice: String? = null, val resolvedIds: Map<String, String> = emptyMap()) : ActionPlan
}

private fun unavailable() = ActionPlan.Done("A change couldn’t sync because its original item or list is no longer available.")

internal fun OfflineAction.rebase(workspace: Workspace): ActionPlan {
    val fields = data.toMutableMap()
    val categoryId = data.string("categoryId")
    if (type == "workspace.import") return ActionPlan.Write(data)
    if (type == "samples.remove") return ActionPlan.Write(data)
    if (type == "category.create")
        return if (workspace.categories.any { it.id == categoryId }) ActionPlan.Done() else ActionPlan.Write(data)
    if (type.startsWith("category.")) {
        if (workspace.categories.none { it.id == categoryId }) return if (type == "category.delete") ActionPlan.Done() else unavailable()
        return ActionPlan.Write(data)
    }
    if (categoryId != null && workspace.categories.none { it.id == categoryId }) fields["categoryId"] = JsonNull
    if (type == "list.create")
        return if (workspace.lists.any { it.id == data.string("listId") }) ActionPlan.Done() else ActionPlan.Write(JsonObject(fields))
    if (type == "list.clone" && workspace.lists.any { it.id == data.string("createdListId") }) return ActionPlan.Done()

    val list = workspace.lists.find { it.id == data.string("listId") }
        ?: return if (type == "list.delete") ActionPlan.Done() else unavailable()
    val item = list.items.find { it.id == data.string("itemId") }
    val subitem = item?.subitems?.find { it.id == data.string("subitemId") }
    val place = list.places.find { it.id == data.string("placeId") }
    val person = list.recipients.find { it.id == data.string("recipientId") }
    val sectionId = data.string("sectionId")
    val sectionExists = sectionId == null || list.sections.any { it.id == sectionId }

    when {
        type.startsWith("subitem.") -> {
            if (item == null || list.kind != "checklist") return unavailable()
            if (type != "subitem.create" && subitem == null)
                return if (type == "subitem.delete") ActionPlan.Done() else unavailable()
        }
        type in setOf("item.update", "item.updateWithPeople", "item.move", "item.delete", "item.updateDescription") && item == null ->
            return if (type == "item.delete") ActionPlan.Done() else unavailable()
        type in setOf("section.update", "section.delete", "section.move") && (!sectionExists || sectionId == null) ->
            return if (type == "section.delete") ActionPlan.Done() else unavailable()
        type == "place.update" && place == null || type == "recipient.update" && person == null -> return unavailable()
        type == "place.delete" && place == null || type == "recipient.delete" && person == null -> return ActionPlan.Done()
    }

    when (type) {
        "cover.put" -> if (data.string("expectedCoverId") != list.coverId)
            return ActionPlan.Done("A newer list cover was kept.")
        "section.create" -> if (list.sections.any { it.id == sectionId }) return ActionPlan.Done()
        "subitem.create" -> if (subitem != null) return ActionPlan.Done()
        "list.delete" -> if (!list.owner) return unavailable()
        "list.reset" -> if (list.kind != "checklist" || list.isTemplate || list.archived) return unavailable()
        "list.deleteDone" -> {
            if (list.kind != "checklist" || list.isTemplate || list.archived) return unavailable()
            val confirmed = data.strings("itemIds").toSet()
            val eligible = list.items.filter { it.checked && it.id in confirmed }.map { it.id }
            if (eligible.isEmpty()) return ActionPlan.Done()
            fields["itemIds"] = strings(eligible)
        }
        "list.update" -> if (!list.owner) fields.keys.removeAll(setOf("numbered", "kind", "repeatCadence", "isTemplate"))
        "place.create", "recipient.create" -> {
            val proposed = if (type == "place.create") data.asPlace() else null
            val existing = if (proposed != null) list.places.firstOrNull { samePlace(it, proposed) }?.id
                else list.recipients.firstOrNull { labelKey(it.name) == labelKey(data.string("name").orEmpty()) }?.id
            if (existing != null) {
                val requested = data.string(if (proposed != null) "placeId" else "recipientId") ?: return ActionPlan.Done()
                return ActionPlan.Done(resolvedIds = mapOf(requested to existing))
            }
        }
        "place.delete" -> if (data.string("expectedAssignmentToken") != list.placeAssignmentToken(checkNotNull(place)))
            return ActionPlan.Done("A place has new details or assignments and was kept.")
        "recipient.delete" -> if (data.string("expectedAssignmentToken") != list.recipientAssignmentToken(checkNotNull(person)))
            return ActionPlan.Done("A person has new details or assignments and was kept.")
        "item.move" -> if (!sectionExists) return ActionPlan.Done("A move couldn’t sync because its destination section was removed.")
        "item.create", "item.createWithPeople" -> {
            if (item != null) return ActionPlan.Done()
            if (!sectionExists) fields["sectionId"] = JsonNull
            if (list.kind == "notes") fields.remove("checked")
        }
        "item.update", "item.updateWithPeople" -> {
            if (!sectionExists) fields.remove("sectionId")
            if (list.kind == "notes") fields.remove("checked")
        }
        "group.reorder" -> {
            val kind = data.string("kind")
            val current = if (kind == "person") list.personGroupOrder else list.placeGroupOrder
            val keys = if (kind == "person") list.recipients.map { "person:${it.id}" } + "unassigned"
                else list.places.map { "place:${it.id}" } + listOf("unassigned", "anywhere")
            val desired = data.strings("groupIds").filter { it in keys }
            fields["expectedGroupIds"] = strings(current)
            fields["groupIds"] = strings(mergeGroupOrder(current, desired, data.strings("expectedGroupIds")))
        }
        "item.reorderVisible" -> {
            fields.remove("movedItemId")
            val desired = data.strings("itemIds")
            val existing = desired.mapNotNull { id -> list.items.find { it.id == id } }
            val first = existing.firstOrNull() ?: return ActionPlan.Done()
            val section = if (reorderBucket != null) reorderBucket.sectionId else first.sectionId
            val groupIsSplit = list.kind == "checklist" && !list.numbered && !list.isTemplate
            val checked = (reorderBucket?.checked ?: first.checked).takeIf { groupIsSplit }
            val eligible = existing.filter { it.sectionId == section && (checked == null || it.checked == checked) }
            if (eligible.size < 2) return ActionPlan.Done()
            val movedId = data.string("movedItemId")
            val ids = if (movedId != null && move != null) {
                if (eligible.none { it.id == movedId }) return ActionPlan.Done("An item moved or changed completion on another device.")
                val current = eligible.sortedBy { it.position }.map { it.id }.filterNot { it == movedId }.toMutableList()
                current.add(move.destination(current, desired.indexOf(movedId)), movedId)
                current
            } else eligible.map { it.id }
            fields["itemIds"] = strings(ids)
        }
        "item.bulkAdd" -> {
            val ids = data.strings("itemIds").filter { id -> list.items.any { it.id == id } }
            if (ids.isEmpty()) return ActionPlan.Done()
            val hasPlaces = data.strings("placeIds").isNotEmpty() || (data["newPlaces"] as? JsonArray)?.isNotEmpty() == true
            val acknowledged = data.strings("expectedAnywhereItemIds").toSet()
            val eligible = if (hasPlaces && "expectedAnywhereItemIds" in data) ids.filterNot { id ->
                list.items.any { it.id == id && it.availability.mode == "anywhere" && id !in acknowledged }
            } else ids
            if (eligible.isEmpty()) return ActionPlan.Done("Items newly marked Anywhere kept their places.")
            fields["itemIds"] = strings(eligible)
            fields["expectedAnywhereItemIds"] = strings(eligible.filter { it in acknowledged })
            fields["placeIds"] = strings(data.strings("placeIds").filter { id -> list.places.any { it.id == id } })
        }
    }

    if (type in setOf("item.create", "item.createWithPeople", "item.update", "item.updateWithPeople", "item.bulkAdd")) {
        rebaseAssignments(fields, data, list, item.takeIf { type.startsWith("item.update") })
        if (type == "item.bulkAdd" && listOf("placeIds", "recipientIds", "newPlaces", "newRecipients").none {
                (fields[it] as? JsonArray)?.isNotEmpty() == true
            } && fields["recipientId"]?.let { it != JsonNull } != true && "newRecipient" !in fields)
            return ActionPlan.Done("A place or person in a pending change was removed.")
    }
    if (item != null && type in setOf("item.update", "item.updateWithPeople", "item.updateDescription")) {
        rebaseItemDetails(fields, data, item)
    }
    if (list.kind == "notes" && type in setOf("item.create", "item.createWithPeople", "item.update", "item.updateWithPeople"))
        fields.keys.removeAll(setOf("subitems", "expectedSubitems"))
    val siblings = list.moveSiblings(type, JsonObject(fields))
    if (siblings != null) {
        val index = move.destination(siblings, data.number("index") ?: 0)
        fields["index"] = JsonPrimitive(index)
        if (type == "subitem.move") fields["expectedSubitemIds"] = strings(checkNotNull(item).subitems.map { it.id })
    }
    val needsOrder = type == "item.reorderVisible" ||
        type == "list.update" && data.boolean("numbered")?.let { it != list.numbered } == true ||
        type in setOf("item.create", "item.createWithPeople", "item.delete", "item.move", "section.create", "section.delete", "section.move") && list.numbered ||
        type in setOf("item.update", "item.updateWithPeople") && "sectionId" in fields && fields["sectionId"].idValue() != item?.sectionId && list.numbered
    if (needsOrder || "expectedOrderToken" in fields) fields["expectedOrderToken"] = JsonPrimitive(list.orderToken ?: list.localOrderToken())
    return ActionPlan.Write(JsonObject(fields))
}

private fun mergeGroupOrder(current: List<String>, desired: List<String>, expected: List<String>): List<String> {
    val extras = current.filter { it !in expected && it !in desired }
    val result = desired.toMutableList()
    for (key in extras) {
        val following = current.drop(current.indexOf(key) + 1).firstOrNull { it in result }
        result.add(following?.let(result::indexOf) ?: result.size, key)
    }
    return result
}

private fun rebaseAssignments(
    fields: MutableMap<String, JsonElement>,
    data: JsonObject,
    list: GatherList,
    item: ListItem?,
) {
    val availablePlaces = list.places.mapTo(mutableSetOf()) { it.id }
    data.decode<Availability>("availability")?.let { desired ->
        val current = item?.availability
        val expected = data.decode<Availability>("expectedAvailability")
        val desiredIds = if (desired.mode == "selected" && current?.mode == "selected" && expected?.mode == "selected")
            mergeIds(expected.placeIds, desired.placeIds, current.placeIds) else desired.placeIds
        val ids = desiredIds.filter { it in availablePlaces }.take(25)
        fields["availability"] = if (desired.mode == "selected" && ids.isEmpty() && "newPlaces" !in fields)
            Availability().toPayload() else desired.copy(placeIds = ids).toPayload()
        if (current != null) fields["expectedAvailability"] = current.toPayload()
    }

    val known = list.recipients.mapTo(mutableSetOf()) { it.id }
    if ("recipientIds" in data || "newRecipients" in data) {
        val desired = data.strings("recipientIds")
        val ids = if (item != null) mergeIds(data.strings("expectedRecipientIds"), desired, item.peopleIds) else desired
        fields["recipientIds"] = strings(ids.filter { it in known }.take(200))
        if (item != null) fields["expectedRecipientIds"] = strings(item.peopleIds)
    } else if ("recipientId" in data || "newRecipient" in data) {
        val desired = listOfNotNull(data.string("recipientId")?.takeIf { it in known })
        val expected = listOfNotNull(data.string("expectedRecipientId"))
        val merged = if (item != null) mergeIds(expected, desired, item.peopleIds) else desired
        if (item != null && (item.peopleIds.size > 1 || merged.size > 1 || "newRecipient" in data && merged.isNotEmpty())) {
            fields.remove("recipientId")
            fields.remove("expectedRecipientId")
            fields["recipientIds"] = strings(merged.take(200))
            fields["expectedRecipientIds"] = strings(item.peopleIds)
            fields.remove("newRecipient")?.let { fields["newRecipients"] = JsonArray(listOf(it)) }
        } else {
            if ("recipientId" in data) fields["recipientId"] = merged.firstOrNull()?.let(::JsonPrimitive) ?: JsonNull
            if (item != null) fields["expectedRecipientId"] = item.peopleIds.firstOrNull()?.let(::JsonPrimitive) ?: JsonNull
        }
    }
}

private fun mergeIds(expected: List<String>, desired: List<String>, current: List<String>): List<String> =
    ((current - (expected.toSet() - desired.toSet())) + (desired - expected.toSet())).distinct().sorted()

private fun rebaseItemDetails(fields: MutableMap<String, JsonElement>, data: JsonObject, item: ListItem) {
    if ("description" in data || "note" in data) {
        val expectedBlocks = data.decode<List<DescriptionBlock>>("expectedDescription").orEmpty()
        val desiredBlocks = data.decode<List<DescriptionBlock>>("description") ?: expectedBlocks
        val expectedNote = data.string("expectedNote").orEmpty()
        val desiredNote = data.string("note") ?: expectedNote
        fun withNote(blocks: List<DescriptionBlock>, note: String) = blocks.ifEmpty {
            note.takeIf(String::isNotEmpty)?.let { listOf(DescriptionBlock(runs = listOf(DescriptionRun(it)))) }.orEmpty()
        }

        if ("expectedNote" !in data) {
            val merged = mergeSequence(expectedBlocks, desiredBlocks, item.description)
            fields["description"] = descriptionPayload(merged)
            fields["expectedDescription"] = descriptionPayload(item.description)
        } else {
            val merged = mergeSequence(
                withNote(expectedBlocks, expectedNote),
                withNote(desiredBlocks, desiredNote),
                withNote(item.description, item.note),
            )
            val plain = merged.singleOrNull()?.takeIf {
                desiredBlocks.isEmpty() && it.type == "paragraph" && it.text.length <= 4000 &&
                    it.runs.all { run -> run == DescriptionRun(run.text) }
            }
            val blocks = if (plain != null) emptyList() else merged
            val note = plain?.text.orEmpty()
            if (blocks != item.description) {
                fields["description"] = descriptionPayload(blocks)
                fields["expectedDescription"] = descriptionPayload(item.description)
            } else {
                fields.remove("description")
                fields.remove("expectedDescription")
            }
            if (note != item.note) {
                fields["note"] = JsonPrimitive(note)
                fields["expectedNote"] = JsonPrimitive(item.note)
            } else {
                fields.remove("note")
                fields.remove("expectedNote")
            }
        }
    }
    data.decode<List<Subitem>>("subitems")?.let { desired ->
        val expected = data.decode<List<Subitem>>("expectedSubitems").orEmpty()
        fields["subitems"] = subitemPayload(mergeSubitems(expected, desired, item.subitems))
        fields["expectedSubitems"] = subitemPayload(item.subitems)
    }
}

private fun mergeSubitems(expected: List<Subitem>, desired: List<Subitem>, current: List<Subitem>): List<Subitem> {
    val before = expected.associateBy { it.id }
    val after = desired.associateBy { it.id }
    val working = current.associateBy { it.id }.toMutableMap()
    for ((id, previous) in before) {
        val wanted = after[id]
        val now = working[id] ?: continue
        if (wanted == null) working.remove(id)
        else working[id] = now.copy(
            text = wanted.text.takeIf { it != previous.text } ?: now.text,
            checked = wanted.checked.takeIf { it != previous.checked } ?: now.checked,
        )
    }
    desired.filterNot { it.id in before }.forEach { working.putIfAbsent(it.id, it) }
    val order = mergeSequence(expected.map { it.id }, desired.map { it.id }, current.map { it.id })
    return (order + working.keys).distinct().mapNotNull(working::get)
}

/** Merge non-overlapping changes. An explicit local edit wins when both edits cover the same blocks. */
internal fun <T> mergeSequence(expected: List<T>, desired: List<T>, current: List<T>): List<T> {
    if (current == expected || current == desired) return desired
    if (desired == expected) return current
    data class Change<T>(val start: Int, val end: Int, val replacement: List<T>)
    fun changes(target: List<T>): List<Change<T>> {
        val size = expected.size
        val common = Array(size + 1) { IntArray(target.size + 1) }
        for (a in expected.indices.reversed()) for (b in target.indices.reversed()) {
            common[a][b] = if (expected[a] == target[b]) 1 + common[a + 1][b + 1]
                else maxOf(common[a + 1][b], common[a][b + 1])
        }
        val result = mutableListOf<Change<T>>()
        var a = 0
        var b = 0
        var startA = 0
        var startB = 0
        while (a < size && b < target.size) {
            if (expected[a] == target[b]) {
                if (a > startA || b > startB) result += Change(startA, a, target.subList(startB, b))
                startA = ++a
                startB = ++b
            } else if (common[a + 1][b] >= common[a][b + 1]) a++ else b++
        }
        if (startA < size || startB < target.size) result += Change(startA, size, target.subList(startB, target.size))
        return result
    }
    val local = changes(desired)
    val remote = changes(current).filter { other ->
        local.none { change ->
            (change.start < other.end && other.start < change.end) ||
                change.start == other.start && change.end == other.end && change.replacement == other.replacement
        }
    }
    val final = expected.toMutableList()
    for (change in (local + remote).sortedWith(compareByDescending<Change<T>> { it.start }.thenByDescending { it.end })) {
        final.subList(change.start, change.end).clear()
        final.addAll(change.start, change.replacement)
    }
    return final
}

private fun subitemPayload(items: List<Subitem>): JsonArray = JsonArray(items.map(Subitem::toPayload))

private fun descriptionPayload(blocks: List<DescriptionBlock>): JsonArray = JsonArray(blocks.map(DescriptionBlock::toPayload))

internal fun OfflineAction.remap(ids: Map<String, String>, images: Map<String, String> = emptyMap()): OfflineAction {
    if (ids.isEmpty() && images.isEmpty()) return this
    val references = setOf("id", "listId", "itemId", "sectionId", "categoryId", "subitemId", "placeId", "recipientId", "movedItemId",
        "expectedRecipientId", "itemIds", "placeIds", "recipientIds", "expectedRecipientIds", "expectedAnywhereItemIds",
        "expectedSubitemIds", "groupIds", "expectedGroupIds", "expectedCoverId")
    fun ref(value: String): String {
        val colon = value.indexOf(':')
        return if (colon < 0) ids[value] ?: value
        else value.substring(0, colon + 1) + (ids[value.substring(colon + 1)] ?: value.substring(colon + 1))
    }
    fun rewrite(value: JsonElement, key: String = ""): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.associate { (name, element) ->
            (if (key == "contentIds") ref(name) else name) to rewrite(element, if (key == "contentIds") "id" else name)
        })
        is JsonArray -> JsonArray(value.map { rewrite(it, key) })
        is JsonPrimitive -> when {
            key in references && value.isString -> JsonPrimitive(ref(value.content))
            key == "dataUrl" && value.isString && images.isNotEmpty() -> images[workspaceHash(value.content)]?.let(::JsonPrimitive) ?: value
            else -> value
        }
    }
    return copy(
        data = rewrite(data).jsonObject,
        move = move?.let { MoveIntent(it.before?.let(::ref), it.after?.let(::ref)) },
        reorderBucket = reorderBucket?.let { it.copy(sectionId = it.sectionId?.let(::ref)) },
    )
}

internal fun JsonObject.string(key: String): String? = get(key).idValue()
private fun JsonElement?.idValue(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.strings(key: String): List<String> = (get(key) as? JsonArray)?.mapNotNull { it.idValue() }.orEmpty()
private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
private inline fun <reified T> JsonObject.decode(key: String): T? =
    get(key)?.takeUnless { it == JsonNull }?.let { actionJson.decodeFromJsonElement<T>(it) }

private fun labelKey(value: String): String =
    java.text.Normalizer.normalize(normalizeLabel(value), java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)

private fun samePlace(a: Place, b: Place): Boolean =
    labelKey(a.name) == labelKey(b.name) && labelKey(a.kind) == labelKey(b.kind) &&
        labelKey(a.region) == labelKey(b.region) && labelKey(a.countryCode) == labelKey(b.countryCode)

internal fun Workspace.applyOffline(action: OfflineAction, data: JsonObject, username: String): Workspace {
    val type = action.type
    val categoryId = data.string("categoryId")
    val listId = data.string("listId")
    val time = action.createdAt

    when (type) {
        "workspace.import" -> return importedLocally(data, username)
        "samples.remove" -> return copy(lists = lists.filterNot { it.owner && it.isSample })
        "category.create" -> return copy(categories = categories + Category(
            checkNotNull(categoryId), data.string("name").orEmpty(), data.string("color") ?: "sage",
            (categories.maxOfOrNull { it.position } ?: -1) + 1,
        ))
        "category.update" -> return copy(categories = categories.map {
            if (it.id != categoryId) it else it.copy(name = data.string("name") ?: it.name, color = data.string("color") ?: it.color)
        })
        "category.delete" -> return copy(
            categories = categories.filterNot { it.id == categoryId }.sortedBy { it.position }.mapIndexed { index, category -> category.copy(position = index) },
            lists = lists.map { if (it.categoryId == categoryId) it.copy(categoryId = null) else it },
        )
        "list.create" -> {
            val created = GatherList(
                id = checkNotNull(listId), title = data.string("title").orEmpty(), ownerUsername = username,
                createdAt = time, updatedAt = time, position = (lists.maxOfOrNull { it.position } ?: -1) + 1,
            ).editProperties(data).withLocalOrderToken()
            return copy(lists = lists + created, createdListId = created.id)
        }
        "list.delete" -> return copy(lists = lists.filterNot { it.id == listId }.sortedBy { it.position }
            .mapIndexed { index, list -> list.copy(position = index) })
    }
    val list = lists.find { it.id == listId } ?: return this
    if (type == "list.clone") {
        val cloned = list.cloneOffline(data, time, username, (lists.maxOfOrNull { it.position } ?: -1) + 1)
        return copy(lists = lists + cloned, createdListId = cloned.id)
    }

    val next = list.applyOfflineList(type, data)
    val personal = type == "cover.put" || type == "list.update" && data.keys.all { it in setOf("listId", "expectedOrderToken", "categoryId", "favorite", "archived", "position") }
    val updated = when {
        next == list || personal -> next
        else -> next.copy(updatedAt = time).withLocalOrderToken()
    }
    return copy(
        lists = lists.map { if (it.id == listId) updated else it },
        createdItemId = if (type in setOf("item.create", "item.createWithPeople")) data.string("itemId") else createdItemId,
        createdSubitemId = if (type == "subitem.create") data.string("subitemId") else createdSubitemId,
    )
}

private fun GatherList.editProperties(data: JsonObject): GatherList = copy(
    title = data.string("title") ?: title,
    description = data.string("description") ?: description,
    kind = data.string("kind") ?: kind,
    repeatCadence = data.string("repeatCadence") ?: repeatCadence,
    icon = data.string("icon") ?: icon,
    color = data.string("color") ?: color,
    categoryId = if ("categoryId" in data) data.string("categoryId") else categoryId,
    favorite = data.boolean("favorite") ?: favorite,
    archived = data.boolean("archived") ?: archived,
    isTemplate = data.boolean("isTemplate") ?: isTemplate,
    numbered = data.boolean("numbered") ?: numbered,
    position = data.number("position") ?: position,
)

private fun GatherList.cloneOffline(data: JsonObject, time: String, username: String, position: Int): GatherList {
    val createdId = checkNotNull(data.string("createdListId"))
    val mapping = (data["contentIds"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
    return remapContent(mapping + (id to createdId), resetCompletion = true).copy(
        id = createdId, title = data.string("title") ?: "$title (copy)".take(200),
        favorite = false, archived = false, isTemplate = false, isSample = false,
        createdAt = time, updatedAt = time, position = position, role = "owner", ownerUsername = username, shared = false,
    ).withLocalOrderToken()
}

internal fun GatherList.remapContent(mapping: Map<String, String>, resetCompletion: Boolean = false): GatherList {
    fun mapped(id: String) = mapping[id] ?: id
    fun mappedGroup(group: String) = group.split(':', limit = 2).let {
        if (it.size == 2) "${it[0]}:${mapped(it[1])}" else group
    }
    return copy(
        sections = sections.map { it.copy(id = mapped(it.id)) },
        places = places.map { it.copy(id = mapped(it.id)) },
        recipients = recipients.map { it.copy(id = mapped(it.id)) },
        personGroupOrder = personGroupOrder.map(::mappedGroup),
        placeGroupOrder = placeGroupOrder.map(::mappedGroup),
        items = items.map { item ->
            item.copy(
                id = mapped(item.id), checked = item.checked && !resetCompletion, sectionId = item.sectionId?.let(::mapped),
                availability = item.availability.copy(placeIds = item.availability.placeIds.map(::mapped)),
                subitems = item.subitems.map { it.copy(id = mapped(it.id), checked = it.checked && !resetCompletion) },
                description = item.description.map { block ->
                    block.copy(runs = block.runs.map { run ->
                        val link = run.link
                        val target = link?.listId?.let(mapping::get)
                        if (link != null && target != null) run.copy(link = link.copy(listId = target)) else run
                    })
                },
            ).withPeople(item.peopleIds.map(::mapped))
        },
    )
}

private fun GatherList.applyOfflineList(type: String, data: JsonObject): GatherList {
    val itemId = data.string("itemId")
    val sectionId = data.string("sectionId")
    return when (type) {
        "cover.put" -> copy(coverId = data.string("digest"))
        "list.update" -> editProperties(data)
        "list.deleteDone" -> {
            val confirmed = data.strings("itemIds").toSet()
            val remaining = items.filterNot { it.checked && it.id in confirmed }
            if (remaining.size == items.size) this else copy(items = remaining).normalizedItems()
        }
        "list.reset" -> copy(items = items.map { it.copy(checked = false, subitems = it.subitems.map { child -> child.copy(checked = false) }) })
        "section.create" -> copy(sections = sections + Section(checkNotNull(sectionId), data.string("title").orEmpty(),
            (sections.maxOfOrNull { it.position } ?: -1) + 1))
        "section.update" -> copy(sections = sections.map { if (it.id == sectionId) it.copy(title = data.string("title").orEmpty()) else it })
        "section.delete" -> {
            val removed = sortedGroup(this, sectionId).mapIndexed { index, item ->
                item.copy(sectionId = null, position = nextPosition(null) + index)
            }
            copy(
                sections = sections.filterNot { it.id == sectionId }.sortedBy { it.position }.mapIndexed { index, section -> section.copy(position = index) },
                items = items.filterNot { it.sectionId == sectionId } + removed,
            )
        }
        "section.move" -> {
            val section = sections.first { it.id == sectionId }
            val ordered = sections.sortedBy { it.position }.filterNot { it.id == sectionId }.toMutableList()
            ordered.add(data.number("index") ?: 0, section)
            copy(sections = ordered.mapIndexed { index, value -> value.copy(position = index) })
        }
        "item.create", "item.createWithPeople", "item.update", "item.updateWithPeople", "item.bulkAdd" -> applyOfflineItemEdits(type, data)
        "item.delete" -> copy(items = items.filterNot { it.id == itemId }).normalizedItems()
        "item.move" -> {
            val item = items.first { it.id == itemId }
            val group = sortedGroup(this, sectionId).filterNot { it.id == itemId }.toMutableList()
            group.add(data.number("index") ?: 0, item.copy(sectionId = sectionId))
            copy(items = items.filterNot { it.sectionId == sectionId || it.id == itemId } +
                group.mapIndexed { index, value -> value.copy(position = index) }).normalizedItems()
        }
        "item.reorderVisible" -> {
            val selected = data.strings("itemIds").map { id -> items.first { it.id == id } }
            val selectedIds = selected.mapTo(mutableSetOf()) { it.id }
            val group = sortedGroup(this, selected.first().sectionId)
            val reordered = selected.iterator()
            val updated = group.mapIndexed { index, value ->
                (if (value.id in selectedIds) reordered.next() else value).copy(position = index)
            }
            copy(items = items.filterNot { it.sectionId == selected.first().sectionId } + updated)
        }
        "item.updateDescription" -> updateItem(itemId) { it.editItemFields(data) }
        "subitem.create" -> updateItem(itemId) { item ->
            item.copy(subitems = item.subitems + Subitem(checkNotNull(data.string("subitemId")), data.string("text").orEmpty(), data.boolean("checked") ?: false))
        }
        "subitem.update" -> updateItem(itemId) { item ->
            item.copy(subitems = item.subitems.map { child ->
                if (child.id != data.string("subitemId")) child
                else child.copy(text = data.string("text") ?: child.text, checked = data.boolean("checked") ?: child.checked)
            })
        }
        "subitem.delete" -> updateItem(itemId) { item -> item.copy(subitems = item.subitems.filterNot { it.id == data.string("subitemId") }) }
        "subitem.move" -> updateItem(itemId) { item ->
            val moved = item.subitems.first { it.id == data.string("subitemId") }
            val rest = item.subitems.filterNot { it.id == moved.id }.toMutableList()
            rest.add(data.number("index") ?: 0, moved)
            item.copy(subitems = rest)
        }
        "group.reorder" -> if (data.string("kind") == "person") copy(personGroupOrder = data.strings("groupIds"))
            else copy(placeGroupOrder = data.strings("groupIds"))
        "place.create" -> copy(places = places + data.asPlace())
        "place.update" -> copy(places = places.map { if (it.id == data.string("placeId")) data.asPlace(it) else it })
        "place.delete" -> {
            val id = data.string("placeId")
            copy(
                places = places.filterNot { it.id == id },
                placeGroupOrder = placeGroupOrder.filterNot { it == "place:$id" },
                items = items.map { item ->
                    if (id !in item.availability.placeIds) item else {
                        val ids = item.availability.placeIds.filterNot { it == id }
                        item.copy(availability = if (ids.isEmpty()) Availability() else Availability("selected", ids))
                    }
                },
            )
        }
        "recipient.create" -> copy(recipients = recipients + Recipient(checkNotNull(data.string("recipientId")), normalizeLabel(data.string("name").orEmpty())))
        "recipient.update" -> copy(recipients = recipients.map {
            if (it.id == data.string("recipientId")) it.copy(name = normalizeLabel(data.string("name") ?: it.name)) else it
        })
        "recipient.delete" -> {
            val id = data.string("recipientId")
            copy(
                recipients = recipients.filterNot { it.id == id },
                personGroupOrder = personGroupOrder.filterNot { it == "person:$id" },
                items = items.map { it.withPeople(it.peopleIds.filterNot { person -> person == id }) },
            )
        }
        else -> this
    }
}

private fun GatherList.applyOfflineItemEdits(type: String, data: JsonObject): GatherList {
    val updatedPlaces = places.toMutableList()
    val updatedRecipients = recipients.toMutableList()
    val addedPlaces = data.drafts("newPlaces").map { draft ->
        val next = draft.asPlace()
        updatedPlaces.firstOrNull { existing ->
            labelKey(existing.name) == labelKey(next.name) &&
                listOf("kind" to existing.kind, "region" to existing.region, "countryCode" to existing.countryCode).all { (key, value) ->
                    key !in draft || labelKey(value) == labelKey(draft.string(key).orEmpty())
                }
        }?.id ?: next.id.also { updatedPlaces += next }
    }
    val addedPeople = (data.drafts("newRecipients") + listOfNotNull(data["newRecipient"] as? JsonObject)).map { draft ->
        val next = Recipient(checkNotNull(draft.string("recipientId")), normalizeLabel(draft.string("name").orEmpty()))
        updatedRecipients.firstOrNull { labelKey(it.name) == labelKey(next.name) }?.id
            ?: next.id.also { updatedRecipients += next }
    }
    val people = data.strings("recipientIds") + listOfNotNull(data.string("recipientId")) + addedPeople
    val availability = data.decode<Availability>("availability")?.let { it.copy(placeIds = (it.placeIds + addedPlaces).distinct().sorted()) }
    val list = copy(places = updatedPlaces, recipients = updatedRecipients)
    if (type == "item.bulkAdd") {
        val itemIds = data.strings("itemIds").toSet()
        val placeIds = data.strings("placeIds") + addedPlaces
        return list.copy(items = items.map { item ->
            if (item.id !in itemIds) item
            else item.withPeople(item.peopleIds + people).copy(
                availability = if (placeIds.isEmpty()) item.availability
                    else Availability("selected", (item.availability.placeIds + placeIds).distinct().sorted().take(25)),
            )
        })
    }
    val edit: (ListItem) -> ListItem = { item ->
        val changesSection = "sectionId" in data && data.string("sectionId") != item.sectionId
        val next = item.editItemFields(data).copy(
            availability = availability ?: item.availability,
            sectionId = if (changesSection) data.string("sectionId") else item.sectionId,
            position = if (changesSection) nextPosition(data.string("sectionId")) else item.position,
        )
        if (listOf("recipientIds", "recipientId", "newRecipients", "newRecipient").any { it in data }) next.withPeople(people) else next
    }
    if (type.startsWith("item.create")) {
        val sectionId = data.string("sectionId")
        val item = ListItem(checkNotNull(data.string("itemId")), "", sectionId = sectionId, position = nextPosition(sectionId))
        return list.copy(items = list.items + edit(item))
    }
    return list.updateItem(data.string("itemId"), edit).normalizedItems()
}

private fun JsonObject.drafts(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun ListItem.editItemFields(data: JsonObject): ListItem {
    val rich = data.decode<List<DescriptionBlock>>("description")
    return copy(
        text = data.string("text") ?: text,
        note = data.string("note") ?: note,
        quantity = data.string("quantity") ?: quantity,
        checked = data.boolean("checked") ?: checked,
        description = rich ?: description,
        subitems = data.decode<List<Subitem>>("subitems") ?: subitems,
    )
}

private fun GatherList.updateItem(itemId: String?, edit: (ListItem) -> ListItem) =
    copy(items = items.map { if (it.id == itemId) edit(it) else it })

private fun GatherList.nextPosition(sectionId: String?) =
    (items.filter { it.sectionId == sectionId }.maxOfOrNull { it.position } ?: -1) + 1

private fun GatherList.normalizedItems() =
    copy(items = items.groupBy { it.sectionId }.values.flatMap { group ->
        group.sortedBy { it.position }.mapIndexed { index, item -> if (item.position == index) item else item.copy(position = index) }
    })

private fun ListItem.withPeople(ids: List<String>): ListItem {
    val unique = ids.distinct().sorted().take(200)
    return copy(recipientId = unique.singleOrNull(), recipientIds = if (unique.size > 1) unique else emptyList())
}

private fun JsonObject.asPlace(previous: Place? = null) = Place(
    id = checkNotNull(string("placeId") ?: previous?.id),
    name = normalizeLabel(string("name") ?: previous?.name.orEmpty()),
    kind = string("kind") ?: previous?.kind ?: "other",
    region = normalizeLabel(string("region") ?: previous?.region.orEmpty()),
    countryCode = string("countryCode") ?: previous?.countryCode.orEmpty(),
)

internal fun GatherList.withLocalOrderToken() = copy(orderToken = localOrderToken())

private fun GatherList.localOrderToken(): String {
    val sectionIds = sections.sortedBy { it.position }.map { it.id }
    val groups = (listOf(null) + sectionIds).map { id -> strings(sortedGroup(this, id).map { it.id }) }
    return workspaceHash(JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(numbered), strings(sectionIds), JsonArray(groups))).toString())
}
