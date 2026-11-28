package ninja.takeya.gather

enum class ItemSort(val label: String) {
    DateAdded("Date added (oldest first)"),
    DateAddedNewest("Date added (newest first)"),
    Name("Name (A–Z)"),
    Manual("Manual"),
}

enum class ItemGrouping(val label: String, val wireKind: String? = null) {
    None("None"),
    Place("Place", "place"),
    Person("Person", "person"),
}

data class ItemViewRow(
    val item: ListItem,
    private val groupNames: List<String> = emptyList(),
    private val groupIndex: Int = -1,
) {
    val isPrimaryAppearance get() = groupIndex <= 0

    val alsoUnder: List<String>
        get() = groupNames.filterIndexed { index, _ -> index != groupIndex }

    val aliasSummary: String?
        get() {
            if (groupNames.size < 2) return null
            val first = groupNames[if (groupIndex == 0) 1 else 0]
            val extra = groupNames.size - 2
            return if (extra == 0) "Also under $first" else "Also under $first +$extra"
        }
}

data class ItemViewGroup(val id: String, val title: String?, val rows: List<ItemViewRow>) {
    val itemIds: List<String> = rows.map { it.item.id }
}

private const val ANYWHERE_GROUP = "anywhere"
private const val UNASSIGNED_GROUP = "unassigned"

internal data class ItemGroupLabel(val id: String, val title: String)

internal fun GatherList.storedGroupOrder(grouping: ItemGrouping): List<String> = when (grouping) {
    ItemGrouping.None -> emptyList()
    ItemGrouping.Place -> placeGroupOrder
    ItemGrouping.Person -> personGroupOrder
}

private fun GatherList.groupLabels(grouping: ItemGrouping): Map<String, String> = when (grouping) {
    ItemGrouping.None -> emptyMap()
    ItemGrouping.Person -> recipients.associate { "person:${it.id}" to it.name } +
        (UNASSIGNED_GROUP to "No recipient")
    ItemGrouping.Place -> places.associate { "place:${it.id}" to it.label } +
        mapOf(ANYWHERE_GROUP to "Anywhere", UNASSIGNED_GROUP to "No places set")
}

private fun ListItem.groupKeys(grouping: ItemGrouping, labels: Map<String, String>): List<String> {
    val assigned = when (grouping) {
        ItemGrouping.None -> return emptyList()
        ItemGrouping.Person -> peopleIds.map { "person:$it" }
        ItemGrouping.Place -> availability.placeIds.map { "place:$it" }
    }
    return assigned.filter { it in labels }.distinct().ifEmpty {
        listOf(if (grouping == ItemGrouping.Place && availability.mode == "anywhere") ANYWHERE_GROUP else UNASSIGNED_GROUP)
    }
}

private fun alphabeticalGroupOrder(labels: Map<String, String>): Comparator<String> =
    compareBy<String> { it == UNASSIGNED_GROUP }
        .thenBy { it == ANYWHERE_GROUP }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { labels.getValue(it) }
        .thenBy { it }

private fun GatherList.groupOrderComparator(grouping: ItemGrouping, labels: Map<String, String>): Comparator<String> {
    val ranks = storedGroupOrder(grouping).withIndex().associate { (index, id) -> id to index }
    return compareBy<String> { ranks[it] ?: Int.MAX_VALUE }.then(alphabeticalGroupOrder(labels))
}

internal fun GatherList.realizedGroupLabels(grouping: ItemGrouping): List<ItemGroupLabel> {
    val labels = groupLabels(grouping)
    return items.flatMapTo(linkedSetOf()) { it.groupKeys(grouping, labels) }
        .sortedWith(groupOrderComparator(grouping, labels))
        .map { ItemGroupLabel(it, labels.getValue(it)) }
}

fun GatherList.defaultItemSort(): ItemSort = if (numbered) ItemSort.Manual else ItemSort.DateAdded

fun GatherList.viewItems(sort: ItemSort, filter: ListFilter, query: String = ""): List<ListItem> {
    val filtered = items.filter { filter.matches(it) && matchesItemQuery(it, query) }
    return when (sort) {
        ItemSort.DateAdded -> filtered
        ItemSort.DateAddedNewest -> filtered.asReversed()
        ItemSort.Manual -> filtered.sortedBy { it.position }
        ItemSort.Name -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.text })
    }
}

fun GatherList.matchesItemQuery(item: ListItem, query: String): Boolean {
    val search = query.trim()
    return search.isEmpty() || (listOf(item.text, item.descriptionText, item.quantity, itemMetadata(item, ListFilter())) + item.subitems.map { it.text })
        .any { it.contains(search, ignoreCase = true) }
}

val GatherList.separatesCompletedItems: Boolean
    get() = kind == "checklist" && !numbered && !isTemplate

/** Builds groups for items in a single section and completion bucket. */
fun GatherList.viewGroups(items: List<ListItem>, grouping: ItemGrouping): List<ItemViewGroup> {
    if (items.isEmpty()) return emptyList()
    if (grouping == ItemGrouping.None) {
        return listOf(ItemViewGroup("", null, items.map { ItemViewRow(it) }))
    }
    val labels = groupLabels(grouping)
    val alphabetical = alphabeticalGroupOrder(labels)
    val groups = linkedMapOf<String, MutableList<ItemViewRow>>()
    items.forEach { item ->
        val available = item.groupKeys(grouping, labels).sortedWith(alphabetical)
        val names = available.map(labels::getValue)
        available.forEachIndexed { index, id ->
            groups.getOrPut(id) { mutableListOf() } += ItemViewRow(item, names, index)
        }
    }
    return groups.keys.sortedWith(groupOrderComparator(grouping, labels))
        .map { id -> ItemViewGroup(id, labels.getValue(id), groups.getValue(id)) }
}

internal sealed interface DetailEntry {
    val key: String

    data object Completed : DetailEntry { override val key = "completed" }

    data class SectionHeading(val section: Section?, val done: Boolean) : DetailEntry {
        override val key = detailEntryKey("section-heading", section?.id.orEmpty(), done.toString())
    }

    data class EmptySection(val sectionId: String) : DetailEntry {
        override val key = detailEntryKey("empty-section", sectionId)
    }

    data class GroupHeading(val title: String, val bucketKey: String, val groupId: String) : DetailEntry {
        override val key = detailEntryKey("group-heading", bucketKey)
    }

    data class Row(val row: ItemViewRow, val bucket: ItemViewGroup, val bucketKey: String) : DetailEntry {
        override val key = siblingKey(row)
        fun siblingKey(sibling: ItemViewRow): String = if (sibling.isPrimaryAppearance)
            detailEntryKey("primary-item", sibling.item.id) else detailEntryKey("item", bucketKey, sibling.item.id)
    }
}

private fun detailEntryKey(vararg parts: String): String =
    parts.joinToString(separator = "") { "${it.length}:$it" }

internal fun GatherList.detailEntries(
    visibleItems: List<ListItem>,
    grouping: ItemGrouping,
    filtered: Boolean,
    splitCompleted: Boolean,
): List<DetailEntry> {
    val orderedSections = sections.sortedBy { it.position }
    val (completed, open) = visibleItems.partition { splitCompleted && it.checked }
    val openBySection = open.groupBy { it.sectionId }
    val completedBySection = completed.groupBy { it.sectionId }
    return buildList {
        fun addGroups(items: List<ListItem>, sectionId: String?, done: Boolean) {
            viewGroups(items, grouping).forEach { group ->
                val bucketKey = detailEntryKey(sectionId.orEmpty(), done.toString(), group.id)
                group.title?.let { add(DetailEntry.GroupHeading(it, bucketKey, group.id)) }
                group.rows.forEach { row -> add(DetailEntry.Row(row, group, bucketKey)) }
            }
        }

        addGroups(openBySection[null].orEmpty(), null, false)
        orderedSections.forEach { section ->
            val openItems = openBySection[section.id].orEmpty()
            val doneItems = completedBySection[section.id].orEmpty()
            if (openItems.isNotEmpty() || !filtered) {
                add(DetailEntry.SectionHeading(section, false))
                addGroups(openItems, section.id, false)
                if (openItems.isEmpty() && doneItems.isEmpty()) add(DetailEntry.EmptySection(section.id))
            }
        }
        if (completed.isNotEmpty()) {
            add(DetailEntry.Completed)
            for (section in listOf(null) + orderedSections) {
                val items = completedBySection[section?.id].orEmpty()
                if (items.isEmpty()) continue
                if (orderedSections.isNotEmpty()) add(DetailEntry.SectionHeading(section, true))
                addGroups(items, section?.id, true)
            }
        }
    }
}

fun movedItemIds(itemIds: List<String>, itemId: String, destinationId: String): List<String>? {
    val from = itemIds.indexOf(itemId)
    val to = itemIds.indexOf(destinationId)
    if (from < 0 || to < 0 || from == to) return null
    return itemIds.toMutableList().apply { add(to, removeAt(from)) }
}
