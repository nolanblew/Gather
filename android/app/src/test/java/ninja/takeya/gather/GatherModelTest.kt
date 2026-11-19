package ninja.takeya.gather

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GatherModelTest {
    @Test
    fun authoritativeWorkspaceRemovesDanglingPersonalCategoryAndAccessState() {
        val previous =
            Workspace(
                categories = listOf(Category("cat", "Personal")),
                lists = listOf(GatherList("shared", "Shared")),
            )
        val state =
            GatherState(
                workspace = previous,
                selectedId = "shared",
                categoryId = "cat",
                sharing = Sharing(true, "owner", "owner"),
                search = SearchResponse(),
            )
        val updated = state.adoptWorkspace(Workspace(lists = listOf(GatherList("kept", "Kept"))))
        assertNull(updated.categoryId)
        assertNull(updated.selectedId)
        assertNull(updated.sharing)
        assertNull(updated.search)
        assertEquals("kept", updated.workspace.lists.single().id)
    }

    @Test
    fun authoritativeWorkspaceKeepsExistingPersonalReferences() {
        val workspace =
            Workspace(
                categories = listOf(Category("cat", "Personal")),
                lists = listOf(GatherList("list", "List")),
            )
        val state =
            GatherState(
                workspace = workspace,
                selectedId = "list",
                categoryId = "cat",
                search = SearchResponse(),
            )
        val updated = state.adoptWorkspace(workspace)
        assertEquals("cat", updated.categoryId)
        assertEquals("list", updated.selectedId)
        assertNotNull(updated.search)
    }

    @Test
    fun authoritativeMutationSelectsOnlyItsExactCreatedList() {
        val state =
            GatherState(
                selectedId = "old",
                workspace = Workspace(lists = listOf(GatherList("old", "Old"))),
            )
        val result =
            Workspace(
                lists = listOf(GatherList("mine", "Mine"), GatherList("concurrent", "Other")),
                createdListId = "mine",
            )
        assertEquals("mine", state.adoptWorkspace(result, true).selectedId)
        assertNull(state.adoptWorkspace(result.copy(createdListId = "missing"), true).selectedId)
    }

    private fun list(
        id: String,
        favorite: Boolean = false,
        archived: Boolean = false,
        template: Boolean = false,
        category: String? = null,
        position: Int = 0,
    ) =
        GatherList(
            id,
            id,
            favorite = favorite,
            archived = archived,
            isTemplate = template,
            categoryId = category,
            position = position,
        )

    @Test
    fun libraryDoesNotLeakTemplatesOrArchiveIntoFavorites() {
        val workspace =
            Workspace(
                lists =
                    listOf(
                        list("plain"),
                        list("favorite", true),
                        list("archived", true, true),
                        list("template", true, template = true),
                    )
            )
        assertEquals(
            listOf("plain", "favorite"),
            visibleLists(workspace, "library", null).map { it.id },
        )
        assertEquals(listOf("favorite"), visibleLists(workspace, "favorites", null).map { it.id })
        assertEquals(listOf("template"), visibleLists(workspace, "templates", null).map { it.id })
        assertEquals(listOf("archived"), visibleLists(workspace, "archive", null).map { it.id })
        assertEquals(4, visibleLists(workspace, "all", null).size)
    }

    @Test
    fun categoryAndPositionArePersonalFilters() {
        val workspace =
            Workspace(
                lists =
                    listOf(
                        list("later", category = "a", position = 4),
                        list("other", category = "b"),
                        list("first", category = "a", position = 1),
                    )
            )
        assertEquals(listOf("first", "later"), visibleLists(workspace, "all", "a").map { it.id })
    }

    @Test
    fun sectionsSortWithinTheirOwnGroup() {
        val list =
            GatherList(
                "x",
                "x",
                items =
                    listOf(
                        ListItem("b", "b", position = 3),
                        ListItem("a", "a", position = 0),
                        ListItem("c", "c", sectionId = "s", position = 0),
                    ),
            )
        assertEquals(listOf("a", "b"), sortedGroup(list, null).map { it.id })
        assertEquals(listOf("c"), sortedGroup(list, "s").map { it.id })
    }

    @Test
    fun inviteExtractionOnlyAcceptsCanonicalHttpsHost() {
        assertEquals("a-b_1", extractInvite("https://gather.takeya.ninja/#invite=a-b_1"))
        assertNull(extractInvite("http://gather.takeya.ninja/#invite=secret"))
        assertNull(extractInvite("https://gather.takeya.ninja.evil.test/#invite=secret"))
        assertNull(extractInvite("https://evil.test/#invite=secret"))
        assertNull(extractInvite("https://gather.takeya.ninja/?invite=secret"))
        assertNull(extractInvite("https://gather.takeya.ninja/#invite="))
        assertNull(extractInvite("https://gather.takeya.ninja/#invite=" + "x".repeat(513)))
    }

    @Test
    fun payloadPreservesNullAndBooleanTypes() {
        val objectValue =
            payload("sectionId" to null, "checked" to false, "index" to 2, "text" to "2")
        assertEquals(JsonNull, objectValue["sectionId"])
        assertEquals(false, objectValue["checked"]!!.jsonPrimitive.boolean)
        assertEquals(2, objectValue["index"]!!.jsonPrimitive.int)
        assertTrue(objectValue["text"]!!.jsonPrimitive.isString)
    }

    @Test
    fun oldAccountCompletionsCannotChangeCurrentAccount() {
        val epoch = SessionEpoch()
        val beforeLogin = epoch.current
        epoch.advance()
        val accountA = epoch.current
        epoch.advance()
        val accountB = epoch.current
        assertFalse(epoch.isCurrent(beforeLogin))
        assertFalse(epoch.isCurrent(accountA))
        assertTrue(epoch.isCurrent(accountB))
    }

    @Test
    fun tokenRotationInvalidatesSearchBeforeNetworkCompletes() {
        val epoch = SessionEpoch()
        val oldTokenSearch = epoch.current
        epoch.advance() // start password change before the server revokes the old token
        val rotation = epoch.current
        assertFalse(epoch.isCurrent(oldTokenSearch))
        assertTrue(epoch.isCurrent(rotation))
        epoch.advance() // save the new token
        assertFalse(epoch.isCurrent(rotation))
    }

    @Test
    fun unknownApiMetadataDoesNotBreakNativeWorkspace() {
        val json = Json { ignoreUnknownKeys = true }
        val workspace =
            json.decodeFromString<Workspace>(
                """{"version":2,"categories":[],"lists":[{"id":"a","title":"A","futureMetadata":true}],"createdListId":"a","futureCapability":true}"""
            )
        assertEquals("a", workspace.createdListId)
        assertEquals("checklist", workspace.lists.single().kind)
        assertFalse(workspace.lists.single().numbered)
        assertEquals(emptyList<Place>(), workspace.lists.single().places)
    }

    @Test
    fun numberedViewKeepsCanonicalOrderAndFiltersAlternativePlaces() {
        val list = GatherList(
            "trip", "Trip", numbered = true,
            places = listOf(Place("kyoto", "Kyoto"), Place("tokyo", "Tokyo")),
            recipients = listOf(Recipient("maya", "Maya")),
            sections = listOf(Section("last", "Last", 2), Section("empty", "Empty", 1)),
            items = listOf(
                ListItem("tea", "Tea", availability = Availability("selected", listOf("kyoto", "tokyo")), recipientId = "maya"),
                ListItem("socks", "Socks", checked = true, position = 1, availability = Availability("anywhere"), recipientId = "maya"),
                ListItem("paper", "Paper", position = 2),
                ListItem("gift", "Gift", sectionId = "last", availability = Availability("anywhere"), recipientId = "maya"),
            ),
        )
        val canonical = list.canonicalItems()
        val filter = ListFilter(place = "kyoto", person = "maya", notDone = true)
        assertEquals(listOf("tea", "socks", "paper", "gift"), canonical.map { it.id })
        assertEquals(listOf(1, 4), canonical.mapIndexedNotNull { index, item -> (index + 1).takeIf { filter.matches(item) } })
        assertEquals("Also Tokyo", list.itemMetadata(canonical.first(), filter))
        assertEquals("Kyoto · Maya · Not done · 2 shown", filter.summary(list))
        assertEquals(listOf("paper"), canonical.filter(ListFilter(place = FILTER_UNSET)::matches).map { it.id })
    }

    @Test
    fun itemDraftSerializesNestedAtomicFieldsAndOnlySendsTouchedMetadata() {
        val original = ListItem("item", "Tea", note = "Keep\n\nThis", availability = Availability("anywhere"), recipientId = "old")
        val list = GatherList("list", "List", numbered = true, orderToken = "token", items = listOf(original))
        val scalar = list.itemWritePayload(
            original, "New tea", original.note, "", null, original.availability, "old", emptyList(), null,
            original.availability, "old",
        )
        assertEquals(setOf("listId", "itemId", "text"), scalar.keys)
        val structural = list.itemWritePayload(
            original, original.text, original.note, "2", "section", Availability("selected", listOf("pl")),
            null, listOf("Kyoto", "Tokyo"), "Maya", original.availability, "old",
        )
        assertEquals(JsonPrimitive("token"), structural["expectedOrderToken"])
        assertEquals(JsonPrimitive("old"), structural["expectedRecipientId"])
        assertEquals(JsonPrimitive("Maya"), structural["newRecipient"]?.jsonObject?.get("name"))
        assertEquals(JsonArray(listOf(JsonPrimitive("pl"))), structural["availability"]?.jsonObject?.get("placeIds"))
        assertEquals(listOf("Kyoto", "Tokyo"), structural["newPlaces"]?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive("anywhere"), structural["expectedAvailability"]?.jsonObject?.get("mode"))
        assertFalse(structural.containsKey("note"))
    }

    @Test
    fun nativeReadsSparseVersionThreeOrganizationState() {
        val json = Json { ignoreUnknownKeys = true }
        val workspace = json.decodeFromString<Workspace>(
            """{"version":3,"lists":[{"id":"trip","title":"Trip","numbered":true,"orderToken":"guard","places":[{"id":"p","name":"Kyoto"}],"recipients":[{"id":"r","name":"Maya"}],"items":[{"id":"i","text":"Tea","note":"Paragraph 1\n\nParagraph 2","availability":{"mode":"selected","placeIds":["p"]},"recipientId":"r"},{"id":"j","text":"Paper"}]}],"createdItemId":"i"}"""
        )
        val list = workspace.lists.single()
        assertEquals("i", workspace.createdItemId)
        assertEquals("other", list.places.single().kind)
        assertEquals("guard", list.orderToken)
        assertEquals("Paragraph 1\n\nParagraph 2", list.items.first().note)
        assertEquals(Availability(), list.items.last().availability)
        assertNull(list.items.last().recipientId)
    }

    @Test
    fun nativeReadsMultiplePeopleAndFiltersAnyMembership() {
        val json = Json { ignoreUnknownKeys = true }
        val workspace = json.decodeFromString<Workspace>(
            """{"version":4,"lists":[{"id":"trip","title":"Trip","items":[{"id":"shared","text":"Gift","recipientIds":["alex","maya"]},{"id":"legacy","text":"Tea","recipientId":"maya"},{"id":"unset","text":"Paper"}]}]}"""
        )
        val items = workspace.lists.single().items
        assertEquals(listOf("alex", "maya"), items[0].peopleIds)
        assertEquals(listOf("maya"), items[1].peopleIds)
        assertEquals(listOf("shared"), items.filter(ListFilter(person = "alex")::matches).map { it.id })
        assertEquals(listOf("shared", "legacy"), items.filter(ListFilter(person = "maya")::matches).map { it.id })
        assertEquals(listOf("unset"), items.filter(ListFilter(person = FILTER_UNSET)::matches).map { it.id })
    }

    @Test
    fun groupedRowsKeepOneUnderlyingIdentityAndDateAddedIgnoresManualMoves() {
        val shared = ListItem("shared", "Gift", position = 2, recipientIds = listOf("maya", "alex"))
        val other = ListItem("other", "Tea", position = 0)
        val list = GatherList(
            "list", "List", recipients = listOf(Recipient("maya", "Maya"), Recipient("alex", "Alex")),
            items = listOf(shared, other),
        )
        assertEquals(listOf("shared", "other"), list.viewItems(ItemSort.DateAdded, ListFilter()).map { it.id })
        assertEquals(listOf("other", "shared"), list.viewItems(ItemSort.Manual, ListFilter()).map { it.id })
        val groups = list.viewGroups(list.items, ItemGrouping.Person)
        assertEquals(listOf("Alex", "Maya", "No recipient"), groups.map { it.title })
        assertEquals("shared", groups[0].rows.single().item.id)
        assertEquals("shared", groups[1].rows.single().item.id)
        assertEquals(listOf("Maya"), groups[0].rows.single().alsoUnder)
        assertEquals(listOf("other", "shared"), movedItemIds(listOf("shared", "other"), "shared", "other"))
    }
}
