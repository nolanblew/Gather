package ninja.takeya.gather

import android.graphics.Bitmap
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature as createFoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.closeSoftKeyboard
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import ninja.takeya.gather.LocalAcceptanceFixture.Companion.json
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class GatherAcceptanceTest {
    // Compose 1.7.2's test rule replaces WindowRecomposer, bypassing Android's
    // normal settings observer. Supply the real setting explicitly to the test
    // recomposer; verify production's observer separately outside this harness.
    private val systemMotionScale = object : MotionDurationScale {
        override val scaleFactor: Float
            get() = Settings.Global.getFloat(
                InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
    }
    @get:Rule(order = 0) val windowLayout = WindowLayoutInfoPublisherRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>(effectContext = systemMotionScale)
    @get:Rule(order = 2) val evidence = object : TestWatcher() {
        override fun failed(error: Throwable?, description: Description?) {
            runCatching {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "acceptance").apply { mkdirs() }
                runCatching {
                    val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                    val trees = roots.fetchSemanticsNodes().indices.map { index ->
                        roots[index].printToLog("GatherAcceptance")
                        roots[index].printToString(maxDepth = 30)
                    }
                    File(directory, "${description?.methodName ?: "failure"}-semantics.txt").writeText(trees.joinToString("\n\n"))
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(directory, "${description?.methodName ?: "failure"}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun realAppListsSharingSearchTemplatesArchiveAndAppearance() {
        val fixture = LocalAcceptanceFixture()
        signIn(fixture)

        val title = "Device groceries ${fixture.runId}"
        val original = "Apples ${fixture.runId}"
        val edited = "Green apples ${fixture.runId}"
        compose.onNodeWithContentDescription("New list").assertExists().assertHasClickAction()
        click("New list")
        editorText("editor-title", title)
        saveEditor()
        awaitStored("created list") { fixture.list(title) != null }
        waitDescription("Back to library")
        assertTrue(fixture.list(title)!!.getString("id").isNotBlank())
        val listId = fixture.list(title)!!.getString("id")

        scrollText("Add item")
        click("Add item")
        editorText("editor-item-text", original)
        saveEditor()
        awaitStored("created item") { fixture.item(title, original) != null }
        scrollDescription("Check $original")
        clickDescription("Check $original")
        awaitStored("checked item") { fixture.item(title, original)?.getBoolean("checked") == true }
        waitDescription("Uncheck $original")
        compose.onNodeWithContentDescription("Uncheck $original").assertIsOn()

        // Editing the actual row must preserve its existing checkmark.
        compose.onNodeWithText(original).performClick()
        editorText("editor-item-text", edited)
        saveEditor()
        awaitStored("edited item and preserved completion") {
            fixture.item(title, edited)?.getBoolean("checked") == true && fixture.item(title, original) == null
        }

        // The ordinary browser cookie transport reads and updates the same list.
        val browserCookie = fixture.browserLogin()
        val webItem = "From browser ${fixture.runId}"
        fixture.action("item.create", json("listId" to listId, "text" to webItem), cookie = browserCookie)
        clickDescription("Refresh lists")
        scrollText(webItem)
        compose.onNodeWithText(webItem).assertIsDisplayed()

        // A remote deletion invalidates the open editor instead of leaving a
        // stale draft that could be submitted against an item that no longer exists.
        compose.onNodeWithText(webItem).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor-item-text").fetchSemanticsNodes().isNotEmpty() }
        val webItemId = fixture.item(title, webItem)!!.getString("id")
        fixture.action("item.delete", json("listId" to listId, "itemId" to webItemId), cookie = browserCookie)
        val editedItemModel = viewModel()
        compose.runOnIdle { editedItemModel.refresh() }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("editor-item-text").fetchSemanticsNodes().isEmpty() }
        assertEquals(listId, editedItemModel.state.value.selectedId)
        scrollText(edited)
        compose.onNodeWithText(edited).assertIsDisplayed()

        // A separate native account joins, edits and then loses authorization.
        val memberToken = fixture.secondAccount()
        clickDescription("Share list")
        waitText("Create invitation")
        compose.onNodeWithTag("editor-save").performScrollTo().performClick()
        waitText("Copy link")
        compose.onNodeWithText("Copy link").performScrollTo().performClick()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var inviteLink = ""
        compose.runOnIdle {
            inviteLink = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            clipboard.clearPrimaryClip()
        }
        assertTrue("The UI must produce a canonical invitation link", inviteLink.startsWith("https://gather.takeya.ninja/#invite="))
        compose.onNodeWithText("Done").performScrollTo().performClick()
        fixture.request("/api/invitations/join", json("token" to inviteLink.substringAfter("#invite=")), memberToken)
        val sharedItem = "From collaborator ${fixture.runId}"
        fixture.action("item.create", json("listId" to listId, "text" to sharedItem), memberToken)
        clickDescription("Refresh lists")
        scrollText(sharedItem)
        compose.onNodeWithText(sharedItem).assertIsDisplayed()
        val memberId = fixture.request("/api/native/auth/session", bearer = memberToken).getString("userId")
        fixture.request("/api/sharing/remove", json("listId" to listId, "userId" to memberId), fixture.token)
        assertEquals(404, fixture.raw("/api/actions", json("type" to "item.create", "payload" to json("listId" to listId, "text" to "Must be denied")), memberToken).status)

        clickDescription("Back to library")
        // Another client can delete the selected category, including the last
        // category whose removal also takes away every category chip.
        val categoryName = "Remote category ${fixture.runId}"
        val categorized = fixture.action("category.create", json("name" to categoryName), cookie = browserCookie)
        val categories = categorized.getJSONArray("categories")
        val categoryId = (0 until categories.length()).map { categories.getJSONObject(it) }
            .single { it.getString("name") == categoryName }.getString("id")
        // This fixture is restricted to disposable local synthetic accounts.
        // Remove the other categories to exercise the disappearing-chip case.
        (0 until categories.length()).map { categories.getJSONObject(it).getString("id") }
            .filter { it != categoryId }.forEach {
                fixture.action("category.delete", json("categoryId" to it), cookie = browserCookie)
            }
        fixture.action("list.update", json("listId" to listId, "categoryId" to categoryId), cookie = browserCookie)
        val categoryModel = viewModel()
        clickDescription("Refresh lists")
        compose.waitUntil(20_000) { categoryModel.state.value.workspace.categories.singleOrNull()?.id == categoryId }
        scrollText(categoryName)
        compose.onNodeWithText(categoryName).performScrollTo().performTouchInput { click() }
        compose.waitUntil(10_000) { categoryModel.state.value.categoryId == categoryId }
        scrollText(title)
        compose.onNodeWithText(title).assertIsDisplayed()
        fixture.action("category.delete", json("categoryId" to categoryId), cookie = browserCookie)
        clickDescription("Refresh lists")
        compose.waitUntil(20_000) { categoryModel.state.value.workspace.categories.isEmpty() }
        assertNull("A category removed by another client must clear the active filter", categoryModel.state.value.categoryId)
        compose.onAllNodesWithText(categoryName).assertCountEquals(0)
        scrollText(title)
        compose.onNodeWithText(title).assertIsDisplayed()

        // Searching item content retrieves its enclosing list through the server.
        scrollTo(hasTestTag("library-search"), "library search")
        compose.onNodeWithTag("library-search").performTextInput(edited)
        waitText("Items & notes included")
        scrollText(title)
        compose.onNodeWithText(title).assertIsDisplayed()
        assertTrue(categoryModel.state.value.search?.results?.isNotEmpty() == true)
        compose.runOnIdle {
            categoryModel.collection("templates")
            assertNull("Changing scope must clear old results before the next response", categoryModel.state.value.search)
            categoryModel.collection("library")
            assertNull(categoryModel.state.value.search)
        }
        scrollTo(hasTestTag("library-search"), "library search")
        clickDescription("Clear search")
        scrollDescription("Favorite $title")
        clickDescription("Favorite $title")
        awaitStored("personal favorite") { fixture.list(title)?.getBoolean("favorite") == true }
        click("Favorites")
        scrollText(title)
        compose.onNodeWithText(title).assertIsDisplayed()

        // A real reusable master is started in the UI; its copy is independent.
        val masterTitle = "Weekend master ${fixture.runId}"
        val master = fixture.action("list.create", json("title" to masterTitle, "isTemplate" to true))
        val masterId = master.getString("createdListId")
        fixture.action("item.create", json("listId" to masterId, "text" to "Pack shoes", "checked" to true))
        clickDescription("Refresh lists")
        click("Templates")
        scrollText(masterTitle)
        click(masterTitle)
        scrollText("Start list")
        click("Start list")
        awaitStored("independent unchecked template copy") {
            val lists = fixture.workspace().getJSONArray("lists")
            (0 until lists.length()).map { lists.getJSONObject(it) }.any {
                it.getString("id") != masterId && it.getString("title").startsWith("$masterTitle — ") &&
                    !it.getBoolean("isTemplate") && !it.getJSONArray("items").getJSONObject(0).getBoolean("checked")
            }
        }
        assertTrue(fixture.list(masterTitle)!!.getJSONArray("items").getJSONObject(0).getBoolean("checked"))
        clickDescription("Back to library")
        click("Lists")

        // Archive and restore use the normal confirmation and collection UI.
        scrollText(title)
        click(title)
        scrollDescription("List options")
        clickDescription("List options")
        click("Archive for me")
        waitText("Archive this list for you?")
        click("Archive")
        awaitStored("archived list") { fixture.list(title)?.getBoolean("archived") == true }
        clickDescription("Back to library")
        scrollDescription("Library options")
        clickDescription("Library options")
        click("Archive")
        waitTag("page-archive")
        scrollText(title)
        click(title)
        scrollDescription("List options")
        clickDescription("List options")
        click("Restore to library")
        awaitStored("restored list") { fixture.list(title)?.getBoolean("archived") == false }
        clickDescription("Back to library")

        click("Settings")
        // A credential error must leave the valid phone session signed in.
        scrollText("Change password")
        click("Change password")
        field("Current password").performScrollTo().performTextReplacement("Incorrect-password")
        field("New password").performScrollTo().performTextReplacement("Unused-password-123")
        field("Repeat new password").performScrollTo().performTextReplacement("Unused-password-123")
        compose.onNodeWithTag("editor-save").performScrollTo().performClick()
        waitText("Current password is incorrect.")
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor-save").fetchSemanticsNodes().isEmpty() }
        waitDescription("Refresh lists")
        clickDescription("Refresh lists")
        scrollText("Forest")
        click("Forest")
        compose.onNodeWithText("Forest").assertIsSelected()
        compose.activityRule.scenario.recreate()
        waitText("Settings")
        scrollText("Forest")
        compose.onNodeWithText("Forest").assertIsSelected()
        assertEquals("dark", InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("appearance", 0).getString("theme", null))
        click("Paper")
        compose.onNodeWithText("Paper").assertIsSelected()

        // Sign-out goes through the same confirmation UI and retains server data.
        scrollText("Sign out")
        click("Sign out")
        waitText("Sign out?")
        compose.onAllNodesWithText("Sign out").onLast().performClick()
        waitText("Username")
        waitText("Signed out on this phone.")
        assertNull("Successful logout must not be shown as an error", viewModel().state.value.error)
        compose.onAllNodesWithText("Retry connection", useUnmergedTree = true).assertCountEquals(0)
        assertNotNull(fixture.list(title))
    }

    @Test fun incomingShareReplacesAnOpenCaptureDraft() {
        val fixture = LocalAcceptanceFixture()
        signIn(fixture)
        val workspace = fixture.action("list.create", json("title" to "Capture target ${fixture.runId}"))
        val listId = workspace.getString("createdListId")
        val mainModel = viewModel()
        compose.runOnIdle { mainModel.refresh() }
        compose.waitUntil(20_000) { mainModel.state.value.workspace.lists.any { it.id == listId } }

        val originalIntent = compose.runOnIdle { Intent(compose.activity.intent) }
        try {
        fun send(text: String) {
            compose.runOnIdle {
                compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
        }
        val first = "First incoming thought ${fixture.runId}"
        val second = "Replacement incoming thought ${fixture.runId}"
        send(first)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor-item-text").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor-item-text").assertTextContains(first)
        compose.onNodeWithTag("editor-item-text").performTextReplacement("An unsaved edit of the first share")
        val firstCaptureId = mainModel.state.value.captureId
        send(second)
        compose.waitUntil(10_000) { mainModel.state.value.captureId > firstCaptureId }
        compose.onNodeWithTag("editor-item-text").assertTextContains(second)
        assertEquals(second, mainModel.state.value.capture)
        // Sensitive incoming content is removed from the retained Activity intent.
        compose.runOnIdle { assertFalse(compose.activity.intent.hasExtra(Intent.EXTRA_TEXT)) }
        compose.onNodeWithText("Not now").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor-item-text").fetchSemanticsNodes().isEmpty() }
        assertNull(mainModel.state.value.capture)
        } finally {
            // ActivityScenario identifies lifecycle events using its original
            // launch intent. Real ACTION_SEND delivery changes Activity.intent;
            // restore the scenario identity after testing the actual app flow.
            compose.runOnIdle { compose.activity.intent = originalIntent }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test fun exportSurvivesRecreationAndFailedSharingClearsOldInvitation() {
        val fixture = LocalAcceptanceFixture()
        signIn(fixture)
        val title = "Lifecycle export ${fixture.runId}"
        val created = fixture.action("list.create", json("title" to title))
        val listId = created.getString("createdListId")
        fixture.action("item.create", json("listId" to listId, "text" to "Exported after rotation", "checked" to true))

        // A retained second instance runs the production ViewModel code and real
        // API without launching an external picker. The visible app still signs
        // in normally; this instance reads that same Keystore-backed session.
        val exportModel = viewModel("acceptance-export")
        compose.waitUntil(20_000) { exportModel.state.value.connected && !exportModel.state.value.starting }
        compose.runOnIdle { exportModel.exportBackup() }
        compose.waitUntil(20_000) { exportModel.state.value.exportReady && !exportModel.state.value.busy }
        compose.runOnIdle { exportModel.exportPickerLaunched() }
        compose.activityRule.scenario.recreate()
        val retained = viewModel("acceptance-export")
        assertSame("Export owner must survive Activity recreation", exportModel, retained)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "acceptance-export-${fixture.runId}.json")
        try {
            compose.runOnIdle { retained.finishExport(Uri.fromFile(file)) }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(20_000) { file.exists() && retained.state.value.message == "Backup saved." }
            val backup = JSONObject(file.readText())
            assertEquals(2, backup.getInt("version"))
            val lists = backup.getJSONArray("lists")
            val saved = (0 until lists.length()).map { lists.getJSONObject(it) }.single { it.getString("id") == listId }
            assertEquals(title, saved.getString("title"))
            assertTrue(saved.getJSONArray("items").getJSONObject(0).getBoolean("checked"))
            assertFalse(saved.has("role"))
            compose.runOnIdle { assertNull("Completed export must be consumed once", retained.consumeExport()) }
        } finally { file.delete() }

        val mainModel = viewModel()
        compose.runOnIdle { mainModel.refresh() }
        compose.waitUntil(20_000) { mainModel.state.value.workspace.lists.any { it.id == listId } }
        compose.runOnIdle { mainModel.select(listId); mainModel.loadSharing(listId) }
        compose.waitUntil(20_000) { !mainModel.state.value.busy && mainModel.state.value.sharing != null }
        compose.runOnIdle { mainModel.sharingAction("invite", listId) }
        compose.waitUntil(20_000) { !mainModel.state.value.busy && mainModel.state.value.sharing?.token != null }
        val oldLink = requireNotNull(mainModel.invitationLink(listId))
        // A genuine authenticated backend 404 deterministically exercises the
        // failure path, without network interception or production test hooks.
        compose.runOnIdle {
            mainModel.sharingAction("acceptance-invalid-route", listId)
            assertNull(mainModel.state.value.sharing?.token)
            assertNull(mainModel.invitationLink(listId))
        }
        compose.waitUntil(20_000) { !mainModel.state.value.busy && mainModel.state.value.error != null }
        assertNull(mainModel.state.value.sharing?.token)
        assertNull(mainModel.invitationLink(listId))
        assertTrue(mainModel.state.value.sharing?.inviteActive == true)
        compose.runOnIdle { mainModel.sharingAction("invite", listId) }
        compose.waitUntil(20_000) { !mainModel.state.value.busy && mainModel.state.value.sharing?.token != null }
        assertNotEquals(oldLink, mainModel.invitationLink(listId))
        assertEquals(404, fixture.raw("/api/invitations/preview", json("token" to oldLink.substringAfter("#invite=")), fixture.token).status)
        compose.runOnIdle { mainModel.select(null) }
    }

    @Test fun authModeSlidesAndRapidChangesPreserveOneForm() {
        val fixture = LocalAcceptanceFixture()
        withSystemAnimationScale(1f) {
            compose.activityRule.scenario.recreate()
            showSignIn()
            compose.onNodeWithTag("auth-username").performScrollTo().performTextReplacement(fixture.username)
            compose.onNodeWithTag("auth-password").performScrollTo().performTextReplacement(fixture.password)
            closeSoftKeyboard()
            compose.onNodeWithContentDescription("Show password").performClick()
            compose.onNodeWithTag("auth-mode-sign-in").performScrollTo().assertIsSelected()

            val start = authIndicatorLeft()
            compose.mainClock.autoAdvance = false
            try {
                compose.onNodeWithTag("auth-mode-create-account").performClick()
                compose.mainClock.advanceTimeBy(80)
                compose.waitForIdle()
                val middle = authIndicatorLeft()
                compose.mainClock.advanceTimeBy(420)
                compose.waitForIdle()
                val end = authIndicatorLeft()
                assertTrue("The selection must slide through an intermediate position", middle > minOf(start, end) + 1f && middle < maxOf(start, end) - 1f)
                compose.onNodeWithTag("auth-mode-create-account").assertIsSelected()
                compose.onNodeWithTag("auth-submit").assertTextContains("Create account")
                compose.mainClock.advanceTimeBy(100)
                compose.waitForIdle()
                assertEquals("The selection must settle within 500 ms", end, authIndicatorLeft(), 1f)

                // Reverse the target before each preceding animation can finish.
                repeat(6) { index ->
                    compose.onNodeWithTag(if (index % 2 == 0) "auth-mode-sign-in" else "auth-mode-create-account").performClick()
                    compose.mainClock.advanceTimeBy(32)
                }
                compose.onNodeWithTag("auth-mode-sign-in").performClick()
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                compose.onNodeWithTag("auth-mode-sign-in").assertIsSelected()
                compose.onNodeWithTag("auth-submit").assertTextContains("Sign in")
                compose.onAllNodesWithTag("auth-username").assertCountEquals(1)
                compose.onAllNodesWithTag("auth-password").assertCountEquals(1)
                compose.onNodeWithTag("auth-username").assertTextContains(fixture.username)
                compose.onNodeWithTag("auth-password").assertTextContains(fixture.password)
                compose.onNodeWithContentDescription("Hide password").assertExists().performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithContentDescription("Show password").assertExists()
            } finally {
                compose.mainClock.autoAdvance = true
            }
            // The preserved values must still authenticate through the ordinary app.
            compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
            waitTag("page-library")
        }
    }

    @Test fun testConfiguredZeroMotionScaleLeavesAuthAndNavigationUsable() {
        val fixture = LocalAcceptanceFixture()
        withSystemAnimationScale(0f) {
            compose.activityRule.scenario.recreate()
            showSignIn()
            compose.onNodeWithTag("auth-mode-sign-in").performScrollTo()
            val start = authIndicatorLeft()
            compose.mainClock.autoAdvance = false
            try {
                compose.onNodeWithTag("auth-mode-create-account").performClick()
                repeat(3) { compose.mainClock.advanceTimeByFrame() }
                compose.waitForIdle()
                val completed = authIndicatorLeft()
                assertTrue("Disabled motion must reach the other selection promptly", kotlin.math.abs(completed - start) > 1f)
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                assertEquals("Disabled motion must not leave a partially moved selection", completed, authIndicatorLeft(), 1f)
                compose.onNodeWithTag("auth-mode-create-account").assertIsSelected()
                compose.onNodeWithTag("auth-submit").assertTextContains("Create account")
                compose.onNodeWithTag("auth-mode-sign-in").performClick()
                repeat(3) { compose.mainClock.advanceTimeByFrame() }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            signIn(fixture)
            click("Settings")
            waitTag("page-settings")
            click("Lists")
            waitTag("page-library")
            compose.onNodeWithTag("library-search").assertExists()
        }
    }

    @Test fun authenticationDraftSurvivesRotationAndRecreation() {
        val fixture = LocalAcceptanceFixture()
        showSignIn()
        compose.onNodeWithTag("auth-username").performScrollTo().performTextReplacement(fixture.username)
        compose.onNodeWithTag("auth-password").performScrollTo().performClick().performTextReplacement(fixture.password)
        closeSoftKeyboard()
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithTag("auth-mode-create-account").performScrollTo().performClick()
        val originalOrientation = compose.runOnIdle { compose.activity.requestedOrientation }
        try {
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            assertAuthDraft(fixture)
            compose.onNodeWithTag("auth-submit").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.activityRule.scenario.recreate()
            assertAuthDraft(fixture)
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            assertAuthDraft(fixture)
            // Keep the keyboard open while checking that the primary action is reachable.
            compose.onNodeWithTag("auth-password").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("auth-password").assertIsFocused()
            compose.onNodeWithTag("auth-submit").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("auth-password").assertIsFocused()
            closeSoftKeyboard()
            compose.onNodeWithTag("auth-mode-sign-in").performScrollTo().performClick()
            compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
            waitTag("page-library")
            // Credentials survive only the active draft, then clear on authentication/sign-out.
            showSignIn()
            compose.onNodeWithTag("auth-password").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
            )
            compose.onNodeWithContentDescription("Show password").assertExists()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    @Test fun authenticationKeyboardKeepsTheCurrentLayoutAndFocusedField() {
        val fixture = LocalAcceptanceFixture()
        showSignIn()
        compose.onNodeWithTag("auth-username").performScrollTo().performTextReplacement(fixture.username)
        closeSoftKeyboard()
        val wide = compose.onAllNodesWithTag("auth-layout-wide").fetchSemanticsNodes().isNotEmpty()
        compose.onNodeWithTag("auth-password").performScrollTo().performClick().performTextReplacement(fixture.password)
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("auth-password").assertIsFocused()
        assertEquals("The keyboard must not replace the authentication layout", wide,
            compose.onAllNodesWithTag("auth-layout-wide").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("auth-submit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth-password").assertIsFocused()
        closeSoftKeyboard()
    }

    private fun assertAuthDraft(fixture: LocalAcceptanceFixture) {
        waitTag("auth-username")
        compose.onAllNodesWithTag("auth-username").assertCountEquals(1)
        compose.onAllNodesWithTag("auth-password").assertCountEquals(1)
        compose.onNodeWithTag("auth-username").assertTextContains(fixture.username)
        compose.onNodeWithTag("auth-password").assertTextContains(fixture.password)
        compose.onNodeWithTag("auth-mode-create-account").assertIsSelected()
        compose.onNodeWithContentDescription("Hide password").assertExists()
    }

    @Test fun listSelectionAndUnsavedEditorSurviveRotationAndRecreation() {
        val fixture = LocalAcceptanceFixture()
        val title = "Adaptive draft ${fixture.runId}"
        val created = fixture.action("list.create", json("title" to title))
        val listId = created.getString("createdListId")
        val original = "Before resize ${fixture.runId}"
        val replacement = "Retained through resize ${fixture.runId}"
        fixture.action("item.create", json("listId" to listId, "text" to original))
        signIn(fixture)
        scrollText(title)
        click(title)
        waitTag("page-detail:$listId")
        scrollText(original)
        click(original)
        editorText("editor-item-text", replacement)
        closeSoftKeyboard()
        val originalOrientation = compose.runOnIdle { compose.activity.requestedOrientation }
        try {
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            waitTag("editor-item-text")
            compose.onNodeWithTag("editor-item-text").assertTextContains(replacement)
            assertEquals(listId, viewModel().state.value.selectedId)
            compose.activityRule.scenario.recreate()
            waitTag("editor-item-text")
            compose.onNodeWithTag("editor-item-text").assertTextContains(replacement)
            assertEquals(listId, viewModel().state.value.selectedId)
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            compose.onNodeWithTag("editor-item-text").assertTextContains(replacement)
            saveEditor()
            awaitStored("one retained editor save") {
                fixture.item(title, replacement) != null && fixture.item(title, original) == null
            }
            assertEquals("Resizing must not duplicate the saved item", 1, fixture.list(title)!!.getJSONArray("items").length())
            scrollText(replacement)
            compose.onNodeWithText(replacement).assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBack()
            waitTag("page-library")
            assertNull(viewModel().state.value.selectedId)
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    private fun rotateTo(requested: Int, configuration: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == configuration }
        compose.waitForIdle()
    }

    @Test fun injectedHingesPreserveSelectionAndExcludeOcclusionInLtrAndRtl() {
        val fixture = LocalAcceptanceFixture()
        val title = "Fold continuity ${fixture.runId}"
        val listId = fixture.action("list.create", json("title" to title)).getString("createdListId")
        fixture.action("item.create", json("listId" to listId, "text" to "Across postures ${fixture.runId}"))
        signIn(fixture)
        scrollText(title)
        click(title)
        waitTag("page-detail:$listId")
        val activity = compose.activity
        val originalDirection = compose.runOnIdle { activity.window.decorView.layoutDirection }
        try {
            listOf(android.view.View.LAYOUT_DIRECTION_LTR, android.view.View.LAYOUT_DIRECTION_RTL).forEach { direction ->
                compose.runOnIdle { activity.window.decorView.layoutDirection = direction }
                compose.waitForIdle()
                assertNavigationDirection(direction)
                listOf(FoldingFeature.Orientation.VERTICAL, FoldingFeature.Orientation.HORIZONTAL).forEach { orientation ->
                    val unfoldedBaseline = compose.onNodeWithTag("page-detail:$listId").fetchSemanticsNode().boundsInWindow
                    val hinge = compose.runOnIdle {
                        createFoldingFeature(
                            activity = activity,
                            size = (activity.resources.displayMetrics.density * 16f).toInt().coerceAtLeast(2),
                            state = FoldingFeature.State.HALF_OPENED,
                            orientation = orientation,
                        )
                    }
                    windowLayout.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(hinge)))
                    compose.waitUntil(10_000) { paneAvoidsHinge("page-detail:$listId", hinge) }
                    compose.waitForIdle()
                    assertTrue("Selected detail must stay clear of the physical hinge ($direction, $orientation)", paneAvoidsHinge("page-detail:$listId", hinge))
                    listOf("library-pane", "detail-pane").forEach { tag ->
                        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty())
                            assertTrue("$tag must stay clear of the physical hinge ($direction, $orientation)", paneAvoidsHinge(tag, hinge))
                    }
                    assertEquals(listId, viewModel().state.value.selectedId)
                    assertSame("Changing posture must not replace the Activity", activity, compose.activity)
                    if (orientation == FoldingFeature.Orientation.HORIZONTAL) {
                        scrollDescription("List options")
                        clickDescription("List options")
                        click("Archive for me")
                        waitTag("gather-dialog")
                        assertTrue("The confirmation must stay clear of the tabletop hinge", paneAvoidsHinge("gather-dialog", hinge))
                        click("Cancel")
                        compose.waitUntil(10_000) { compose.onAllNodesWithTag("gather-dialog").fetchSemanticsNodes().isEmpty() }
                        assertFalse("Cancel must leave the list unarchived", fixture.list(title)!!.getBoolean("archived"))
                        assertEquals(listId, viewModel().state.value.selectedId)
                    }
                    windowLayout.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
                    compose.waitUntil(10_000) {
                        val restored = compose.onNodeWithTag("page-detail:$listId").fetchSemanticsNode().boundsInWindow
                        // Unfolding can replace a narrow rail with a wider drawer.
                        // The correct result is the original layout, not necessarily a larger pane.
                        kotlin.math.abs(restored.left - unfoldedBaseline.left) <= 1f &&
                            kotlin.math.abs(restored.top - unfoldedBaseline.top) <= 1f &&
                            kotlin.math.abs(restored.right - unfoldedBaseline.right) <= 1f &&
                            kotlin.math.abs(restored.bottom - unfoldedBaseline.bottom) <= 1f
                    }
                    assertEquals("Unfolding must keep the same selection", listId, viewModel().state.value.selectedId)
                    assertSame(activity, compose.activity)
                }
            }
        } finally {
            windowLayout.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
            compose.runOnIdle { activity.window.decorView.layoutDirection = originalDirection }
        }
    }

    private fun paneAvoidsHinge(tag: String, hinge: FoldingFeature): Boolean {
        val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        if (nodes.size != 1) return false
        val pane = nodes.single().boundsInWindow
        val obstruction = hinge.bounds
        return if (hinge.orientation == FoldingFeature.Orientation.VERTICAL)
            pane.right <= obstruction.left + 1f || pane.left >= obstruction.right - 1f
        else pane.bottom <= obstruction.top + 1f || pane.top >= obstruction.bottom - 1f
    }

    private fun assertNavigationDirection(direction: Int) {
        val sideNavigation = compose.onAllNodes(hasTestTag("navigation-rail") or hasTestTag("navigation-drawer")).fetchSemanticsNodes()
        if (sideNavigation.isNotEmpty()) {
            val center = sideNavigation.single().boundsInWindow.center.x
            val midpoint = compose.activity.windowManager.currentWindowMetrics.bounds.width() / 2f
            assertTrue("Side navigation must follow the actual layout direction", if (direction == android.view.View.LAYOUT_DIRECTION_RTL) center > midpoint else center < midpoint)
        } else {
            // Compact detail hides bottom navigation; expose it without changing the window.
            val model = viewModel()
            val selected = model.state.value.selectedId
            compose.runOnIdle { model.select(null) }
            waitTag("navigation-bottom")
            val lists = compose.onNode(hasText("Lists") and hasClickAction()).fetchSemanticsNode().boundsInWindow.center.x
            val settings = compose.onNode(hasText("Settings") and hasClickAction()).fetchSemanticsNode().boundsInWindow.center.x
            assertTrue("Bottom navigation must follow the actual layout direction", if (direction == android.view.View.LAYOUT_DIRECTION_RTL) lists > settings else lists < settings)
            compose.runOnIdle { model.select(selected) }
            waitTag("page-detail:$selected")
        }
    }

    @Test fun interruptedDetailNavigationAndRemoteDeletionKeepCorrectSelection() {
        val fixture = LocalAcceptanceFixture()
        val first = fixture.action("list.create", json("title" to "Motion first ${fixture.runId}")).getString("createdListId")
        val second = fixture.action("list.create", json("title" to "Motion second ${fixture.runId}")).getString("createdListId")
        val firstItem = "First detail item ${fixture.runId}"
        val secondItem = "Second detail item ${fixture.runId}"
        fixture.action("item.create", json("listId" to first, "text" to firstItem))
        fixture.action("item.create", json("listId" to second, "text" to secondItem))
        withSystemAnimationScale(1f) {
            compose.activityRule.scenario.recreate()
            signIn(fixture)
            val model = viewModel()
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { model.select(first) }
                compose.mainClock.advanceTimeBy(64)
                compose.runOnIdle { model.select(second) }
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                compose.onNodeWithTag("page-detail:$second").assertIsDisplayed()
                compose.onAllNodesWithTag("page-detail:$first").assertCountEquals(0)
                compose.onNodeWithText(secondItem).assertExists()
                compose.onAllNodesWithText(firstItem).assertCountEquals(0)
                assertEquals(second, model.state.value.selectedId)

                // Authoritative removal during entry must finish back at the library.
                compose.runOnIdle { model.select(first) }
                compose.mainClock.advanceTimeBy(64)
                fixture.action("list.delete", json("listId" to first))
                compose.runOnIdle { model.refresh() }
                compose.waitUntil(20_000) { !model.state.value.busy && model.state.value.selectedId == null }
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                compose.onNodeWithTag("page-library").assertIsDisplayed()
                compose.onAllNodesWithTag("page-detail:$first").assertCountEquals(0)
                compose.onAllNodesWithContentDescription("Back to library").assertCountEquals(0)
                compose.onNodeWithText("New list", useUnmergedTree = true).assertExists()
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }

    private fun authIndicatorLeft() = compose.onNodeWithTag("auth-mode-indicator", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left

    private fun <T> withSystemAnimationScale(scale: Float, action: () -> T): T {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val names = listOf(Settings.Global.ANIMATOR_DURATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE)
        val originals = names.associateWith { Settings.Global.getString(resolver, it) }
        try {
            instrumentation.uiAutomation.setAnimationScale(scale)
            compose.waitUntil(5_000) {
                names.all { Settings.Global.getFloat(resolver, it, 1f) == scale }
            }
            return action()
        } finally {
            originals.forEach { (name, original) ->
                val command = if (original == null) "settings delete global $name"
                    else "settings put global $name ${original.toFloat()}"
                instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
            }
        }
    }

    private fun viewModel(key: String? = null): GatherViewModel = compose.runOnIdle {
        val provider = ViewModelProvider(compose.activity)
        if (key == null) provider[GatherViewModel::class.java] else provider.get(key, GatherViewModel::class.java)
    }

    private fun signIn(fixture: LocalAcceptanceFixture) {
        showSignIn()
        compose.onNodeWithTag("auth-username").performScrollTo().performTextReplacement(fixture.username)
        compose.onNodeWithTag("auth-password").performScrollTo().performTextReplacement(fixture.password)
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
        waitTag("page-library")
    }

    private fun showSignIn() {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Username").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Username").fetchSemanticsNodes().isEmpty()) {
            click("Settings"); scrollText("Sign out"); click("Sign out")
            waitText("Sign out?"); compose.onAllNodesWithText("Sign out").onLast().performClick(); waitText("Username")
        }
        compose.onNodeWithTag("auth-mode-sign-in").performScrollTo().performClick()
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private fun editorText(tag: String, value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }
    private fun saveEditor() {
        val submit = hasTestTag("editor-save")
        compose.waitUntil(10_000) { compose.onAllNodes(submit).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(submit).performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(submit).fetchSemanticsNodes().isEmpty() }
    }
    private fun waitText(text: String) { compose.waitUntil(20_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitTag(tag: String) { compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitDescription(text: String) { compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(text: String) {
        waitText(text)
        // Screen headings can match navigation labels. Prefer the actionable
        // control so concise headings cannot turn a navigation step into a no-op.
        val controls = compose.onAllNodes(hasText(text) and hasClickAction())
        if (controls.fetchSemanticsNodes().isNotEmpty()) {
            controls.onFirst().performClick()
            return
        }
        // Material components can place their visible label beneath a separate
        // semantics boundary. Tap that real label's bounds, like a person does.
        compose.onAllNodesWithText(text, useUnmergedTree = true).onFirst().performTouchInput { click() }
    }
    private fun clickDescription(text: String) { waitDescription(text); compose.onNodeWithContentDescription(text).performClick() }
    private fun scrollText(text: String) {
        scrollTo(hasText(text), text)
    }
    private fun scrollDescription(text: String) {
        scrollTo(hasContentDescription(text), text)
    }
    private fun scrollTo(matcher: SemanticsMatcher, label: String) {
        var lastFailure: Throwable? = null
        try {
            compose.waitUntil(20_000) {
                // Expanded windows contain independent list/detail scroll areas.
                // Try a currently matching area first, then search each real pane.
                val matching = compose.onAllNodes(hasScrollToIndexAction() and hasAnyDescendant(matcher))
                val scrollables = if (matching.fetchSemanticsNodes().isNotEmpty()) matching
                    else compose.onAllNodes(hasScrollToIndexAction())
                scrollables.fetchSemanticsNodes().indices.any { index ->
                    runCatching { scrollables[index].performScrollToNode(matcher) }
                        .onFailure { lastFailure = it }.isSuccess
                }
            }
        } catch (error: Throwable) {
            throw AssertionError("The app did not display $label after refresh/navigation", lastFailure ?: error)
        }
    }
    private fun awaitStored(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) {
                compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("Refresh lists") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle()
                return
            }
            SystemClock.sleep(100)
        }
        fail("Server did not confirm $label within 20 seconds")
    }
}
