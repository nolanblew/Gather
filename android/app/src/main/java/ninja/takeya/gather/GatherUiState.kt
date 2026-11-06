package ninja.takeya.gather

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*

/** Configuration-retained UI state only; never saved to a Bundle, preferences, or disk. */
class AuthDraft {
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var register by mutableStateOf(false)
    var visible by mutableStateOf(false)
    var passwordHelp by mutableStateOf(false)

    fun clear() {
        username = ""
        password = ""
        register = false
        visible = false
        passwordHelp = false
    }
}

class GatherUiState {
    val auth = AuthDraft()
    private val listStates = mutableMapOf<String, LazyListState>()
    var settings by mutableStateOf(false)
    private var activeEditor by mutableStateOf<Editor?>(null)
    var editor: Editor?
        get() = activeEditor
        set(value) {
            if (activeEditor != value) {
                editorDraft.clear()
                editorScroll = ScrollState(0)
            }
            activeEditor = value
        }

    val editorDraft = mutableMapOf<String, MutableState<*>>()
    var editorScroll = ScrollState(0)
    var settingsScroll = LazyGridState()
    private var captureId: Long? = null

    fun prepareCapture(id: Long) {
        if (captureId != id) {
            editorDraft.clear()
            editorScroll = ScrollState(0)
            captureId = id
        }
    }

    fun openCapture(id: Long) {
        prepareCapture(id)
        editor = Editor.Capture
    }

    fun listScroll(key: String): LazyListState = listStates.getOrPut(key) { LazyListState() }

    fun clearAccount() {
        auth.clear()
        editor = null
        settings = false
        listStates.clear()
        settingsScroll = LazyGridState()
        editorDraft.clear()
        captureId = null
    }
}

internal val LocalEditorDraft =
    staticCompositionLocalOf<MutableMap<String, MutableState<*>>> {
        error("Editor draft is not provided")
    }

@Suppress("UNCHECKED_CAST")
@Composable
internal fun <T> rememberEditorDraft(name: String, initial: () -> T): MutableState<T> {
    val draft = LocalEditorDraft.current
    return remember(draft, name) {
        draft.getOrPut(name) { mutableStateOf(initial()) } as MutableState<T>
    }
}
