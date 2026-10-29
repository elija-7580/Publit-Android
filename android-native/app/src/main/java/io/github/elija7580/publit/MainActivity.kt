package io.github.elija7580.publit

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.elija7580.publit.ui.NearbyViewModel
import io.github.elija7580.publit.ui.PublitApp
import io.github.elija7580.publit.ui.PlanViewModel

class MainActivity : ComponentActivity() {
    private val nearby: NearbyViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as PublitApplication
                return NearbyViewModel(app.client, app.location, app.crashLog, getSharedPreferences("publit", MODE_PRIVATE)) as T
            }
        }
    }

    private val plan: PlanViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as PublitApplication
                return PlanViewModel(app.client, app.location, app.crashLog, getSharedPreferences("publit", MODE_PRIVATE)) as T
            }
        }
    }

    /** A notification tapped: the saved trip to open. */
    private val openTrip = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(io.github.elija7580.publit.notify.TripNotifications.EXTRA_TRIP)?.let { openTrip.value = it }
        stopLink(intent)?.let { openTrip.value = it }
    }

    /** A widget tapped: its stop, as a "stop:" link for the app's navigation. */
    private fun stopLink(i: android.content.Intent?): String? =
        i?.getStringExtra("stopId")?.takeIf { it.isNotEmpty() }?.let { "stop:" + it + "|" + (i.getStringExtra("stopName") ?: "") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PublitApplication
        val crash = if (savedInstanceState == null) app.crashLog.take() else null
        intent?.getStringExtra(io.github.elija7580.publit.notify.TripNotifications.EXTRA_TRIP)?.let { openTrip.value = it }
        stopLink(intent)?.let { openTrip.value = it }
        lifecycleScope.launch { runCatching { io.github.elija7580.publit.notify.LineAlerts.check(app); io.github.elija7580.publit.notify.LineAlerts.schedule(app) } }
        setContent { PublitApp(nearby, plan, app.client, app.favorites, getSharedPreferences("publit", MODE_PRIVATE), app.trips, app.passes, openTrip, crash) }
    }
}
