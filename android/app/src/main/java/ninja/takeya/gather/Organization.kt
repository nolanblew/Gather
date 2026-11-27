package ninja.takeya.gather

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class Place(
    val id: String,
    val name: String,
    val kind: String = "other",
    val region: String = "",
    val countryCode: String = "",
) {
    val label: String
        get() = listOf(name, region, countryCode).filter(String::isNotBlank).joinToString(", ")
}

@Serializable data class Recipient(val id: String, val name: String)

@Serializable
data class Availability(val mode: String = "unassigned", val placeIds: List<String> = emptyList()) {
    fun toPayload(): JsonObject = payload("mode" to mode, "placeIds" to placeIds)

    fun equivalent(other: Availability): Boolean =
        mode == other.mode && placeIds.toSet() == other.placeIds.toSet()
}

data class ListFilter(
    val place: String? = null,
    val person: String? = null,
    val notDone: Boolean = false,
) {
    val active: Boolean
        get() = this != ListFilter()

    fun matches(item: ListItem): Boolean =
        (!notDone || !item.checked) &&
            when (place) {
                null -> true
                FILTER_ANYWHERE -> item.availability.mode == "anywhere"
                FILTER_UNSET -> item.availability.mode == "unassigned"
                else -> item.availability.mode == "anywhere" || place in item.availability.placeIds
            } &&
            when (person) {
                null -> true
                FILTER_UNSET -> item.peopleIds.isEmpty()
                else -> person in item.peopleIds
            }

    fun summary(list: GatherList): String =
        listOfNotNull(
                place?.let { list.placeFilterLabel(it) },
                person?.let {
                    if (it == FILTER_UNSET) "Not set" else list.recipients.find { person -> person.id == it }?.name
                },
                "Not done".takeIf { notDone },
                "${list.items.count(::matches)} shown",
            )
            .joinToString(" · ")

    fun draftAvailability(): Availability =
        when (place) {
            FILTER_ANYWHERE -> Availability("anywhere")
            null, FILTER_UNSET -> Availability()
            else -> Availability("selected", listOf(place))
        }
}

internal const val FILTER_ANYWHERE = ":anywhere"
internal const val FILTER_UNSET = ":unset"

fun GatherList.placeFilterLabel(id: String): String? =
    when (id) {
        FILTER_ANYWHERE -> "Anywhere only"
        FILTER_UNSET -> "No places set"
        else -> places.find { it.id == id }?.label
    }

val GatherList.usesPlaces: Boolean
    get() = items.any { it.availability.mode != "unassigned" }

val GatherList.usesRecipients: Boolean
    get() = items.any { it.peopleIds.isNotEmpty() }

val GatherList.hasItemDetails: Boolean
    get() = usesPlaces || usesRecipients || items.any { it.note.isNotBlank() || it.description.isNotEmpty() || it.quantity.isNotBlank() }

fun GatherList.canonicalItems(): List<ListItem> =
    sortedGroup(this, null) + sections.sortedBy { it.position }.flatMap { sortedGroup(this, it.id) }

fun GatherList.orderedPayload(vararg values: Pair<String, Any?>): JsonObject =
    if (!numbered || orderToken == null) payload("listId" to id, *values)
    else payload("listId" to id, "expectedOrderToken" to orderToken, *values)

fun GatherList.itemMetadata(
    item: ListItem,
    filter: ListFilter,
    grouping: ItemGrouping = ItemGrouping.None,
): String {
    val people = if (grouping == ItemGrouping.Person) emptyList() else {
        val assigned = item.peopleIds.toSet() - filter.person
        recipients.filter { it.id in assigned }.map { it.name }
    }
    return listOfNotNull(
        item.quantity.takeIf { it.isNotBlank() },
        people.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "For "),
        placePreview(item.availability, filter.place).takeUnless { grouping == ItemGrouping.Place },
    ).joinToString(" · ")
}

private fun GatherList.placePreview(availability: Availability, selectedPlace: String?): String? {
    if (availability.mode == "anywhere") return "Anywhere"
    val names = availability.placeIds.filter { it != selectedPlace }.mapNotNull { id ->
        places.find { it.id == id }?.label
    }
    if (names.isEmpty()) return null
    val text = names.joinToString(" or ")
    return if (selectedPlace in availability.placeIds) "Also $text" else text
}

fun GatherList.itemWritePayload(
    item: ListItem?,
    text: String,
    description: String,
    quantity: String,
    sectionId: String?,
    availability: Availability,
    recipientId: String?,
    newPlaces: List<String>,
    newRecipient: String?,
    expectedAvailability: Availability,
    expectedRecipientId: String?,
): JsonObject = itemWritePayload(
    item, text, description, quantity, sectionId, availability,
    listOfNotNull(recipientId), newPlaces, listOfNotNull(newRecipient),
    expectedAvailability, listOfNotNull(expectedRecipientId), multiplePeople = false,
)

fun GatherList.itemWritePayload(
    item: ListItem?,
    text: String,
    description: String,
    quantity: String,
    sectionId: String?,
    availability: Availability,
    recipientIds: List<String>,
    newPlaces: List<String>,
    newRecipients: List<String>,
    expectedAvailability: Availability,
    expectedRecipientIds: List<String>,
    multiplePeople: Boolean,
): JsonObject {
    val values = mutableListOf<Pair<String, Any?>>()
    if (item != null) values += "itemId" to item.id
    if (item == null || text != item.text) values += "text" to text
    if (description != (item?.note ?: "")) values += "note" to description
    if (quantity != (item?.quantity ?: "")) values += "quantity" to quantity
    if (sectionId != item?.sectionId) values += "sectionId" to sectionId
    if (!availability.equivalent(expectedAvailability) || newPlaces.isNotEmpty()) {
        values += "availability" to availability.toPayload()
        if (item != null) values += "expectedAvailability" to expectedAvailability.toPayload()
        if (newPlaces.isNotEmpty()) values += "newPlaces" to newPlaces.map { payload("name" to it) }
    }
    if (recipientIds.toSet() != expectedRecipientIds.toSet() || newRecipients.isNotEmpty()) {
        if (multiplePeople) {
            values += "recipientIds" to recipientIds
            if (newRecipients.isNotEmpty()) values += "newRecipients" to newRecipients.map { payload("name" to it) }
            if (item != null) values += "expectedRecipientIds" to expectedRecipientIds
        } else {
            if (newRecipients.isEmpty()) values += "recipientId" to recipientIds.singleOrNull()
            else values += "newRecipient" to payload("name" to newRecipients.single())
            if (item != null) values += "expectedRecipientId" to expectedRecipientIds.singleOrNull()
        }
    }
    return if (item == null || sectionId != item.sectionId) orderedPayload(*values.toTypedArray())
    else payload("listId" to id, *values.toTypedArray())
}

private fun assignmentToken(type: String, id: String, definition: JsonObject, assigned: List<JsonArray>): String {
    val serialized = JsonArray(listOf(JsonPrimitive(type), JsonPrimitive(id), definition, JsonArray(assigned)))
    return MessageDigest.getInstance("SHA-256")
        .digest(serialized.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

fun GatherList.placeAssignmentToken(place: Place): String =
    assignmentToken(
        "place",
        place.id,
        payload(
            "id" to place.id,
            "name" to place.name,
            "kind" to place.kind,
            "region" to place.region,
            "countryCode" to place.countryCode,
        ),
        items.filter { place.id in it.availability.placeIds }.sortedBy { it.id }.map {
            JsonArray(listOf(JsonPrimitive(it.id), it.availability.copy(placeIds = it.availability.placeIds.sorted()).toPayload()))
        },
    )

fun GatherList.recipientAssignmentToken(recipient: Recipient): String =
    assignmentToken(
        "recipient",
        recipient.id,
        payload("id" to recipient.id, "name" to recipient.name),
        items.filter { recipient.id in it.peopleIds }.sortedBy { it.id }.map {
            JsonArray(listOf(JsonPrimitive(it.id), JsonPrimitive(recipient.id)))
        },
    )

fun normalizeLabel(name: String): String =
    name.replace(Regex("[\\s\\p{Z}\\uFEFF]+"), " ").trim()
