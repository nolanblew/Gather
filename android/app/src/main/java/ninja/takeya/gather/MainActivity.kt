package ninja.takeya.gather

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    private val model get() = (application as GatherApplication).gatherModel

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
                val connectivity = getSystemService(ConnectivityManager::class.java)
                val reconnect = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { model.refresh() }
                    override fun onLost(network: Network) {
                        if (connectivity.activeNetwork == null) model.refresh()
                    }
                }
                connectivity.registerDefaultNetworkCallback(reconnect, Handler(Looper.getMainLooper()))
                try {
                    model.refresh()
                    while (true) {
                        delay(30000)
                        model.refresh()
                    }
                } finally {
                    connectivity.unregisterNetworkCallback(reconnect)
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
