package ninja.takeya.gather

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GatherOfflineDeviceSmokeTest {
    @Test fun runStage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val stage = args.getString("gatherSmokeStage")
        assumeTrue("Run the staged offline smoke test explicitly", stage != null)
        val server = URI(BuildConfig.SERVER_URL)
        require(BuildConfig.DEBUG && server.scheme == "http" && server.host in listOf("127.0.0.1", "localhost") && server.port == 48733)
        val listTitle = requireNotNull(args.getString("gatherSmokeList"))
        val itemText = requireNotNull(args.getString("gatherSmokeItem"))
        val username = requireNotNull(args.getString("gatherTestUsername"))
        require(username.startsWith("android-e2e-"))
        val application = instrumentation.targetContext.applicationContext as GatherApplication
        lateinit var model: GatherViewModel
        instrumentation.runOnMainSync { model = application.gatherModel }
        fun list() = model.state.value.workspace.lists.singleOrNull { it.title == listTitle }
        fun item() = list()?.items?.singleOrNull { it.text == itemText }

        when (stage) {
            "prepare", "capture" -> {
                val fixture = LocalAcceptanceFixture()
                instrumentation.runOnMainSync {
                    if (model.state.value.username != fixture.username) model.authenticate(fixture.username, fixture.password, register = false)
                }
                awaitState("online cached item") { model.state.value.connected && item() != null }
                if (stage == "prepare") {
                    assertFalse(item()!!.checked)
                    return
                }
                val screenshotItem = args.getString("gatherSmokeScreenshotItem") ?: itemText
                val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val activity = instrumentation.startActivitySync(intent)
                try {
                    instrumentation.runOnMainSync {
                        val list = checkNotNull(list())
                        model.select(list.id)
                        model.ui.editor = Editor.ItemForm(list, list.items.single { it.text == screenshotItem })
                    }
                    val screenshot = captureWindow(activity.window)
                    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "acceptance").apply { mkdirs() }
                    File(directory, "android-item-details.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                }
            }
            "enqueue" -> {
                awaitState("cached item while offline") { model.state.value.workspaceReady && !model.state.value.connected && item() != null }
                assertTrue(model.state.value.username == username)
                assertFalse(item()!!.checked)
                instrumentation.runOnMainSync {
                    val list = checkNotNull(list())
                    val item = checkNotNull(item())
                    model.action("item.update", payload("listId" to list.id, "itemId" to item.id, "checked" to true))
                }
                awaitState("queued item completion") { model.state.value.pendingChanges == 1 && item()?.checked == true }
                assertFalse(model.state.value.connected)
            }
            "restore" -> {
                awaitState("queued item completion after process restart") { model.state.value.pendingChanges == 1 && item()?.checked == true }
                assertFalse(model.state.value.connected)
            }
            "reconnect" -> {
                val fixture = LocalAcceptanceFixture()
                awaitState("synced item completion") {
                    model.state.value.connected && model.state.value.pendingChanges == 0 && item()?.checked == true
                }
                assertTrue(checkNotNull(fixture.item(listTitle, itemText)).getBoolean("checked"))
            }
            else -> error("Unknown Android smoke test stage $stage")
        }
    }

    private fun captureWindow(window: Window): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var screenshot: Bitmap? = null
        awaitState("rendered item editor") {
            val copy = CountDownLatch(1)
            var requested = false
            var frame: Bitmap? = null
            instrumentation.runOnMainSync {
                val editor = WindowInspector.getGlobalWindowViews().lastOrNull {
                    it !== window.decorView && it.isShown && it.width > 0 && it.height > 0
                }
                if (editor != null) {
                    val request = PixelCopy.Request.Builder.ofWindow(editor).build()
                    PixelCopy.request(request, editor.context.mainExecutor) { result ->
                        if (result.status == PixelCopy.SUCCESS) frame = result.bitmap
                        copy.countDown()
                    }
                    requested = true
                }
            }
            if (requested && copy.await(5, TimeUnit.SECONDS)) {
                if (frame?.hasVisibleContent() == true) screenshot = frame
                else frame?.recycle()
            }
            screenshot != null
        }
        return checkNotNull(screenshot)
    }

    private fun Bitmap.hasVisibleContent(): Boolean {
        val colors = mutableSetOf<Int>()
        for (y in 0 until height step maxOf(1, height / 48)) {
            for (x in 0 until width step maxOf(1, width / 24)) {
                val color = getPixel(x, y)
                if (color ushr 24 == 255) colors += color
            }
        }
        return colors.size > 12
    }

    private fun awaitState(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(250)
        }
        throw AssertionError("Did not observe $label")
    }
}
