package ninja.takeya.gather

enum class ItemSort(val label: String) {
    DateAdded("Date added (oldest first)"),
    DateAddedNewest("Date added (newest first)"),
    Name("Name (A–Z)"),
    Manual("Manual"),
}

enum class ItemGrouping(val label: String) {
    None("None"),
    Place("Place"),
    Person("Person"),
}

data class ItemViewRow(
    val item: ListItem,
    private val groupNames: List<String> = emptyList(),
    private val groupIndex: Int = -1,
) {
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
private const val UNSET_GROUP = "unset"

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
    return search.isEmpty() || listOf(item.text, item.note, item.quantity, itemMetadata(item, ListFilter()))
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
    val labels = if (grouping == ItemGrouping.Person) recipients.associate { "id:${it.id}" to it.name }
        else places.associate { "id:${it.id}" to it.label }
    val groups = linkedMapOf<String, MutableList<ItemViewRow>>()
    fun title(id: String): String = when (id) {
        UNSET_GROUP -> if (grouping == ItemGrouping.Person) "No recipient" else "No places set"
        ANYWHERE_GROUP -> "Anywhere"
        else -> labels.getValue(id)
    }
    items.forEach { item ->
        val assigned = when (grouping) {
            ItemGrouping.Person -> item.peopleIds
            ItemGrouping.Place -> item.availability.placeIds
            ItemGrouping.None -> emptyList()
        }
        val available = assigned.map { "id:$it" }.filter { it in labels }.distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ::title)).ifEmpty {
                listOf(if (grouping == ItemGrouping.Place && item.availability.mode == "anywhere") ANYWHERE_GROUP else UNSET_GROUP)
            }
        val names = available.map(::title)
        available.forEachIndexed { index, id ->
            groups.getOrPut(id) { mutableListOf() } += ItemViewRow(item, names, index)
        }
    }
    return groups.map { (id, rows) -> ItemViewGroup(id, title(id), rows) }.sortedWith(
        compareBy<ItemViewGroup> { it.id == UNSET_GROUP }
            .thenBy { it.id == ANYWHERE_GROUP }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title.orEmpty() }
            .thenBy { it.id },
    )
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

    data class GroupHeading(val title: String, val bucketKey: String) : DetailEntry {
        override val key = detailEntryKey("group-heading", bucketKey)
    }

    data class Row(val row: ItemViewRow, val bucket: ItemViewGroup, val bucketKey: String) : DetailEntry {
        override val key = detailEntryKey("item", bucketKey, row.item.id)
        fun siblingKey(itemId: String): String = detailEntryKey("item", bucketKey, itemId)
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
                group.title?.let { add(DetailEntry.GroupHeading(it, bucketKey)) }
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
