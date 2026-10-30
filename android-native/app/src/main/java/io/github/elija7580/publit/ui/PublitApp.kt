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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Flag
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
              openTrip: kotlinx.coroutines.flow.MutableStateFlow<String?>, crashReport: String?, goNow: PlanViewModel = plan) {
    PublitTheme {
        val ink = LocalInk.current
        // One back stack per tab, as the tabs on iOS: a tab keeps its own screens, tapping it again goes to its top.
        var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (prefs.getString(START_TAB_KEY, "nearby") == "plan") "plan" else "nearby") }
        val nearbyNav = androidx.navigation.compose.rememberNavController()
        val planNav = androidx.navigation.compose.rememberNavController()
        val ticketsNav = androidx.navigation.compose.rememberNavController()
        val savedNav = androidx.navigation.compose.rememberNavController()
        val nav = when (tab) { "plan" -> planNav; "tickets" -> ticketsNav; "saved" -> savedNav; else -> nearbyNav }
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: tab
        /** Plan with the search set up: the Plan tab at its top. */
        fun openPlan() { tab = "plan"; runCatching { planNav.popBackStack("plan", false) } }
        var savedLine by remember { mutableStateOf<io.github.elija7580.publit.data.SavedLine?>(null) }
        var pickFor by remember { mutableStateOf<Pair<String, (io.github.elija7580.publit.data.SavedPlace) -> Unit>?>(null) }
        var onboarding by remember { mutableStateOf(!prefs.getBoolean(ONBOARDING_DONE_KEY, false)) }
        val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.permissionResult() }
        val tripLink by openTrip.collectAsStateWithLifecycle()
        val linkContext = androidx.compose.ui.platform.LocalContext.current
        var sharedRoute by remember { mutableStateOf<io.github.elija7580.publit.data.SharedRoute?>(null) }
        LaunchedEffect(tripLink) {
            val link = tripLink ?: return@LaunchedEffect
            openTrip.value = null
            if (link.startsWith("route:")) {
                sharedRoute = io.github.elija7580.publit.data.SharedRoute.parse(link.removePrefix("route:"))
                if (sharedRoute != null) nav.navigate("sharedroute") { launchSingleTop = true }
            } else if (link.startsWith("place:")) {
                val parts = link.removePrefix("place:").split("|")
                if (parts.size == 3) {
                    plan.route(Endpoint.Here, Endpoint.At(parts[0], io.github.elija7580.publit.core.LatLon(parts[1].toDouble(), parts[2].toDouble())))
                    openPlan()
                } else android.widget.Toast.makeText(linkContext, L10n.t("No place found in what was shared."), android.widget.Toast.LENGTH_LONG).show()
            } else if (link.startsWith("go:")) {
                val role = if (link == "go:work") io.github.elija7580.publit.data.PlaceRole.WORK else io.github.elija7580.publit.data.PlaceRole.HOME
                val p = favorites.state.value.place(role)
                if (p == null) tab = "saved" else { plan.route(Endpoint.Here, Endpoint.At(role.title, p.coordinate)); openPlan() }
            } else if (link == "map:") {
                // publit://map: the station map (as the map button on Nearby), e.g. for the website screenshots.
                tab = "nearby"
                runCatching { nearbyNav.navigate("stationmap") { launchSingleTop = true } }
            } else if (link.startsWith("stop:")) {
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
                if (!route.startsWith("savedtrip")) LiveTripBar(trips, client) { t -> nav.navigate("savedtrip?id=${t.id}") { launchSingleTop = true } }
                androidx.compose.material3.NavigationBar(containerColor = ink.surface, tonalElevation = 0.dp) {
                    tabs.forEach { (r, title, icon) ->
                        val selected = tab == r
                        NavigationBarItem(
                            selected = selected,
                            onClick = { if (selected) runCatching { nav.popBackStack(r, false) } else tab = r },
                            icon = { androidx.compose.material3.Icon(icon, null) }, label = { Text(title) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = ink.ink, selectedTextColor = ink.ink, indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unselectedIconColor = ink.muted, unselectedTextColor = ink.muted),
                        )
                    }
                }
            } },
        ) { padding ->
            // Back on a tab's top screen goes to Nearby, as leaving a tab on iOS.
            androidx.activity.compose.BackHandler(enabled = tab != "nearby" && entry?.destination?.route == tab) { tab = "nearby" }
            androidx.compose.runtime.key(tab) {
            androidx.navigation.compose.NavHost(nav, startDestination = tab, modifier = Modifier.padding(padding)) {
                composable("nearby") {
                    NearbyScreen(vm, onOpenMap = { nav.navigate("stationmap") }, onSettings = { nav.navigate("settings") },
                        pinned = favorites.state.collectAsStateWithLifecycle().value.let { st -> io.github.elija7580.publit.data.PlaceRole.entries.mapNotNull { st.place(it) } },
                        onWhereTo = { goNow.route(Endpoint.Here, null); nav.navigate("gonow") { launchSingleTop = true } },
                        nextTrip = trips.state.collectAsStateWithLifecycle().value.let { st -> st.trips.filter { t -> t.id != st.activeId && t.startTime > java.time.Instant.now() }.minByOrNull { it.startTime } },
                        onOpenSaved = { id -> nav.navigate("savedtrip?id=$id") },
                        onRoute = { name, at -> goNow.route(Endpoint.Here, Endpoint.At(name, at)); nav.navigate("gonow") { launchSingleTop = true } }, onOpenTrip = { st, g -> openTrip(TripTarget(st, g.name, g.id, g.coordinate)) }) { g -> nav.navigate("stop?id=${Uri.encode(g.id)}&name=${Uri.encode(g.name)}&exact=${g.isPole}") }
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
                        openPlan()
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
                        openPlan()
                    }
                }
                composable("plan") {
                    PlanScreen(plan, onSettings = { nav.navigate("settings") }, saved = favorites.state.collectAsStateWithLifecycle().value.items, onSetRole = { role, p -> favorites.set(p, role) }) { it, from, to -> TripActions(trips, it, from, to) { t -> nav.navigate("savedtrip?id=${t.id}") } }
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
                        onRoute = { p -> plan.route(Endpoint.Here, Endpoint.At(p.name, p.coordinate)); openPlan() },
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
                composable("gonow") {
                    GoNowScreen(goNow, onBack = { goNow.open(null); nav.popBackStack() }, saved = favorites.state.collectAsStateWithLifecycle().value.items,
                        onSetRole = { role, p -> favorites.set(p, role) }) { it, from, to -> TripActions(trips, it, from, to) { t -> nav.navigate("savedtrip?id=${t.id}") } }
                }
                composable("sharedroute") {
                    val r = sharedRoute
                    if (r == null) LaunchedEffect(Unit) { nav.popBackStack() } else
                    SharedRouteScreen(client, trips, r, onBack = { nav.popBackStack() },
                        onSearch = { plan.route(Endpoint.At(r.fromName, r.from), Endpoint.At(r.toName, r.to)); openPlan() },
                        onStarted = { t -> nav.navigate("savedtrip?id=${t.id}") })
                }
                composable("settings") { SettingsScreen(prefs) { nav.popBackStack() } }
            }
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

/**
 * The bar above the tabs while a saved trip is on (LiveTripBar on iOS): 30 min before leaving until
 * 5 min after arrival, or the trip started by hand. ▶ starts the trip; a started trip is a dark live bar
 * with a ring for the trip so far, what to do now and a big countdown. Tap or swipe up opens the trip.
 */
@Composable
fun LiveTripBar(store: io.github.elija7580.publit.data.TripStore, client: io.github.elija7580.publit.data.TransitousClient? = null,
                onOpen: (io.github.elija7580.publit.data.SavedTrip) -> Unit) {
    val ink = LocalInk.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("publit", android.content.Context.MODE_PRIVATE) }
    var hiddenId by remember { mutableStateOf(prefs.getString("liveBarHiddenTrip", "") ?: "") }
    val state by store.state.collectAsStateWithLifecycle()
    val now by androidx.compose.runtime.produceState(java.time.Instant.now()) { while (true) { kotlinx.coroutines.delay(15_000); value = java.time.Instant.now() } }
    val active = state.activeId?.let { id -> state.trips.firstOrNull { it.id == id } }
        ?.takeIf { it.endTime.plusSeconds(300) >= now && it.startTime.minusSeconds(3 * 3600) <= now }
    val trip = (active ?: state.trips.filter { t -> t.startTime.minusSeconds(1800) <= now && t.endTime.plusSeconds(300) >= now }.minByOrNull { it.startTime })
        ?.takeIf { it.id != hiddenId } ?: return
    // Live times every 90 s while the app is open.
    if (client != null) LaunchedEffect(trip.id) {
        while (true) {
            val t = store.trip(trip.id) ?: break
            if (t.endTime <= java.time.Instant.now()) break
            runCatching { TripSession.refresh(ctx, client, store, t) }
            kotlinx.coroutines.delay(90_000)
        }
    }
    val it = trip.itinerary
    val started = active?.id == trip.id
    val status = io.github.elija7580.publit.data.TripStatus.at(now, it, countdown = !started)
    val leg = it.transitLegs.firstOrNull { l -> l.endTime > now }
    val total = maxOf(it.endTime.epochSecond - it.startTime.epochSecond, 60).toFloat()
    val progress = ((now.epochSecond - it.startTime.epochSecond) / total).coerceIn(0f, 1f)
    val cancelled = status.phase == io.github.elija7580.publit.data.TripStatus.Phase.CANCELLED
    val arrived = status.phase == io.github.elija7580.publit.data.TripStatus.Phase.ARRIVED
    val tint = if (cancelled) ink.danger else if (status.delayMinutes > 0) ink.delay else ink.ink
    var pull by remember { mutableStateOf(0f) }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp)
            .offset { androidx.compose.ui.unit.IntOffset(0, pull.toInt()) }
            .pointerInput(trip.id) {
                detectVerticalDragGestures(onDragEnd = { if (pull < -30) onOpen(trip); pull = 0f }, onDragCancel = { pull = 0f }) { _, d -> pull = (pull + d * 0.4f).coerceIn(-48f, 0f) }
            }
            .clickable { onOpen(trip) },
        shape = RoundedCornerShape(if (started) 24.dp else 22.dp), color = if (started) ink.ink else ink.surface,
        border = if (started) null else androidx.compose.foundation.BorderStroke(1.dp, if (cancelled || status.delayMinutes > 0) tint.copy(alpha = 0.35f) else ink.hairline),
        shadowElevation = 6.dp,
    ) {
        Box {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 3.dp).width(24.dp).height(3.dp)
                .background(if (started) ink.onInk.copy(alpha = 0.35f) else ink.ink.copy(alpha = 0.2f), RoundedCornerShape(50)))
            Row(Modifier.padding(horizontal = 12.dp, vertical = if (started) 10.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (started) {
                    // Like a running workout: a ring for the trip so far, what to do now, a big countdown.
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val st = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            drawArc(ink.onInk.copy(alpha = 0.18f), 0f, 360f, false, style = st)
                            drawArc(androidx.compose.ui.graphics.Color(0xFFFFC21A), -90f, 360f * maxOf(progress, 0.02f), false, style = st)
                        }
                        androidx.compose.material3.Icon(when {
                            arrived -> androidx.compose.material.icons.Icons.Filled.Flag
                            cancelled -> androidx.compose.material.icons.Icons.Filled.Warning
                            leg != null && leg.startTime <= now -> modeIcon(leg.mode)
                            else -> androidx.compose.material.icons.Icons.AutoMirrored.Filled.DirectionsWalk
                        }, null, Modifier.size(18.dp), tint = ink.onInk)
                    }
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(if (cancelled) ink.danger else ink.live, androidx.compose.foundation.shape.CircleShape))
                            Spacer(Modifier.width(5.dp))
                            Text(L10n.t("Live trip").uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ink.onInk.copy(alpha = 0.7f))
                            if (leg != null && leg.startTime <= now.plusSeconds(1800)) { Spacer(Modifier.width(6.dp)); LineBadge(leg) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(status.headline, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ink.onInk, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (status.delayMinutes > 0) Text(" +${status.delayMinutes}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ink.delay)
                        }
                        Text(status.detail.ifEmpty { trip.toName }, fontSize = 12.sp, color = ink.onInk.copy(alpha = 0.7f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    val mins = ((status.nextEvent.epochSecond - now.epochSecond + 59) / 60)
                    if (!arrived && !cancelled && mins >= 0) {
                        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp)) {
                            if (mins < 60) {
                                Text("$mins", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink.onInk)
                                Text("min", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = ink.onInk.copy(alpha = 0.7f))
                            } else Text(io.github.elija7580.publit.core.Fmt.time(status.nextEvent, it.legs.firstOrNull()?.from?.tz), fontWeight = FontWeight.Bold, color = ink.onInk)
                        }
                    }
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp, L10n.t("Open trip"), tint = ink.onInk.copy(alpha = 0.5f))
                } else {
                    if (leg != null) LineBadge(leg) else androidx.compose.material3.Icon(
                        if (arrived) androidx.compose.material.icons.Icons.Filled.Flag else androidx.compose.material.icons.Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.width(28.dp))
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(status.headline, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (status.delayMinutes > 0) Text(" +${status.delayMinutes}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ink.delay)
                        }
                        Text(status.detail.ifEmpty { trip.toName }, fontSize = 12.sp, color = ink.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        androidx.compose.material3.LinearProgressIndicator(progress = { progress }, color = tint, trackColor = ink.fill,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp))
                    }
                    if (!arrived && !cancelled) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(36.dp).background(androidx.compose.ui.graphics.Color(0xFFFFC21A), androidx.compose.foundation.shape.CircleShape)
                            .clickable { TripSession.start(ctx, store, trip); onOpen(trip) }, contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.PlayArrow, L10n.t("Start trip"), tint = androidx.compose.ui.graphics.Color.Black)
                        }
                    }
                    androidx.compose.material3.IconButton(onClick = { hiddenId = trip.id; prefs.edit().putString("liveBarHiddenTrip", trip.id).apply() }, modifier = Modifier.size(32.dp)) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Clear, L10n.t("Hide"), tint = ink.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** A route someone shared (SharedRouteView on iOS): fetched again with its live state. */
@Composable
fun SharedRouteScreen(client: io.github.elija7580.publit.data.TransitousClient, trips: io.github.elija7580.publit.data.TripStore,
                      route: io.github.elija7580.publit.data.SharedRoute, onBack: () -> Unit, onSearch: () -> Unit,
                      onStarted: (io.github.elija7580.publit.data.SavedTrip) -> Unit) {
    val ink = LocalInk.current
    var it by remember { mutableStateOf<io.github.elija7580.publit.data.Itinerary?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(route) {
        failed = false
        it = route.itineraryId?.let { id -> runCatching { client.refresh(id) }.getOrNull() }
        if (it == null) failed = true
    }
    val loaded = it
    when {
        loaded != null -> ItineraryDetail(loaded, onBack, title = route.toName) {
            TripActions(trips, loaded, route.fromName, route.toName, onStarted)
        }
        else -> androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().statusBarsPadding()) {
            androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                if (!failed) androidx.compose.material3.CircularProgressIndicator(color = ink.ink) else {
                    Text(L10n.t("This route could not be loaded."), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(route.fromName + " → " + route.toName, color = ink.muted)
                    Text(L10n.t("It may be too old or no longer in the timetable. You can search for it again."), color = ink.muted)
                    androidx.compose.material3.Button(onClick = onSearch, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) {
                        Text(L10n.t("Search this route"))
                    }
                }
            }
        }
    }
}
