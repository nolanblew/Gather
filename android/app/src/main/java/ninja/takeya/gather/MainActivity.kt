package ninja.takeya.gather

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: GatherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        receive(intent)
        setContent {
            val posture = remember {
                WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this).map { info ->
                    info.displayFeatures.filterIsInstance<FoldingFeature>()
                }
            }
            val folds by posture.collectAsStateWithLifecycle(initialValue = emptyList())
            GatherApp(model, folds)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.refresh()
                while (true) {
                    delay(30000)
                    model.refresh()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> model.incoming(intent.dataString)
            Intent.ACTION_SEND ->
                if (intent.type == "text/plain")
                    model.incoming(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(), true)
        }
        // Invitation tokens and captures are memory-only, never retained as activity intent extras.
        intent?.data = null
        intent?.removeExtra(Intent.EXTRA_TEXT)
    }
}
