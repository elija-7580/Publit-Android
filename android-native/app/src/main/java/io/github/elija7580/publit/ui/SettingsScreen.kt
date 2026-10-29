package io.github.elija7580.publit.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elija7580.publit.BuildConfigInfo
import io.github.elija7580.publit.R
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.FavoritesStore
import io.github.elija7580.publit.data.PlaceRole
import io.github.elija7580.publit.data.SavedPlace

const val START_TAB_KEY = "startTab"
const val ONBOARDING_DONE_KEY = "onboardingDone"
private const val REPOSITORY = "https://github.com/elija-7580/Publit-Android"

@Composable
fun PublitAppIcon(size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.225f)).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(size * 1.5f))
    }
}

/** Settings (SettingsView on iOS), the parts that exist on Android so far. */
@Composable
fun SettingsScreen(prefs: SharedPreferences, onBack: () -> Unit) {
    val ink = LocalInk.current
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    var startTab by remember { mutableStateOf(prefs.getString(START_TAB_KEY, "nearby") ?: "nearby") }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
            Text(L10n.t("Settings"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Group(null) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        PublitAppIcon(56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Publit", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            Text(L10n.t("Public transport, without the noise."), color = ink.muted, fontSize = 14.sp)
                        }
                    }
                }
            }
            item {
                Group(null) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(L10n.t("Start screen"), fontSize = 16.sp)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("nearby" to L10n.t("Nearby"), "plan" to L10n.t("Plan")).forEachIndexed { i, (k, t) ->
                                SegmentedButton(startTab == k, { startTab = k; prefs.edit().putString(START_TAB_KEY, k).apply() },
                                    SegmentedButtonDefaults.itemShape(i, 2)) { Text(t) }
                            }
                        }
                    }
                }
            }
            item { Box(Modifier.padding(horizontal = 12.dp)) { SmartRoutingSettings() } }
            item {
                var alerts by remember { mutableStateOf(io.github.elija7580.publit.notify.LineAlerts.enabled(context)) }
                val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
                    alerts = ok; io.github.elija7580.publit.notify.LineAlerts.setEnabled(context, ok)
                }
                Box(Modifier.padding(horizontal = 12.dp)) {
                    OptionGroup(L10n.t("Notifications"), L10n.t("Publit checks your saved lines when you open it and from time to time in the background (iOS decides when), and tells you about alerts and cancelled departures.").replace(" (iOS decides when)", "")) {
                        SwitchRow(L10n.t("Disruptions on saved lines"), alerts) { on ->
                            if (on && android.os.Build.VERSION.SDK_INT >= 33 && !io.github.elija7580.publit.notify.TripNotifications.allowed(context)) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            else { alerts = on; io.github.elija7580.publit.notify.LineAlerts.setEnabled(context, on) }
                        }
                    }
                }
            }
            item {
                Group(L10n.t("Data"), footer = listOf(
                    L10n.t("Times marked live come from the operator's real-time feed; all others are timetable times."),
                    L10n.t("All information without guarantee. Times, platforms and prices come from the operators' open data and can change at short notice."))) {
                    LinkRow(L10n.t("Routing and timetables: Transitous and its data sources")) { open("https://transitous.org/sources/") }
                    Line()
                    LinkRow(L10n.t("Map data © OpenStreetMap contributors")) { open("https://www.openstreetmap.org/copyright") }
                }
            }
            item {
                Group(L10n.t("About"), footer = listOf(
                    L10n.t("Publit is open source (MIT). No account, no ads, no tracking. Saved places and trips stay on this device."),
                    "Publit ${BuildConfigInfo.version}")) {
                    LinkRow(L10n.t("Source code and feedback")) { open(REPOSITORY) }
                    Line()
                    LinkRow(L10n.t("Privacy policy")) { open("https://elija-7580.github.io/publit-site/privacy/") }
                }
            }
        }
    }
}

@Composable private fun Line() = HorizontalDivider(color = LocalInk.current.hairline, modifier = Modifier.padding(horizontal = 16.dp))

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) =
    Text(title, color = LocalInk.current.ink, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp))

@Composable
private fun Group(title: String?, footer: List<String> = emptyList(), content: @Composable () -> Unit) {
    val ink = LocalInk.current
    Column {
        title?.let { Text(it, color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 6.dp)) }
        Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) { Column { content() } }
        footer.forEach { Text(it, color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 6.dp)) }
    }
}

/** First start (OnboardingView on iOS): intro, location, on the way, Home and Work. */
@Composable
fun OnboardingScreen(favorites: FavoritesStore, onAskLocation: () -> Unit, onPick: (PlaceRole, (SavedPlace) -> Unit) -> Unit, onDone: () -> Unit) {
    val ink = LocalInk.current
    var step by remember { mutableIntStateOf(0) }
    val saved by favorites.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (step < 3) TextButton(onClick = onDone) { Text(L10n.t("Skip"), fontWeight = FontWeight.SemiBold, color = ink.ink) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
            when (step) {
                0 -> Page(null, L10n.t("Public transport, without the noise."),
                    L10n.t("Departures near you, routes across Europe and a guide until you get off. No ads, no account, no tracking."))
                1 -> Page(Icons.Filled.LocationOn, L10n.t("Departures where you are"),
                    L10n.t("Publit uses your location to show what leaves nearby and to plan from here. It is only sent with a search, never stored or shared."))
                2 -> Page(Icons.Filled.Sensors, L10n.t("On the way"),
                    L10n.t("Start a route and Publit guides you: live times, changes, where to get off, also on the Lock Screen. Add the Publit widget for your next departures."))
                else -> {
                    Icon(Icons.Filled.Home, null, Modifier.size(56.dp), tint = ink.ink)
                    Text(L10n.t("Home and Work"), fontWeight = FontWeight.Bold, fontSize = 28.sp)
                    Text(L10n.t("One tap to get home or to work, from anywhere. You can change them any time in Saved."), color = ink.muted, textAlign = TextAlign.Center)
                    Surface(shape = RoundedCornerShape(14.dp), color = ink.surface) {
                        Column {
                            PlaceRole.entries.forEachIndexed { i, role ->
                                if (i > 0) Line()
                                Row(Modifier.fillMaxWidth().clickable { onPick(role) { p -> favorites.set(p, role) } }.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (role == PlaceRole.HOME) Icons.Filled.Home else Icons.Filled.Work, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(role.title, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.weight(1f))
                                    Text(saved.place(role)?.name ?: L10n.t("Not set"), color = ink.muted, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
            repeat(4) { i -> Box(Modifier.padding(4.dp).size(7.dp).clip(RoundedCornerShape(50)).background(if (i == step) ink.ink else ink.hairline)) }
        }
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                when (step) { 1 -> { onAskLocation(); step = 2 }; 3 -> onDone(); else -> step += 1 }
            }, Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) {
                Text(when (step) { 1 -> L10n.t("Allow location"); 3 -> L10n.t("Get started"); else -> L10n.t("Continue") }, fontWeight = FontWeight.SemiBold)
            }
            if (step == 1) TextButton(onClick = { step = 2 }, Modifier.fillMaxWidth()) { Text(L10n.t("Not now"), color = ink.ink) }
        }
    }
}

@Composable
private fun Page(icon: androidx.compose.ui.graphics.vector.ImageVector?, title: String, text: String) {
    val ink = LocalInk.current
    if (icon != null) Icon(icon, null, Modifier.size(56.dp), tint = ink.ink) else PublitAppIcon(96.dp)
    Text(title, fontWeight = FontWeight.Bold, fontSize = 28.sp, textAlign = TextAlign.Center, lineHeight = 34.sp)
    Text(text, color = ink.muted, textAlign = TextAlign.Center, fontSize = 16.sp)
}
