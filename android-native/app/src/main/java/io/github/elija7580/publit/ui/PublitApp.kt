package io.github.elija7580.publit.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.composable
import androidx.compose.material3.NavigationBarItem
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.ModeFilter
import io.github.elija7580.publit.data.Reach
import io.github.elija7580.publit.location.LocationAccess
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun PublitApp(vm: NearbyViewModel, plan: PlanViewModel, client: io.github.elija7580.publit.data.TransitousClient,
              favorites: io.github.elija7580.publit.data.FavoritesStore, prefs: android.content.SharedPreferences, trips: io.github.elija7580.publit.data.TripStore, passes: io.github.elija7580.publit.tickets.PassStore,
              openTrip: kotlinx.coroutines.flow.MutableStateFlow<String?>, crashReport: String?) {
    PublitTheme {
        val ink = LocalInk.current
        val nav = androidx.navigation.compose.rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: "nearby"
        var savedLine by remember { mutableStateOf<io.github.elija7580.publit.data.SavedLine?>(null) }
        var pickFor by remember { mutableStateOf<Pair<String, (io.github.elija7580.publit.data.SavedPlace) -> Unit>?>(null) }
        var onboarding by remember { mutableStateOf(!prefs.getBoolean(ONBOARDING_DONE_KEY, false)) }
        val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.permissionResult() }
        LaunchedEffect(Unit) { if (prefs.getString(START_TAB_KEY, "nearby") == "plan") nav.navigate("plan") { popUpTo("nearby") { saveState = true }; launchSingleTop = true } }
        val tripLink by openTrip.collectAsStateWithLifecycle()
        LaunchedEffect(tripLink) {
            val link = tripLink ?: return@LaunchedEffect
            openTrip.value = null
            if (link.startsWith("stop:")) {
                val (id, name) = link.removePrefix("stop:").split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                nav.navigate("stop?id=${Uri.encode(id)}&name=${Uri.encode(name)}&exact=false")
            } else nav.navigate("savedtrip?id=$link") { launchSingleTop = true }
        }
        var pendingPass by remember { mutableStateOf<PendingImport?>(null) }
        var tripTarget by remember { mutableStateOf<TripTarget?>(null) }
        val openTrip = { t: TripTarget -> tripTarget = t; nav.navigate("trip") }
        val tabs = listOf(
            Triple("nearby", L10n.t("Nearby"), androidx.compose.material.icons.Icons.Filled.NearMe),
            Triple("plan", L10n.t("Plan"), androidx.compose.material.icons.Icons.Filled.Route),
            Triple("tickets", L10n.t("Tickets"), androidx.compose.material.icons.Icons.Filled.ConfirmationNumber),
            Triple("saved", L10n.t("Saved"), androidx.compose.material.icons.Icons.Filled.Star),
        )
        androidx.compose.material3.Scaffold(
            containerColor = ink.ground,
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            bottomBar = { androidx.compose.foundation.layout.Column {
                if (!route.startsWith("savedtrip")) LiveTripBar(trips) { t -> nav.navigate("savedtrip?id=${t.id}") { launchSingleTop = true } }
                androidx.compose.material3.NavigationBar(containerColor = ink.surface, tonalElevation = 0.dp) {
                    tabs.forEach { (r, title, icon) ->
                        val selected = route == r || (r == "nearby" && (route.startsWith("stop") || route == "trip" || route == "stationmap" || (route == "settings" && false))) || (r == "tickets" && route.startsWith("pass") && !route.startsWith("past")) || (r == "saved" && (route == "line" || route == "settings" || route == "pasttrips" || route.startsWith("savedtrip")))
                        NavigationBarItem(
                            selected = selected,
                            onClick = { if (!selected || route != r) nav.navigate(r) { popUpTo("nearby") { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { androidx.compose.material3.Icon(icon, null) }, label = { Text(title) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = ink.ink, selectedTextColor = ink.ink, indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unselectedIconColor = ink.muted, unselectedTextColor = ink.muted),
                        )
                    }
                }
            } },
        ) { padding ->
            androidx.navigation.compose.NavHost(nav, startDestination = "nearby", modifier = Modifier.padding(padding)) {
                composable("nearby") {
                    NearbyScreen(vm, onOpenMap = { nav.navigate("stationmap") }, onSettings = { nav.navigate("settings") },
                        pinned = favorites.state.collectAsStateWithLifecycle().value.let { st -> io.github.elija7580.publit.data.PlaceRole.entries.mapNotNull { st.place(it) } },
                        onWhereTo = { plan.route(Endpoint.Here, null); plan.requestSearch(); nav.navigate("plan") { popUpTo("nearby"); launchSingleTop = true } },
                        nextTrip = trips.state.collectAsStateWithLifecycle().value.let { st -> st.trips.filter { t -> t.id != st.activeId && t.startTime > java.time.Instant.now() }.minByOrNull { it.startTime } },
                        onOpenSaved = { id -> nav.navigate("savedtrip?id=$id") },
                        onRoute = { name, at -> plan.route(Endpoint.Here, Endpoint.At(name, at)); nav.navigate("plan") { popUpTo("nearby"); launchSingleTop = true } }, onOpenTrip = { st, g -> openTrip(TripTarget(st, g.name, g.id, g.coordinate)) }) { g -> nav.navigate("stop?id=${Uri.encode(g.id)}&name=${Uri.encode(g.name)}&exact=${g.isPole}") }
                }
                composable("stop?id={id}&name={name}&exact={exact}") { e ->
                    val a = e.arguments
                    StopScreen(client, favorites, a?.getString("id").orEmpty(), a?.getString("name").orEmpty(), a?.getString("exact") == "true", onBack = { nav.popBackStack() },
                        onOpenTrip = { st -> openTrip(TripTarget(st, a?.getString("name").orEmpty(), a?.getString("id").orEmpty(), st.place.coordinate)) },
                        onOpenLine = { l -> savedLine = l; nav.navigate("line") })
                }
                composable("stationmap") {
                    StationMapScreen(client, vm.state.value.fix?.coordinate, onBack = { nav.popBackStack() }, onRoute = { name, at, to ->
                        val e = Endpoint.At(name, at)
                        if (to) plan.route(Endpoint.Here, e) else plan.route(e, null)
                        nav.navigate("plan") { popUpTo("nearby"); launchSingleTop = true }
                    }) { id, name, exact ->
                        nav.navigate("stop?id=${Uri.encode(id)}&name=${Uri.encode(name)}&exact=$exact")
                    }
                }
                composable("trip") {
                    val t = tripTarget
                    if (t == null) LaunchedEffect(Unit) { nav.popBackStack() } else
                    TripScreen(client, favorites, t, onBack = { nav.popBackStack() }) { from, to ->
                        fun ep(p: io.github.elija7580.publit.data.Place) = Endpoint.At(p.name, p.coordinate)
                        plan.route(from?.let(::ep) ?: Endpoint.Here, to?.let(::ep))
                        nav.navigate("plan") { popUpTo("nearby"); launchSingleTop = true }
                    }
                }
                composable("plan") {
                    PlanScreen(plan, onSettings = { nav.navigate("settings") }, pinned = favorites.state.collectAsStateWithLifecycle().value.let { st -> io.github.elija7580.publit.data.PlaceRole.entries.mapNotNull { st.place(it) } }) { it, from, to -> TripActions(trips, it, from, to) { t -> nav.navigate("savedtrip?id=${t.id}") } }
                }
                composable("savedtrip?id={id}") { e ->
                    SavedTripScreen(client, trips, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) {
                        TripPasses(passes, it.id, it.startTime, onOpen = { id -> nav.navigate("pass?id=$id") }, onAdd = { nav.navigate("passimport?trip=${it.id}") })
                    }
                }
                composable("tickets") {
                    PassWalletScreen(passes, onOpen = { nav.navigate("pass?id=$it") }, onAdd = { nav.navigate("passimport?trip=") },
                        onArchive = { nav.navigate("passarchive") }, onSettings = { nav.navigate("settings") })
                }
                composable("pass?id={id}") { e ->
                    PassDetailScreen(passes, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) { id ->
                        passes.pass(id)?.let { pendingPass = PendingImport(it, null, null, 0, null); nav.navigate("passedit?new=false") }
                    }
                }
                composable("passarchive") { PassArchiveScreen(passes, onBack = { nav.popBackStack() }) { nav.navigate("pass?id=$it") } }
                composable("passimport?trip={trip}") { e ->
                    PassImportScreen(passes, e.arguments?.getString("trip")?.takeIf { it.isNotEmpty() }, onBack = { nav.popBackStack() }) {
                        pendingPass = it; nav.navigate("passedit?new=true") { popUpTo("passimport?trip={trip}") { inclusive = true } }
                    }
                }
                composable("passedit?new={new}") { e ->
                    val pending = pendingPass
                    if (pending == null) LaunchedEffect(Unit) { nav.popBackStack() } else {
                        val isNew = e.arguments?.getString("new") == "true"
                        PassEditorScreen(passes, pending, isNew, onCancel = { nav.popBackStack() }) { saved ->
                            pendingPass = null
                            nav.popBackStack()
                            if (isNew) nav.navigate("pass?id=${saved.id}")
                        }
                    }
                }
                composable("pasttrips") {
                    PastTripsScreen(trips, onBack = { nav.popBackStack() }) { id -> nav.navigate("savedtrip?id=$id") }
                }
                composable("saved") {
                    val pick = pickFor
                    if (pick != null) PlaceSearch(plan, allowHere = false, onBack = { pickFor = null },
                        onMatch = { m -> pick.second(io.github.elija7580.publit.data.SavedPlace.of(m)); pickFor = null }) { }
                    else SavedScreen(favorites, trips, onOpenTrip = { id -> nav.navigate("savedtrip?id=$id") }, onPastTrips = { nav.navigate("pasttrips") },
                        onRoute = { p -> plan.route(Endpoint.Here, Endpoint.At(p.name, p.coordinate)); nav.navigate("plan") { popUpTo("nearby"); launchSingleTop = true } },
                        onOpenStop = { p -> nav.navigate("stop?id=${Uri.encode(p.stopId ?: p.id)}&name=${Uri.encode(p.name)}&exact=${p.exact == true}") },
                        onOpenLine = { l -> savedLine = l; nav.navigate("line") },
                        onSearch = { title, done -> pickFor = title to done },
                        onSettings = { nav.navigate("settings") })
                }
                composable("line") {
                    val l = savedLine
                    if (l == null) LaunchedEffect(Unit) { nav.popBackStack() } else
                    LineDetailScreen(client, favorites, l, onBack = { nav.popBackStack() }) { st -> openTrip(TripTarget(st, l.stopName, l.stopId, l.coordinate)) }
                }
                composable("settings") { SettingsScreen(prefs) { nav.popBackStack() } }
            }
        }
        if (onboarding) androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = ink.ground) {
            val pick = pickFor
            if (pick != null) PlaceSearch(plan, allowHere = false, onBack = { pickFor = null },
                onMatch = { m -> pick.second(io.github.elija7580.publit.data.SavedPlace.of(m)); pickFor = null }) { }
            else OnboardingScreen(favorites,
                onAskLocation = { askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                onPick = { role, done -> pickFor = role.title to done },
                onDone = { prefs.edit().putBoolean(ONBOARDING_DONE_KEY, true).apply(); onboarding = false })
        }
        // A moment of the logo while location and the first departures load underneath.
        var splash by remember { mutableStateOf(!onboarding) }
        val nearbyState by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(2500); splash = false }
        LaunchedEffect(nearbyState.groups.isNotEmpty()) { if (nearbyState.groups.isNotEmpty()) { kotlinx.coroutines.delay(900); splash = false } }
        androidx.compose.animation.AnimatedVisibility(splash, enter = androidx.compose.animation.EnterTransition.None,
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(250))) { LaunchSplash() }
        CrashDialog(crashReport)
    }
}

@Composable
private fun CrashDialog(report: String?) {
    var shown by remember { mutableStateOf(report != null) }
    if (!shown || report == null) return
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = { shown = false },
        title = { Text("Publit ist abgestürzt") },
        text = {
            Column {
                Text("Kopiere den Bericht und schick ihn dem Entwickler.", fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text(report.take(1200), fontSize = 10.sp, lineHeight = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.height(220.dp).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(report)); shown = false }) { Text("Kopieren") } },
        dismissButton = { TextButton(onClick = { shown = false }) { Text("Schließen") } },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(vm: NearbyViewModel, onOpenMap: () -> Unit = {}, onSettings: () -> Unit = {}, onWhereTo: () -> Unit = {},
                 onRoute: (String, io.github.elija7580.publit.core.LatLon) -> Unit = { _, _ -> },
                 pinned: List<io.github.elija7580.publit.data.SavedPlace> = emptyList(), nextTrip: io.github.elija7580.publit.data.SavedTrip? = null, onOpenSaved: (String) -> Unit = {}, onOpenTrip: (io.github.elija7580.publit.data.StopTime, io.github.elija7580.publit.data.StopGroup) -> Unit = { _, _ -> }, onOpenStop: (io.github.elija7580.publit.data.StopGroup) -> Unit) {
    val ink = LocalInk.current
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(15_000) } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.permissionResult() }

    // Position and board run only while the screen is in front.
    LifecycleResumeEffect(Unit) {
        vm.setActive(true)
        onPauseOrDispose { vm.setActive(false) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L10n.t("Nearby"), fontWeight = FontWeight.Bold, fontSize = 32.sp, modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp))
            androidx.compose.material3.IconButton(onClick = onOpenMap) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Map, L10n.t("Station map"))
            }
            androidx.compose.material3.IconButton(onClick = onSettings, modifier = Modifier.padding(end = 8.dp)) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Settings, L10n.t("Settings"))
            }
        }
        when {
            state.access == LocationAccess.NOT_ASKED -> Message(
                title = L10n.t("Allow location"),
                text = L10n.t("Allow location access in Settings to see departures near you. Trip planning works without it."),
                button = L10n.t("Allow location"),
            ) { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }

            !state.servicesOn -> Message(
                title = L10n.t("Location is off"),
                text = "Schalte den Standort in den Schnelleinstellungen ein.",
                button = L10n.t("Settings"),
            ) { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

            else -> PullToRefreshBox(isRefreshing = false, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                    io.github.elija7580.publit.data.CoverageGuide.notice(state.fix?.coordinate)?.let { n -> item("coverage") { CoverageBanner(listOf(n)) } }
                    item("go") {
                        // "Where to now?", Home and Work: a route from here in one tap (goSection on iOS).
                        Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                            Column {
                                Row(Modifier.fillMaxWidth().clickable(onClick = onWhereTo).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(L10n.t("To"), color = ink.muted, fontSize = 14.sp, modifier = Modifier.width(56.dp))
                                    Text(L10n.t("Where to now?"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = ink.muted, modifier = Modifier.weight(1f))
                                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, null,
                                        Modifier.background(ink.ink, androidx.compose.foundation.shape.CircleShape).padding(8.dp), tint = ink.onInk)
                                }
                                nextTrip?.let { t ->
                                    HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                                    Row(Modifier.fillMaxWidth().clickable { onOpenSaved(t.id) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.DateRange, null, Modifier.width(20.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(L10n.f("Next trip: %@", t.toName), fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            Text(io.github.elija7580.publit.data.TripStatus.at(java.time.Instant.now(), t.itinerary).headline, color = ink.muted, fontSize = 14.sp)
                                        }
                                    }
                                }
                                pinned.forEach { p ->
                                    HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                                    Row(Modifier.fillMaxWidth().clickable { onRoute(p.name, p.coordinate) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.Icon(if (p.role == io.github.elija7580.publit.data.PlaceRole.HOME) androidx.compose.material.icons.Icons.Filled.Home
                                            else androidx.compose.material.icons.Icons.Filled.Work, null, Modifier.width(20.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Text(p.role?.title ?: p.name, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.weight(1f))
                                        Text(p.name, color = ink.muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (state.available.size > 1 || state.filter.isActive) item("chips") {
                        ModeChips(state.available, state.filter.included, state.filter.isActive) { vm.setFilter(state.filter.toggleOnly(it)) }
                    }
                    if (state.groups.isEmpty()) item("status") {
                        Status(state, onShowAll = { vm.setFilter(ModeFilter()) }, onSettings = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        })
                    }
                    state.farDistance?.let { d -> if (state.groups.isNotEmpty()) item("far") {
                        Text(Reach.farNote(d), color = ink.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    } }
                    if (state.groups.isNotEmpty() && state.error != null) item("stale") {
                        Text(L10n.t("Couldn't refresh. Times may be out of date."), color = ink.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    items(state.groups, key = { it.id }) { group ->
                        Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable { onOpenStop(group) }) {
                                        StationHeader(group, nearest = group.id == state.groups.first().id)
                                    }
                                    // The arrow plans a route there from here, now.
                                    androidx.compose.material3.IconButton(onClick = { onRoute(group.name, group.coordinate) }, modifier = Modifier.padding(end = 8.dp)) {
                                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Directions, L10n.t("Route here"),
                                            Modifier.background(ink.fill, androidx.compose.foundation.shape.CircleShape).padding(8.dp))
                                    }
                                }
                                group.departures.take(5).forEachIndexed { i, st ->
                                    HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                                    DepartureRow(st, now) { onOpenTrip(st, group) }
                                }
                                HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                                Text(L10n.t("All departures"), fontWeight = FontWeight.Medium, fontSize = 15.sp,
                                    modifier = Modifier.fillMaxWidth().clickable { onOpenStop(group) }.padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Status(state: NearbyState, onShowAll: () -> Unit, onSettings: () -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state.error != null && !state.loading -> {
                Text(L10n.t("Departures unavailable"), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(state.error, color = ink.muted, textAlign = TextAlign.Center)
            }
            state.fix == null -> {
                CircularProgressIndicator(color = ink.ink, modifier = Modifier.height(32.dp))
                Text(L10n.t("Waiting for your location…"), color = ink.muted)
                if (state.locatingSeconds >= 15) {
                    Text("Das dauert ungewöhnlich lange. Gehe ans Fenster oder nach draußen und prüfe, dass der Standort auf „Genau“ steht.",
                        color = ink.muted, textAlign = TextAlign.Center, fontSize = 13.sp)
                    TextButton(onClick = onSettings) { Text(L10n.t("Settings")) }
                }
            }
            state.loading -> CircularProgressIndicator(color = ink.ink, modifier = Modifier.height(32.dp))
            state.filter.isActive -> {
                Text(L10n.t("No departures for these modes"), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                state.filter.summary?.let { Text(it, color = ink.muted) }
                TextButton(onClick = onShowAll) { Text(L10n.t("Show all modes")) }
            }
            else -> {
                Text(L10n.t("No departures nearby"), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(L10n.t("No departures within 5 km. The station map shows stops further away."), color = ink.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Message(title: String, text: String, button: String, onClick: () -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text(text, color = ink.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) { Text(button) }
    }
}

/** Trips hidden from the bar this session ("Hide"). */
private val hiddenBars = androidx.compose.runtime.mutableStateListOf<String>()

/**
 * The live bar over the tabs (LiveTripBar on iOS): the started trip, or a saved one that is running or
 * starts within 30 minutes; until 5 minutes after arrival ("Arrived").
 */
@Composable
fun LiveTripBar(store: io.github.elija7580.publit.data.TripStore, onOpen: (io.github.elija7580.publit.data.SavedTrip) -> Unit) {
    val ink = LocalInk.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val state by store.state.collectAsStateWithLifecycle()
    val now by androidx.compose.runtime.produceState(java.time.Instant.now()) { while (true) { kotlinx.coroutines.delay(15_000); value = java.time.Instant.now() } }
    val active = state.activeId?.let { id -> state.trips.firstOrNull { it.id == id } }?.takeIf { it.endTime.plusSeconds(300) >= now }
    val trip = active ?: state.trips.filter { t -> t.startTime.minusSeconds(1800) <= now && t.endTime.plusSeconds(300) >= now }.minByOrNull { it.startTime }
    if (trip == null || trip.id in hiddenBars) return
    val started = active?.id == trip.id
    val arrived = now >= trip.endTime
    val mins = ((if (now < trip.startTime) trip.startTime else trip.endTime).epochSecond - now.epochSecond + 59) / 60
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clickable { onOpen(trip) }, shape = RoundedCornerShape(16.dp), color = ink.ink, contentColor = ink.onInk, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.width(9.dp).height(9.dp).background(androidx.compose.ui.graphics.Color(0xFFFFC21A), androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (arrived) L10n.t("Arrived") else L10n.t("Live trip"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(trip.toName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (!arrived) Text(L10n.f("%lld min", mins), fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 8.dp))
            if (!started && !arrived) androidx.compose.material3.TextButton(onClick = { TripSession.start(ctx, store, trip); onOpen(trip) }) { Text(L10n.t("Start trip"), color = ink.onInk) }
            else androidx.compose.material3.TextButton(onClick = { onOpen(trip) }) { Text(L10n.t("Open trip"), color = ink.onInk) }
            if (!started) androidx.compose.material3.IconButton(onClick = { hiddenBars += trip.id }) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Clear, L10n.t("Hide"))
            }
        }
    }
}
