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

    /** Nearby → Where to now / Home / Work: its own search, as app.goNow on iOS. */
    private val goNow: PlanViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as PublitApplication
                return PlanViewModel(app.client, app.location, app.crashLog, getSharedPreferences("publit", MODE_PRIVATE)) as T
            }
        })["goNow", PlanViewModel::class.java]
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
        shared(intent)
    }

    /** A place shared or opened from another app: a route there (as "place:" for the app's navigation). */
    private fun shared(i: android.content.Intent?) {
        // publit:// links and https://publit.eu/open/#… links from iPhone or Android.
        val raw = i?.data ?: i?.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { t -> Regex("https://\\S+").find(t)?.value?.let(android.net.Uri::parse) }
        val app = raw?.let { u -> if (u.scheme == "publit") u else io.github.elija7580.publit.data.ShareLinks.appUrl(u)?.let(android.net.Uri::parse) }
        when (app?.host) {
            "route" -> { openTrip.value = "route:" + (app.encodedQuery ?: ""); return }
            "trip", "tickets" -> { app.lastPathSegment?.let { openTrip.value = it }; return }
            "stop" -> { app.lastPathSegment?.let { id -> openTrip.value = "stop:" + id + "|" + (app.getQueryParameter("name") ?: "") }; return }
            "place" -> {
                val lat = app.getQueryParameter("lat")?.toDoubleOrNull(); val lon = app.getQueryParameter("lon")?.toDoubleOrNull()
                if (lat != null && lon != null) { openTrip.value = "place:${app.getQueryParameter("name") ?: ""}|$lat|$lon"; return }
            }
        }
        i?.data?.takeIf { it.scheme == "publit" && it.host == "go" }?.lastPathSegment?.let { openTrip.value = "go:$it"; return }
        val text = SharedPlace.text(i) ?: return
        lifecycleScope.launch {
            val f = SharedPlace.resolve(text)
            openTrip.value = if (f == null) "place:" else "place:${f.name}|${f.at.lat}|${f.at.lon}"
        }
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
        if (savedInstanceState == null) shared(intent)
        lifecycleScope.launch { runCatching { io.github.elija7580.publit.notify.LineAlerts.check(app); io.github.elija7580.publit.notify.LineAlerts.schedule(app) } }
        setContent { PublitApp(nearby, plan, app.client, app.favorites, getSharedPreferences("publit", MODE_PRIVATE), app.trips, app.passes, openTrip, crash, goNow) }
    }
}
