package ninja.takeya.gather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class OfflineActionsTest {
    private fun base(vararg lists: GatherList) = Workspace(lists = lists.toList())
    private fun OfflineAction.write(workspace: Workspace) = (rebase(workspace) as ActionPlan.Write).data

    @Test
    fun serializedOutboxRestoresDependentCreatesAndExactSubmittedPayload() {
        val empty = OfflineWorkspace(base(), capabilities = setOf("offline-actions"))
        val category = makeOfflineAction("category.create", payload("name" to "Trips"), empty.projected("Avery"))
        val withCategory = empty.copy(outbox = listOf(category))
        val categoryId = withCategory.projected("Avery").categories.single().id
        val created = makeOfflineAction("list.create", payload("title" to "Packing", "categoryId" to categoryId), withCategory.projected("Avery"))
        val withList = empty.copy(outbox = listOf(category, created))
        val listId = withList.projected("Avery").lists.single().id
        val item = makeOfflineAction("item.create", payload("listId" to listId, "text" to "Tent"), withList.projected("Avery"))
        val withItem = withList.copy(outbox = withList.outbox + item)
        val itemId = withItem.projected("Avery").lists.single().items.single().id
        val child = makeOfflineAction("subitem.create", payload("listId" to listId, "itemId" to itemId, "text" to "Pegs"), withItem.projected("Avery"))
        val pending = withItem.copy(outbox = listOf(category.copy(submitted = category.data), created, item, child))
        val restored = Json.decodeFromString<OfflineWorkspace>(Json.encodeToString(pending))
        assertEquals(category.data, restored.outbox.first().submitted)
        assertEquals(pending, restored)
        val list = restored.projected("Avery").lists.single()
        assertEquals(categoryId, list.categoryId)
        assertEquals("Pegs", list.items.single().subitems.single().text)
        assertEquals(child.data.string("subitemId"), list.items.single().subitems.single().id)
    }

    @Test
    fun acknowledgedCreateDoesNotAppearTwiceBeforeReceiptIsRetried() {
        val existing = base(GatherList("list", "Household"))
        val action = makeOfflineAction("item.create", payload("listId" to "list", "text" to "Batteries"), existing)
        val accepted = existing.applyOffline(action, action.write(existing), "Avery").withoutReceipt()
        val record = OfflineWorkspace(accepted, listOf(action.copy(submitted = action.data)))
        assertEquals(1, record.projected("Avery").lists.single().items.size)
        assertEquals(action.data, record.outbox.single().submitted)
    }

    @Test
    fun itemMoveUsesSurvivingAnchorsAndLatestOrderToken() {
        fun items(vararg ids: String) = ids.mapIndexed { i, id -> ListItem(id, id, position = i) }
        val original = GatherList("list", "Trip", numbered = true, orderToken = "one", items = items("a", "b", "c"))
        val action = makeOfflineAction("item.move", payload("listId" to "list", "itemId" to "c", "sectionId" to null, "index" to 1), base(original))
        val current = original.copy(items = items("x", "a", "b", "c"), orderToken = "two")
        val rebased = action.write(base(current))
        assertEquals("two", rebased.string("expectedOrderToken"))
        val projected = base(current).applyOffline(action, rebased, "Avery").lists.single()
        assertEquals(listOf("x", "a", "c", "b"), sortedGroup(projected, null).map { it.id })
    }

    @Test
    fun peopleAndSubitemMergesPreserveIndependentRemoteChanges() {
        val people = listOf(Recipient("a", "A"), Recipient("b", "B"), Recipient("c", "C"))
        val child = Subitem("task", "Old")
        val before = ListItem("item", "Packing", recipientId = "a", subitems = listOf(child))
        val original = GatherList("list", "Trip", recipients = people, items = listOf(before))
        val queued = makeOfflineAction("item.update", payload(
            "listId" to "list", "itemId" to "item", "recipientId" to "b", "expectedRecipientId" to "a",
            "expectedSubitems" to listOf(child.toPayload()),
            "subitems" to listOf(child.copy(text = "Local").toPayload()),
        ), base(original))
        val latest = original.copy(items = listOf(before.copy(
            recipientId = "c",
            subitems = listOf(child.copy(checked = true), Subitem("remote", "Remote")),
        )))
        val projected = base(latest).applyOffline(queued, queued.write(base(latest)), "Avery").lists.single().items.single()
        assertEquals(listOf("b", "c"), projected.peopleIds)
        assertEquals(listOf(Subitem("task", "Local", true), Subitem("remote", "Remote")), projected.subitems)
    }

    @Test
    fun descriptionMergesSurvivingParagraphsAndGuardsTheLatestServerValue() {
        fun block(text: String) = DescriptionBlock(runs = listOf(DescriptionRun(text)))
        val before = ListItem("item", "Packing", description = listOf(block("first"), block("second")))
        val original = GatherList("list", "Trip", items = listOf(before))
        val queued = makeOfflineAction("item.updateDescription", payload(
            "listId" to "list", "itemId" to "item",
            "expectedDescription" to before.description.map(DescriptionBlock::toPayload),
            "description" to listOf(block("local"), block("second")).map(DescriptionBlock::toPayload),
        ), base(original))
        val latest = original.copy(items = listOf(before.copy(description = listOf(block("first"), block("remote")))))
        val rebased = queued.write(base(latest))
        assertEquals(JsonArray(latest.items.single().description.map(DescriptionBlock::toPayload)), rebased["expectedDescription"])
        val projected = base(latest).applyOffline(queued, rebased, "Avery").lists.single().items.single()
        assertEquals(listOf(block("local"), block("remote")), projected.description)
    }

    @Test
    fun offlineRichEditRemovesLegacyNoteWithCurrentGuard() {
        val before = ListItem("item", "Packing", note = "Old note")
        val list = GatherList("list", "Trip", items = listOf(before))
        val bold = DescriptionBlock(runs = listOf(DescriptionRun("Old note", bold = true)))
        val action = makeOfflineAction("item.update", payload(
            "listId" to "list", "itemId" to "item", "note" to "", "expectedNote" to "Old note",
            "description" to listOf(bold.toPayload()), "expectedDescription" to emptyList<DescriptionBlock>(),
        ), base(list))
        val latest = list.copy(items = listOf(before.copy(note = "Other device")))
        val rebased = action.write(base(latest))
        assertEquals("Other device", rebased.string("expectedNote"))
        assertEquals("", rebased.string("note"))
        val projected = base(latest).applyOffline(action, rebased, "Avery").lists.single().items.single()
        assertEquals(listOf(bold), projected.description)
        assertEquals("", projected.note)
    }

    @Test
    fun coverAndInlineDraftAliasesOnlyRemapReferences() {
        val list = GatherList("list", "Trip", coverId = "old")
        val data = payload("listId" to "list", "expectedCoverId" to "old", "digest" to "raw", "data" to "bytes")
        val action = makeOfflineAction("cover.put", data, base(list))
        val next = action.remap(mapOf("old" to "previous-upload", "raw" to "ignored"))
        val afterPreviousUpload = list.copy(coverId = "previous-upload")
        assertEquals("raw", next.write(base(afterPreviousUpload)).string("digest"))
        assertTrue(next.rebase(base(list.copy(coverId = "different-remote-cover"))) is ActionPlan.Done)
        val image = DescriptionBlock(type = "image", dataUrl = "client-image", display = "inline")
        val update = makeOfflineAction("item.updateDescription", payload(
            "listId" to "list", "itemId" to "same", "description" to listOf(image.toPayload()),
            "expectedDescription" to listOf(image.toPayload()),
            "text" to "same",
        ), base(list))
        val rewritten = update.remap(mapOf("same" to "stable"), mapOf(workspaceHash("client-image") to "canonical-image")).data
        assertEquals("stable", rewritten.string("itemId"))
        assertEquals("same", rewritten.string("text"))
        for (key in listOf("description", "expectedDescription")) {
            assertEquals("canonical-image", (rewritten[key] as JsonArray).single().jsonObject.string("dataUrl"))
        }
    }

    @Test
    fun deletionOfRemoteTargetDropsQueuedEditAndKeepsIndependentActions() {
        val shared = GatherList("shared", "Trip", role = "editor", items = listOf(ListItem("item", "Old")))
        val personal = GatherList("personal", "Home")
        val editing = makeOfflineAction("item.update", payload("listId" to "shared", "itemId" to "item", "text" to "Local"), base(shared, personal))
        val favorite = makeOfflineAction("list.update", payload("listId" to "personal", "favorite" to true), base(shared, personal))
        assertNotNull((editing.rebase(base(personal)) as ActionPlan.Done).notice)
        val restored = OfflineWorkspace(base(personal), listOf(editing, favorite)).projected("Avery")
        assertEquals(listOf("personal"), restored.lists.map { it.id })
        assertTrue(restored.lists.single().favorite)
    }

    @Test
    fun visibleReorderKeepsConcurrentSiblingOrder() {
        fun items(vararg ids: String) = ids.mapIndexed { index, id -> ListItem(id, id, position = index) }
        val original = GatherList("list", "Trip", items = items("a", "b", "c", "d"))
        val queued = makeOfflineAction("item.reorderVisible", payload(
            "listId" to "list", "movedItemId" to "d", "itemIds" to listOf("a", "d", "b", "c"),
        ), base(original))
        val current = original.copy(items = items("c", "a", "b", "d", "remote"))
        val rebased = queued.write(base(current))
        assertFalse("movedItemId" in rebased)
        val result = base(current).applyOffline(queued, rebased, "Avery").lists.single()
        assertEquals(listOf("c", "a", "d", "b", "remote"), sortedGroup(result, null).map { it.id })
    }

    @Test
    fun localBackupAndImportPreserveOwnedContentAndRemapReferences() {
        val link = DescriptionLink("list", listId = "second")
        val image = DescriptionBlock(type = "image", dataUrl = "data:image/webp;base64,fixture", display = "inline")
        val child = Subitem("child", "Pegs", checked = true)
        val item = ListItem("item", "Tent", description = listOf(
            DescriptionBlock(runs = listOf(DescriptionRun("Other packing list", link = link))), image,
        ), subitems = listOf(child))
        val first = GatherList("first", "Camp", categoryId = "category", items = listOf(item), coverId = "cover", isSample = true)
        val second = GatherList("second", "Tools")
        val joined = GatherList("shared", "Shared", role = "editor")
        val source = Workspace(version = 5, categories = listOf(Category("category", "Weekend")), lists = listOf(first, second, joined))
        val backup = Json.parseToJsonElement(source.localBackup()).jsonObject
        val parsed = parseLocalBackup(backup)
        assertEquals(listOf("first", "second"), parsed.lists.map { it.id })
        val target = Workspace(version = 5, lists = listOf(GatherList("existing", "Old")))
        val action = makeOfflineAction("workspace.import", importPayload(backup, parsed), target)
        val projected = target.applyOffline(action, action.write(target), "Avery")
        val imported = projected.lists.single { it.title == "Camp" }
        val importedTarget = projected.lists.single { it.title == "Tools" }
        assertNotEquals(first.id, imported.id)
        assertEquals(projected.categories.single().id, imported.categoryId)
        assertEquals(importedTarget.id, imported.items.single().description.first().runs.single().link?.listId)
        assertEquals(image, imported.items.single().description.last())
        assertNotEquals(child.id, imported.items.single().subitems.single().id)
        assertTrue(imported.items.single().subitems.single().checked)
        assertTrue(imported.owner)
        assertFalse(imported.isSample)
        assertNull(imported.coverId)
        assertEquals(projected, projected.applyOffline(action, action.write(projected), "Avery"))
    }
}
