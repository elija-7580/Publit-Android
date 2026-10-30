package io.github.elija7580.publit.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.*

/**
 * The live trip (LiveTripView on iOS): the map is the screen; the stages and their details sit in a
 * panel pulled up over it. The strip and the pager pick a stage; "Trip overview" shows the whole trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTripScreen(client: TransitousClient, store: TripStore, trip: SavedTrip, error: String?, onBack: () -> Unit,
                   onDetails: (() -> Unit)? = null, tickets: (@Composable () -> Unit)? = null) {
    val ink = LocalInk.current
    val ctx = LocalContext.current
    val app = ctx.applicationContext as io.github.elija7580.publit.PublitApplication
    val prefs = remember { ctx.getSharedPreferences("publit", android.content.Context.MODE_PRIVATE) }
    var voice by remember { mutableStateOf(prefs.getBoolean("guideVoice", false)) }
    var mapBase by remember { mutableStateOf(prefs.getString("mapBase", "muted") ?: "muted") }
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(if (ctx.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true) 30_000 else 15_000) } }
    var fix by remember { mutableStateOf<io.github.elija7580.publit.location.Fix?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (app.location.access() != io.github.elija7580.publit.location.LocationAccess.NOT_ASKED) app.location.fixes.collect { fix = it }
        }
    }
    DisposableEffect(Unit) { onDispose { io.github.elija7580.publit.notify.GuideVoice.stop() } }

    val it = trip.itinerary
    // A recent, precise fix only: an old one would put you at the wrong stop.
    val here = fix?.takeIf { f -> (f.accuracy ?: 0f) < 200f }?.coordinate
    val steps = remember(it) { TripGuide.steps(it) }
    val live = TripGuide.current(steps, it, now, here)
    var picked by remember { mutableStateOf<Int?>(null) }
    var overview by remember { mutableStateOf(false) }
    LaunchedEffect(live) { picked?.let { p -> if (p <= live) picked = null } }
    val index = picked ?: live
    val step = steps[index]
    val leg = it.legs[step.legIndex]
    val ride = if (step.kind == GuideStep.Kind.RIDE || step.kind == GuideStep.Kind.WAIT) TripProgress.at(now, leg.allStops) else null
    val check = if (index == live) TripGuide.check(step, it, now, here, fix?.accuracy?.toDouble() ?: 50.0) else TripGuide.Check.FINE
    val risk = if (index == live && SmartRouting.isEnabled) SmartRouting.changeAtRisk(it, now) else null
    val cancelled = it.isCancelled && it.endTime > now
    val wrongMessage = when {
        check == TripGuide.Check.OFF_ROUTE -> L10n.t("You seem to be off the route.")
        check == TripGuide.Check.MISSED -> L10n.t("Missed it?")
        cancelled -> L10n.t("Connection cancelled")
        risk != null -> L10n.f("Your change at %@ may not work (%lld %%).", risk.stopName, risk.percent) +
            (SmartRouting.backup(risk.outgoing)?.let { b -> " " + L10n.f("Backup: %@ at %@", b.leg.lineName, Fmt.time(b.leg.startTime, b.leg.from.tz)) } ?: "")
        else -> null
    }
    // Each cue once: a short vibration, and the words when spoken directions are on.
    val announced = remember { HashSet<String>() }
    val cue = if (index == live) TripGuide.cue(step, it, now, ride) else null
    LaunchedEffect(cue?.key) {
        val c = cue ?: return@LaunchedEffect
        if (!announced.add(c.key)) return@LaunchedEffect
        if (announced.size > 1) runCatching {
            val v = ctx.getSystemService(android.os.Vibrator::class.java)
            v?.vibrate(android.os.VibrationEffect.createOneShot(if (c.kind == GuideCue.Kind.GET_OFF) 400 else 150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
        if (voice) io.github.elija7580.publit.notify.GuideVoice.say(ctx, c.text)
    }
    // At the destination the trip ends by itself.
    val finished = steps[live].kind == GuideStep.Kind.ARRIVE && TripGuide.isFinished(it, now, here)
    LaunchedEffect(finished) { if (finished) TripSession.stop(ctx, store, trip) }

    val vehicle = if (step.kind == GuideStep.Kind.RIDE && ride != null) TripProgress.position(ride, leg.allStops) else null
    val shown: Int? = if (overview) null else index
    fun show(i: Int?) { if (i == null) overview = true else { overview = false; picked = if (i == live) null else i } }

    val sheet = rememberBottomSheetScaffoldState()
    var menu by remember { mutableStateOf(false) }
    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = 236.dp,
        sheetContainerColor = ink.ground,
        sheetShadowElevation = 8.dp,
        sheetContent = {
            Column(Modifier.fillMaxHeight(0.88f)) {
                StageStrip(steps, it, trip.toName, live, shown) { i -> show(i) }
                Spacer(Modifier.height(8.dp))
                StagePager(steps, it, trip.toName, live, now, shown) { i -> show(i) }
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                    if (wrongMessage != null) item("wrong") {
                        AlternativesCard(client, store, trip, wrongMessage, risk?.incoming?.to?.coordinate ?: here)
                    }
                    if (picked != null || overview) item("back") {
                        TextButton(onClick = { picked = null; overview = false }, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(L10n.t("Back to now"), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    val upTo = if (overview) steps.lastIndex else index
                    item("overview") {
                        Section(L10n.t("Trip overview")) {
                            for (i in 0..upTo) StageRow(steps[i], it, trip.toName, i + 1, steps.size, i == live, i < live, !overview && i == index) { show(i) }
                        }
                    }
                    if (!overview) item("stage") {
                        Section(null) {
                            when (step.kind) {
                                GuideStep.Kind.WALK -> WalkStage(it, step.legIndex, trip.toName, now, here, index == live)
                                GuideStep.Kind.WAIT -> WaitStage(it, step.legIndex, now)
                                GuideStep.Kind.RIDE -> RideStage(it, step.legIndex, now, ride ?: TripProgress.NotStarted)
                                GuideStep.Kind.ARRIVE -> ArriveStage(it, trip.toName, now, here) { TripSession.stop(ctx, store, trip) }
                            }
                            if (step.kind == GuideStep.Kind.WAIT || step.kind == GuideStep.Kind.RIDE) tickets?.invoke()
                        }
                    }
                    if (!overview && step.kind == GuideStep.Kind.RIDE) item("stops") {
                        Section(L10n.t("Stops")) {
                            val stops = leg.allStops
                            val p = ride ?: TripProgress.NotStarted
                            stops.forEachIndexed { i, s -> GuideStopRow(s, stopState(i, p, stops.size), i == 0, i == stops.lastIndex, lineStroke(leg, ink.dark)) }
                        }
                    }
                    if (upTo + 1 < steps.size) item("ahead") {
                        Section(null) { for (i in upTo + 1 until steps.size) StageRow(steps[i], it, trip.toName, i + 1, steps.size, false, false, false) { show(i) } }
                    }
                    if (onDetails != null) item("details") {
                        Section(null) {
                            Row(Modifier.fillMaxWidth().clickable(onClick = onDetails).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.List, null); Spacer(Modifier.width(10.dp)); Text(L10n.t("Route details"))
                            }
                        }
                        trip.refreshedAt?.let { r -> Text(L10n.f("Updated %@", Fmt.time(r)), color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)) }
                        error?.let { e -> Text(e, color = ink.delay, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 28.dp)) }
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize()) {
            GuideMap(it, steps, live, shown, vehicle, if (index == live) here else null, mapBase, Modifier.fillMaxSize().padding(bottom = inner.calculateBottomPadding()))
            Row(Modifier.statusBarsPadding().padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundKey(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Minimize"), onBack)
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = ink.surface, shadowElevation = 3.dp, modifier = Modifier.weight(1f, fill = false)) {
                    Text(trip.toName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                RoundKey(if (overview || picked != null) Icons.Filled.MyLocation else Icons.Filled.Route,
                    L10n.t(if (overview || picked != null) "Back to now" else "Trip overview")) {
                    if (overview || picked != null) { picked = null; overview = false } else overview = true
                }
                Spacer(Modifier.width(8.dp))
                Box {
                    RoundKey(Icons.Filled.MoreHoriz, L10n.t("More")) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(L10n.t(if (voice) "Turn off spoken directions" else "Turn on spoken directions")) },
                            leadingIcon = { Icon(if (voice) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, null) },
                            onClick = {
                                menu = false; voice = !voice; prefs.edit().putBoolean("guideVoice", voice).apply()
                                if (!voice) io.github.elija7580.publit.notify.GuideVoice.stop() else cue?.let { c -> io.github.elija7580.publit.notify.GuideVoice.say(ctx, c.text) }
                            })
                        listOf("muted" to "Transit", "standard" to "Standard", "satellite" to "Satellite").forEach { (k, t) ->
                            DropdownMenuItem(text = { Text(L10n.t(t)) }, leadingIcon = { Icon(Icons.Filled.Map, null) },
                                trailingIcon = { if (mapBase == k) Icon(Icons.Filled.Check, null) },
                                onClick = { mapBase = k; prefs.edit().putString("mapBase", k).apply(); menu = false })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(L10n.t("End trip"), color = ink.danger) }, leadingIcon = { Icon(Icons.Filled.StopCircle, null, tint = ink.danger) },
                            onClick = { menu = false; TripSession.stop(ctx, store, trip) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val ink = LocalInk.current
    Surface(onClick = onClick, shape = CircleShape, color = ink.surface, shadowElevation = 3.dp, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label) }
    }
}

@Composable
private fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.padding(horizontal = 12.dp)) {
        title?.let { Text(it.uppercase(), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)) }
        Surface(shape = RoundedCornerShape(14.dp), color = ink.surface, modifier = Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

/** What the stage is about, with a place (StageStrip.label on iOS). */
private fun stageLabel(step: GuideStep, it: Itinerary, destination: String): String {
    val leg = it.legs[step.legIndex]
    return when (step.kind) {
        GuideStep.Kind.WALK -> L10n.f("Walk to %@", walkTarget(it, step.legIndex, destination))
        GuideStep.Kind.WAIT -> leg.shownDirection?.let { d -> L10n.f("Take %@ towards %@", leg.lineName, d) } ?: L10n.f("Take %@", leg.lineName)
        GuideStep.Kind.RIDE -> L10n.f("Get off at %@", leg.to.name)
        GuideStep.Kind.ARRIVE -> L10n.f("Arrive at %@", destination)
    }
}

private fun walkTarget(it: Itinerary, legIndex: Int, destination: String): String =
    it.legs.drop(legIndex + 1).firstOrNull { l -> l.isTransit }?.from?.name
        ?: if (legIndex == it.legs.lastIndex) destination else it.legs[legIndex].to.name

private fun stageIcon(step: GuideStep) = when (step.kind) {
    GuideStep.Kind.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    GuideStep.Kind.WAIT -> Icons.Filled.Schedule
    GuideStep.Kind.RIDE -> Icons.Filled.DirectionsTransit
    GuideStep.Kind.ARRIVE -> Icons.Filled.Flag
}

private fun span(step: GuideStep, leg: Leg) = if (step.kind == GuideStep.Kind.ARRIVE) Fmt.time(leg.endTime, leg.to.tz)
    else Fmt.time(leg.startTime, leg.from.tz) + " – " + Fmt.time(leg.endTime, leg.to.tz)

/** The yellow dot that marks "now". */
@Composable
private fun SignatureDot(size: Int) = Box(Modifier.size(size.dp).background(Color(0xFFFFC21A), CircleShape))

/** The stages as a row of chips: done, now (yellow dot), ahead. */
@Composable
private fun StageStrip(steps: List<GuideStep>, it: Itinerary, destination: String, live: Int, shown: Int?, onTap: (Int) -> Unit) {
    val ink = LocalInk.current
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp).height(38.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, step ->
            if (i > 0) Icon(Icons.Filled.ChevronRight, null, tint = ink.hairline, modifier = Modifier.size(14.dp))
            val leg = it.legs[step.legIndex]
            val selected = i == shown
            Row(Modifier.clip(RoundedCornerShape(50)).background(if (selected) ink.fill else Color.Transparent)
                .border(1.dp, if (selected) ink.ink.copy(alpha = 0.5f) else ink.hairline, RoundedCornerShape(50))
                .clickable { onTap(i) }.padding(horizontal = 8.dp, vertical = 6.dp)
                .then(if (i < live) Modifier.alpha(0.5f) else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (step.kind) {
                    GuideStep.Kind.WALK -> { Icon(stageIcon(step), stageLabel(step, it, destination), Modifier.size(16.dp)); Text("${max(1, leg.duration / 60)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    GuideStep.Kind.WAIT -> { Icon(stageIcon(step), stageLabel(step, it, destination), Modifier.size(16.dp)); Text(Fmt.time(leg.startTime, leg.from.tz), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    GuideStep.Kind.RIDE -> LineBadge(leg)
                    GuideStep.Kind.ARRIVE -> Icon(stageIcon(step), stageLabel(step, it, destination), Modifier.size(16.dp))
                }
                if (i == live) SignatureDot(7)
            }
        }
    }
    LaunchedEffect(shown) { shown?.let { s -> scroll.animateScrollTo((scroll.maxValue * s / max(1, steps.lastIndex))) } }
}

/** Big cards to swipe through: the whole trip first, then one per stage. */
@Composable
private fun StagePager(steps: List<GuideStep>, it: Itinerary, destination: String, live: Int, now: Instant, shown: Int?, onShow: (Int?) -> Unit) {
    val ink = LocalInk.current
    val pager = rememberPagerState(initialPage = (shown ?: -1) + 1) { steps.size + 1 }
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        val wanted = (shown ?: -1) + 1
        if (pager.currentPage != wanted) { auto = true; pager.animateScrollToPage(wanted); auto = false }
    }
    LaunchedEffect(pager.settledPage) {
        if (auto) return@LaunchedEffect
        val p = pager.settledPage - 1
        val picked = if (p < 0) null else p
        if (picked != shown) onShow(picked)
    }
    HorizontalPager(pager, Modifier.fillMaxWidth().height(96.dp), contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 8.dp) { page ->
        val selected = (page - 1) == (shown ?: -1)
        Row(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(ink.surface)
            .border(1.dp, if (selected) ink.ink.copy(alpha = 0.35f) else ink.hairline, RoundedCornerShape(22.dp))
            .clickable { scope.launch { pager.animateScrollToPage(page) } }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .then(if (page - 1 in 0 until live) Modifier.alpha(0.55f) else Modifier),
            verticalAlignment = Alignment.CenterVertically) {
            if (page == 0) {
                Icon(Icons.Filled.Route, null, Modifier.size(30.dp)); Spacer(Modifier.width(12.dp))
                Column {
                    Text(L10n.t("Trip overview"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
                    val f = it.legs.first(); val l = it.legs.last()
                    Text(Fmt.time(f.startTime, f.from.tz) + " – " + Fmt.time(l.endTime, l.to.tz) + " · " + Fmt.duration(it.duration),
                        color = ink.muted, fontSize = 14.sp, maxLines = 1)
                }
            } else {
                val i = page - 1
                val step = steps[i]
                val leg = it.legs[step.legIndex]
                val isNow = i == live
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text((if (isNow) L10n.t("now").uppercase() + " · " else "") + "${i + 1}/${steps.size}", color = ink.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                        if (step.kind == GuideStep.Kind.RIDE || step.kind == GuideStep.Kind.WAIT) LineBadge(leg) else Icon(stageIcon(step), null, tint = ink.muted, modifier = Modifier.size(14.dp))
                        if (isNow) SignatureDot(7)
                    }
                    Text(stageLabel(step, it, destination), fontWeight = if (isNow) FontWeight.Black else FontWeight.SemiBold, fontSize = if (isNow) 19.sp else 16.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                    Text(span(step, leg), color = ink.muted, fontSize = 13.sp, maxLines = 1)
                }
                if (isNow && step.kind != GuideStep.Kind.ARRIVE) {
                    val until = if (step.kind == GuideStep.Kind.WAIT) leg.startTime else leg.endTime
                    val left = max(0L, ceil((until.epochSecond - now.epochSecond) / 60.0).toLong())
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$left", fontSize = 34.sp, fontWeight = FontWeight.Black)
                        Text("min", color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** One stage in the pulled-up list. */
@Composable
private fun StageRow(step: GuideStep, it: Itinerary, destination: String, number: Int, count: Int, live: Boolean, done: Boolean, selected: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    val leg = it.legs[step.legIndex]
    Row(Modifier.fillMaxWidth().background(if (selected) ink.fill else Color.Transparent).clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp).then(if (done) Modifier.alpha(0.5f) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.widthIn(min = 32.dp)) { if (step.kind == GuideStep.Kind.RIDE) LineBadge(leg) else Icon(stageIcon(step), null) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stageLabel(step, it, destination), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2)
            Text(span(step, leg) + if (step.kind != GuideStep.Kind.ARRIVE) " · " + Fmt.duration(leg.duration) else "", color = ink.muted, fontSize = 12.sp)
        }
        if (live) { SignatureDot(9); Spacer(Modifier.width(8.dp)) }
        Text("$number/$count", color = ink.muted, fontSize = 11.sp)
    }
}

private fun departure(leg: Leg, now: Instant): String {
    val secs = leg.startTime.epochSecond - now.epochSecond
    return when {
        secs > 60 -> L10n.f("Departs in %lld min", ceil(secs / 60.0).toLong())
        secs > -60 -> L10n.t("Departs now")
        else -> L10n.f("Departed %@", Fmt.time(leg.startTime, leg.from.tz))
    }
}

/** The planned platform differs from the live one. */
private fun platformChange(p: Place): Pair<String, String>? {
    val now = TransitLabels.clean(p.track) ?: return null
    val was = TransitLabels.clean(p.scheduledTrack) ?: return null
    return if (now != was) now to was else null
}

@Composable
private fun PlatformLine(p: Place, big: Boolean) {
    val ink = LocalInk.current
    val change = platformChange(p)
    val track = change?.first ?: TransitLabels.clean(p.track) ?: return
    Text(L10n.f("Platform %@", track), fontWeight = FontWeight.Bold, fontSize = if (big) 20.sp else 14.sp,
        color = if (change != null) ink.delay else ink.ink,
        modifier = Modifier.background(if (change != null) ink.delay.copy(alpha = 0.15f) else ink.fill, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
    change?.let { c -> Text(L10n.f("Platform changed (was %@)", c.second), color = ink.delay, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** A compass row: which way to go, north up (HeadingRow without a compass on iOS). */
@Composable
private fun HeadingRow(bearing: Double, title: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Navigation, null, Modifier.size(30.dp).rotate(bearing.toFloat()))
        Spacer(Modifier.width(12.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WalkStage(it: Itinerary, legIndex: Int, destination: String, now: Instant, here: LatLon?, isLive: Boolean) {
    val ink = LocalInk.current
    val ctx = LocalContext.current
    val leg = it.legs[legIndex]
    val target = walkTarget(it, legIndex, destination)
    val next = it.legs.drop(legIndex + 1).firstOrNull { l -> l.isTransit }
    val route = remember(leg) { WalkRoute(leg) }
    val pos = route.livePosition(here, isLive)
    val pace = if ((leg.distance ?: 0.0) > 0) leg.duration / leg.distance!! else 1 / 1.3
    // Away from the planned path: straight-line distance (no Apple Maps detour on Android).
    val away = isLive && here != null && pos == null
    val remaining = pos?.remaining ?: if (away) here!!.distanceTo(leg.to.coordinate) * 1.3 else leg.distance ?: route.length * route.scale
    val walkSeconds = (remaining * pace).toInt()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(28.dp)); Spacer(Modifier.width(10.dp))
            Text(L10n.f("Walk to %@", target), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Text(Fmt.distance(remaining) + " · " + Fmt.duration(max(60, walkSeconds)), color = ink.muted, fontWeight = FontWeight.SemiBold)
        if (now < leg.startTime) Text(TripStatus.at(now, it).headline, color = ink.live, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        next?.let { n ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LineBadge(n); Text(departure(n, now), fontSize = 15.sp)
                TransitLabels.clean(n.from.track)?.let { t -> Text("· " + L10n.f("Platform %@", t), fontSize = 15.sp, color = if (platformChange(n.from) != null) ink.delay else ink.muted, fontWeight = if (platformChange(n.from) != null) FontWeight.Bold else FontWeight.Normal) }
            }
            platformChange(n.from)?.let { c -> Text(L10n.f("Platform changed (was %@)", c.second), color = ink.delay, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            val spare = n.startTime.epochSecond - now.epochSecond - walkSeconds
            if (spare < 60 && n.startTime > now) Text(L10n.t("Walk faster to make it."), color = ink.delay, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
    val bearing = if (away) PathGeometry.bearing(here!!, leg.to.coordinate) else route.bearing(pos?.along ?: 0.0)
    bearing?.let { b ->
        HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(start = 16.dp))
        HeadingRow(b, Compass.head(Compass.point(b)))
    }
    if (pos != null) route.maneuvers.getOrNull(pos.index + 1)?.let { m ->
        HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(start = 16.dp))
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(maneuverIcon(m.kind), null, Modifier.size(32.dp)); Spacer(Modifier.width(12.dp))
            Column {
                Text(L10n.f("In %@", Fmt.distance(pos.toNext ?: 0.0)), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(m.instruction(target), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                m.exitNote?.let { e -> Text(e, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            }
        }
    }
    HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(start = 16.dp))
    Row(Modifier.fillMaxWidth().clickable { io.github.elija7580.publit.DirectionsApp.open(ctx, leg.to.lat, leg.to.lon, target) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Map, null); Spacer(Modifier.width(10.dp)); Text(L10n.t("Open in Maps"))
    }
    if (!away && route.maneuvers.size > 2) {
        HorizontalDivider(color = ink.hairline)
        Text(L10n.t("Directions").uppercase(), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
        route.maneuvers.forEachIndexed { i, m ->
            val isNow = pos?.index == i
            val isPast = (pos?.index ?: -1) > i
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(maneuverIcon(m.kind), null, Modifier.size(24.dp), tint = if (isPast) ink.muted else ink.ink); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.instruction(target), fontSize = 15.sp, fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal, color = if (isPast) ink.muted else ink.ink)
                    m.exitNote?.let { e -> Text(e, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    if (m.kind != WalkManeuver.Kind.ARRIVE && m.distance >= 1) Text(Fmt.distance(m.distance), fontSize = 12.sp, color = ink.muted)
                }
                if (isNow) SignatureDot(10)
            }
        }
    }
}

private fun maneuverIcon(k: WalkManeuver.Kind) = when (k) {
    WalkManeuver.Kind.START -> Icons.AutoMirrored.Filled.DirectionsWalk
    WalkManeuver.Kind.STRAIGHT -> Icons.Filled.Straight
    WalkManeuver.Kind.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
    WalkManeuver.Kind.LEFT, WalkManeuver.Kind.SHARP_LEFT -> Icons.Filled.TurnLeft
    WalkManeuver.Kind.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
    WalkManeuver.Kind.RIGHT, WalkManeuver.Kind.SHARP_RIGHT -> Icons.Filled.TurnRight
    WalkManeuver.Kind.U_TURN -> Icons.Filled.UTurnLeft
    WalkManeuver.Kind.STAIRS_UP, WalkManeuver.Kind.STAIRS_DOWN, WalkManeuver.Kind.STAIRS -> Icons.Filled.Stairs
    WalkManeuver.Kind.ELEVATOR -> Icons.Filled.Elevator
    WalkManeuver.Kind.LEVEL_UP -> Icons.Filled.NorthEast
    WalkManeuver.Kind.LEVEL_DOWN -> Icons.Filled.SouthEast
    WalkManeuver.Kind.ARRIVE -> Icons.Filled.Place
}

@Composable
private fun WaitStage(it: Itinerary, legIndex: Int, now: Instant) {
    val ink = LocalInk.current
    val leg = it.legs[legIndex]
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineBadge(leg); leg.shownDirection?.let { d -> Text(L10n.f("Towards %@", d), fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
        Text(departure(leg, now), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.time(leg.startTime, leg.from.tz), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            if (leg.delayMinutes > 0) Text(L10n.f("+%lld min", leg.delayMinutes), color = ink.delay, fontWeight = FontWeight.SemiBold)
            else if (leg.realTime) Text(L10n.t("On time"), color = ink.live, fontWeight = FontWeight.SemiBold)
        }
        Text(leg.from.name, fontSize = 15.sp)
        PlatformLine(leg.from, big = true)
        if ((leg.mode == "BUS" || leg.mode == "COACH") && (leg.routeShortName ?: "").uppercase().let { n -> n.startsWith("SEV") || n.startsWith("EV") || n.contains("ERSATZ") }) Text(L10n.t("Rail replacement bus"), color = ink.delay, fontWeight = FontWeight.SemiBold)
        if (leg.cancelled == true) Text(L10n.t("This trip is cancelled."), color = ink.danger, fontWeight = FontWeight.SemiBold)
        SmartRouting.changes(it).firstOrNull { c -> c.outgoingIndex == legIndex }?.let { c -> ChangeLine(c) }
        leg.alerts.orEmpty().take(2).forEach { a -> Text(a.headerText, color = ink.delay, fontSize = 13.sp) }
        Text(listOf(L10n.f("%lld stops", max(leg.allStops.size - 1, 1)), Fmt.duration(leg.duration), L10n.f("Get off at %@", leg.to.name)).joinToString(" · "), color = ink.muted, fontSize = 14.sp)
    }
}

@Composable
private fun ChangeLine(c: SmartRouting.Change) {
    val ink = LocalInk.current
    val tight = c.spareMinutes < 1
    Text(if (tight) L10n.t("Very tight change") else L10n.f("Change at %@: %lld %% chance", c.stopName, c.percent),
        color = if (c.percent < 50) ink.danger else if (c.percent < 80) ink.delay else ink.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
private fun RideStage(it: Itinerary, legIndex: Int, now: Instant, progress: TripProgress) {
    val ink = LocalInk.current
    val leg = it.legs[legIndex]
    val stops = leg.allStops
    val left = TripGuide.stopsLeft(progress, stops.size)
    val arrivalDelay = ((leg.endTime.epochSecond - leg.scheduledEnd.epochSecond) / 60.0).roundToInt()
    val value = progress.stopValue(stops.size) / max(stops.size - 1, 1)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineBadge(leg); leg.shownDirection?.let { d -> Text(L10n.f("Towards %@", d), fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
        Text(L10n.f("Get off at %@", leg.to.name), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (left == 0) L10n.t("Next stop") else L10n.f("in %@", L10n.f("%lld stops", left))) + " · " + Fmt.time(leg.endTime, leg.to.tz), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            if (arrivalDelay > 0) Text(L10n.f("+%lld min", arrivalDelay), color = ink.delay, fontWeight = FontWeight.SemiBold)
        }
        when (progress) {
            is TripProgress.NotStarted -> L10n.f("Board at %@", stops.first().name)
            is TripProgress.AtStop -> L10n.f("Now at %@", stops[progress.index].name)
            is TripProgress.Between -> stops.getOrNull(progress.index + 1)?.let { s -> L10n.f("Next: %@", s.name) }
            else -> null
        }?.let { Text(it, color = ink.muted, fontSize = 15.sp) }
        LinearProgressIndicator(progress = { value.toFloat().coerceIn(0f, 1f) }, color = lineStroke(leg, ink.dark), trackColor = ink.fill, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        Text(L10n.t(if (leg.realTime) "Position estimated from live stop times." else "Position estimated from the timetable."), color = ink.muted, fontSize = 12.sp)
        SmartRouting.changes(it).firstOrNull { c -> c.outgoingIndex > legIndex }?.let { c -> ChangeLine(c) }
        leg.alerts.orEmpty().take(2).forEach { a -> Text(a.headerText, color = ink.delay, fontSize = 13.sp) }
    }
}

private enum class StopMark { PAST, HERE, NEXT, AHEAD }

private fun stopState(i: Int, p: TripProgress, count: Int): StopMark = when (p) {
    is TripProgress.NotStarted -> if (i == 0) StopMark.HERE else StopMark.AHEAD
    is TripProgress.AtStop -> if (i < p.index) StopMark.PAST else if (i == p.index) StopMark.HERE else StopMark.AHEAD
    is TripProgress.Between -> if (i <= p.index) StopMark.PAST else if (i == p.index + 1) StopMark.NEXT else StopMark.AHEAD
    else -> if (i == count - 1) StopMark.HERE else StopMark.PAST
}

/** One stop of the ride on a line in the line's colour; the yellow dot is where you are. */
@Composable
private fun GuideStopRow(stop: Place, mark: StopMark, isFirst: Boolean, isLast: Boolean, color: Color) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight()) {
                Box(Modifier.width(4.dp).weight(1f).background(if (isFirst) Color.Transparent else color.copy(alpha = if (mark == StopMark.PAST || mark == StopMark.HERE) 0.35f else 1f)))
                Box(Modifier.width(4.dp).weight(1f).background(if (isLast) Color.Transparent else color.copy(alpha = if (mark == StopMark.PAST) 0.35f else 1f)))
            }
            when (mark) {
                StopMark.HERE -> SignatureDot(14)
                else -> Box(Modifier.size(if (isFirst || isLast) 12.dp else 9.dp).background(Color.White, CircleShape).border(2.5.dp, color, CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text((stop.arrival ?: stop.departure)?.let { t -> Fmt.time(t, stop.tz) } ?: "", fontSize = 14.sp, color = ink.muted, modifier = Modifier.width(48.dp))
        Text(stop.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (isLast || mark == StopMark.HERE || mark == StopMark.NEXT) FontWeight.Bold else FontWeight.Normal,
            color = if (mark == StopMark.PAST) ink.muted else ink.ink, modifier = Modifier.padding(vertical = 10.dp))
    }
}

@Composable
private fun ArriveStage(it: Itinerary, destination: String, now: Instant, here: LatLon?, onEnd: () -> Unit) {
    val ink = LocalInk.current
    val last = it.legs.last()
    val arrived = now >= it.endTime
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Flag, null, Modifier.size(30.dp))
        Text(if (arrived) L10n.t("You have arrived") else L10n.f("Arrive at %@", destination), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(destination + " · " + Fmt.time(it.endTime, last.to.tz), color = ink.muted, fontWeight = FontWeight.SemiBold)
    }
    val away = here?.distanceTo(last.to.coordinate)
    if (here != null && away != null && away > 15 && away < 3000) HeadingRow(PathGeometry.bearing(here, last.to.coordinate), L10n.f("Destination in %@", Fmt.distance(away)))
    if (arrived) Button(onClick = onEnd, Modifier.fillMaxWidth().padding(16.dp).height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) {
        Text(L10n.t("End trip"), fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The trip map (GuideMap on iOS): the way already travelled pale, the way ahead in colour; the vehicle
 * or your position as the yellow dot. Frames the stage shown, or the whole trip.
 */
@Composable
private fun GuideMap(it: Itinerary, steps: List<GuideStep>, live: Int, shown: Int?, vehicle: LatLon?, here: LatLon?, base: String, modifier: Modifier) {
    val ink = LocalInk.current
    val ahead = if (ink.dark) Color(0xFF409CFF) else Color(0xFF0A7CFF)
    val done = if (ink.dark) Color(0xFF5A6B80) else Color(0xFFB7CDE6)
    val liveLeg = steps[live].legIndex
    val content = remember(it.routeKey, ink.dark, liveLeg, vehicle, here) {
        val lines = it.legs.mapIndexed { i, l ->
            val color = when { i < liveLeg -> done; l.isTransit -> lineStroke(l, ink.dark); else -> ahead }
            MapContent.Line(l.mapPath, color, if (l.isTransit) 6f else 4f)
        }
        val dots = ArrayList<MapContent.Dot>()
        it.legs.filter { l -> l.isTransit }.drop(1).forEach { l -> dots += MapContent.Dot(l.from.coordinate, Color.White, ink.ink, 10f, 2f) }
        dots += MapContent.Dot(it.legs.first().from.coordinate, Color.White, ink.ink, 14f, 3f)
        dots += MapContent.Dot(it.legs.last().to.coordinate, ink.ink, Color.White, 18f, 3f)
        here?.let { p -> dots += MapContent.Dot(p, ahead, Color.White, 16f, 3f) }
        vehicle?.let { p -> dots += MapContent.Dot(p, Color(0xFFFFC21A), ink.ink, 18f, 3f) }
        MapContent(lines, dots)
    }
    val camera = remember(it.routeKey, shown) {
        if (shown == null) MapCamera.Fit(it.legs.flatMap { l -> l.mapPath + listOf(l.from.coordinate, l.to.coordinate) })
        else {
            val step = steps[shown]; val l = it.legs[step.legIndex]
            if (step.kind == GuideStep.Kind.ARRIVE) MapCamera.Around(l.to.coordinate, 300.0)
            else MapCamera.Fit(l.mapPath + listOf(l.from.coordinate, l.to.coordinate))
        }
    }
    PublitMap(content, camera, modifier, L10n.t("Trip overview"), base = base)
}
