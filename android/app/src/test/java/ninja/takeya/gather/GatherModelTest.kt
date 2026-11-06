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
    }
}
