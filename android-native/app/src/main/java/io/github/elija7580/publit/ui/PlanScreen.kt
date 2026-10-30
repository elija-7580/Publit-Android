package io.github.elija7580.publit.ui

import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AssistChip
import kotlinx.coroutines.launch
import io.github.elija7580.publit.data.SmartRouting.Change.Level as ChangeLevel
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Tune
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private enum class Field { FROM, TO }

@Composable
fun PlanScreen(vm: PlanViewModel, onSettings: () -> Unit = {}, pinned: List<io.github.elija7580.publit.data.SavedPlace> = emptyList(), actions: @Composable (Itinerary, String, String) -> Unit = { _, _, _ -> }) {
    val state by vm.state.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf<Field?>(null) }
    var options by remember { mutableStateOf(false) }
    val searchRequest by vm.searchRequest.collectAsStateWithLifecycle()
    var handled by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(searchRequest) }
    LaunchedEffect(searchRequest) { if (searchRequest != handled) { handled = searchRequest; searching = Field.TO } }
    val opened = state.opened
    when {
        options -> { BackHandler { options = false }; RouteOptionsScreen(state.options, onDone = { vm.setOptions(it); options = false }) }
        searching != null -> PlaceSearch(vm, allowHere = searching == Field.FROM, onBack = { searching = null }) { e ->
            if (searching == Field.FROM) vm.setFrom(e) else vm.setTo(e)
            searching = null
        }
        opened != null -> { BackHandler { vm.open(null) }; ItineraryDetail(opened, { vm.open(null) }) { actions(opened, state.from.title, state.to?.title ?: "") } }
        else -> PlanForm(state, vm, onPick = { searching = it }, onOptions = { options = true }, onSettings = onSettings, pinned = pinned)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanForm(state: PlanState, vm: PlanViewModel, onPick: (Field) -> Unit, onOptions: () -> Unit, onSettings: () -> Unit, pinned: List<io.github.elija7580.publit.data.SavedPlace> = emptyList()) {
    val ink = LocalInk.current
    var picking by remember { mutableStateOf<Boolean?>(null) } // arriveBy?
    val timelines = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("publit", android.content.Context.MODE_PRIVATE).getBoolean("showTimelines", false)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L10n.t("Plan"), fontWeight = FontWeight.Bold, fontSize = 32.sp, modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp))
                IconButton(onClick = onSettings, modifier = Modifier.padding(end = 8.dp)) { Icon(Icons.Filled.Settings, L10n.t("Settings")) }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        EndpointRow(L10n.t("From"), state.from.title) { onPick(Field.FROM) }
                        HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(start = 16.dp))
                        EndpointRow(L10n.t("To"), state.to?.title ?: L10n.t("Where to?"), placeholder = state.to == null) { onPick(Field.TO) }
                    }
                    IconButton(onClick = vm::swap) { Icon(Icons.Filled.SwapVert, contentDescription = null) }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.time == null, onClick = { vm.setTime(null, false) }, label = { Text(L10n.t("Now")) })
                FilterChip(selected = state.time != null && !state.arriveBy, onClick = { picking = false },
                    label = { Text(if (state.time != null && !state.arriveBy) L10n.t("Depart") + " " + Fmt.time(state.time) else L10n.t("Depart")) })
                FilterChip(selected = state.time != null && state.arriveBy, onClick = { picking = true },
                    label = { Text(if (state.time != null && state.arriveBy) L10n.t("Arrive") + " " + Fmt.time(state.time) else L10n.t("Arrive")) })
            }
        }
        if (pinned.isNotEmpty()) item("pinned") {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pinned.forEach { p ->
                    val icon = if (p.role == io.github.elija7580.publit.data.PlaceRole.WORK) Icons.Filled.Work else Icons.Filled.Home
                    AssistChip(onClick = { vm.setTo(Endpoint.At(p.role?.title ?: p.name, p.coordinate)) },
                        label = { Text(p.role?.title ?: p.name) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
                }
            }
        }
        if (!state.searched) item("recents") { RecentSearches(vm) }
        item("coverage") {
            fun pt(e: Endpoint?) = when (e) { is Endpoint.At -> e.point; Endpoint.Here -> vm.lastFix; null -> null }
            val notices = io.github.elija7580.publit.data.CoverageGuide.notices(pt(state.from), pt(state.to))
            CoverageBanner(notices)
        }
        item("options") {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = onOptions, shape = RoundedCornerShape(50), color = ink.fill, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Tune, null, Modifier.size(18.dp), tint = if (state.options.isDefault) ink.muted else ink.ink)
                        Spacer(Modifier.width(8.dp))
                        Text(L10n.t("Route options"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(state.options.summary ?: L10n.t("All modes"), color = if (state.options.isDefault) ink.muted else ink.ink, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                var sortMenu by remember { mutableStateOf(false) }
                Box {
                    Surface(onClick = { sortMenu = true }, shape = RoundedCornerShape(50), color = ink.fill) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(state.options.sort.effective.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        io.github.elija7580.publit.data.RouteSort.offered.forEach { o ->
                            DropdownMenuItem({ Text(o.title) }, { sortMenu = false; vm.setOptions(state.options.copy(sort = o)) })
                        }
                    }
                }
            }
        }
        when {
            state.loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ink.ink) } }
            state.error != null -> item { Text(state.error, color = ink.muted, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
        if (state.routes.isNotEmpty() && state.previousCursor != null) item("earlier") {
            TextButton(onClick = vm::earlier, modifier = Modifier.padding(horizontal = 8.dp)) { Text(L10n.t("Earlier connections")) }
        }
        val axis = if (timelines && state.routes.isNotEmpty()) state.routes.minOf { r -> r.startTime } to state.routes.maxOf { r -> r.endTime } else null
        if (axis != null) item("axis") { TimeAxis(axis.first, axis.second, state.routes.first().legs.firstOrNull()?.from?.tz) }
        val rec = state.badges.entries.firstOrNull { io.github.elija7580.publit.data.SmartRouting.Badge.RECOMMENDED in it.value }?.key
        items(state.sorted, key = { it.routeKey }) { r ->
            RouteCard(r, state.badges[r.routeKey].orEmpty(),
                if (r.routeKey == rec) io.github.elija7580.publit.data.SmartRouting.reason(r, state.routes) else null, axis, leave = state.from == Endpoint.Here && state.time == null) { vm.open(r) }
        }
        if (state.street.isNotEmpty()) item("street") {
            Text(L10n.t("Other ways").uppercase(), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 28.dp, bottom = 6.dp))
            val best = state.routes.filter { r -> !r.isDirect && !r.isCancelled }.minOfOrNull { r -> r.duration }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                Column {
                    state.street.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        Row(Modifier.fillMaxWidth().clickable { vm.open(c.itinerary) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (c.mode) { "BIKE" -> Icons.AutoMirrored.Filled.DirectionsBike; "CAR" -> Icons.Filled.DirectionsCar; else -> Icons.AutoMirrored.Filled.DirectionsWalk }, null, tint = ink.muted)
                            Spacer(Modifier.width(10.dp))
                            Text(L10n.t(when (c.mode) { "BIKE" -> "Bike"; "CAR" -> "Car"; else -> "Walk" }), modifier = Modifier.weight(1f))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Fmt.duration(c.duration) + (c.distance?.let { " · " + Fmt.distance(it) } ?: ""), color = ink.muted)
                                best?.let { b ->
                                    val diff = (c.duration - b) / 60
                                    Text(when { kotlin.math.abs(diff) <= 2 -> L10n.t("About as fast"); diff < 0 -> L10n.f("%lld min faster", -diff); else -> L10n.f("%lld min slower", diff) },
                                        fontSize = 12.sp, color = if (diff < -2) ink.live else ink.muted)
                                }
                            }
                        }
                    }
                }
            }
            if (state.street.any { it.mode == "CAR" }) Text(L10n.t("Driving times are without traffic and parking."), color = ink.muted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
        }
        if (state.street.isEmpty()) state.walkOnly?.let { w -> item("walk") {
            Row(Modifier.fillMaxWidth().clickable { vm.open(w) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = ink.muted)
                Spacer(Modifier.width(8.dp))
                Text(L10n.f("%@ walk", Fmt.duration(w.duration)), color = ink.muted)
            }
        } }
        if (state.routes.isNotEmpty() && state.nextCursor != null) item("later") {
            TextButton(onClick = vm::later, modifier = Modifier.padding(horizontal = 8.dp)) { Text(L10n.t("Later connections")) }
        }
    }
    picking?.let { arrive ->
        val now = java.time.LocalTime.now()
        val tp = rememberTimePickerState(now.hour, now.minute, is24Hour = Fmt.timePattern.startsWith("H"))
        AlertDialog(onDismissRequest = { picking = null },
            confirmButton = { TextButton(onClick = {
                val zone = ZoneId.systemDefault()
                var t = LocalDate.now().atTime(LocalTime.of(tp.hour, tp.minute)).atZone(zone).toInstant()
                // An earlier time than now means tomorrow.
                if (t.isBefore(Instant.now().minusSeconds(600))) t = t.plusSeconds(86_400)
                vm.setTime(t, arrive); picking = null
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(L10n.t("Cancel")) } },
            text = { TimePicker(tp) })
    }
}

@Composable
private fun EndpointRow(label: String, value: String, placeholder: Boolean = false, onClick: () -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ink.muted, fontSize = 14.sp, modifier = Modifier.width(56.dp))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = if (placeholder) ink.muted else ink.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RouteCard(it: Itinerary, badges: List<io.github.elija7580.publit.data.SmartRouting.Badge>, reason: String?, axis: Pair<java.time.Instant, java.time.Instant>? = null, leave: Boolean = false, onClick: () -> Unit) {
    val ink = LocalInk.current
    val tz = it.legs.firstOrNull()?.from?.tz
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick), shape = RoundedCornerShape(14.dp), color = ink.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // From your location, leaving now (GoNowView on iOS): when to set off is the first thing.
            if (leave) {
                val mins = kotlin.math.ceil((it.startTime.epochSecond - java.time.Instant.now().epochSecond) / 60.0).toLong()
                Text(if (it.isDirect) L10n.t("Walk all the way") else if (mins <= 0) L10n.t("Leave now") else L10n.f("Leave in %lld min", mins),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (!it.isDirect && mins <= 2) ink.delay else ink.muted)
            }
            if (badges.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                badges.forEach { b ->
                    val main = b == io.github.elija7580.publit.data.SmartRouting.Badge.RECOMMENDED
                    Text(b.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (main) ink.onInk else ink.ink,
                        modifier = Modifier.background(if (main) ink.ink else ink.fill, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                reason?.let { r -> Text(r, fontSize = 12.sp, color = ink.muted) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.time(it.startTime, tz) + " – " + Fmt.time(it.endTime, it.legs.lastOrNull()?.to?.tz),
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f),
                    color = if (it.isCancelled) ink.danger else ink.ink)
                Text(Fmt.duration(it.duration), fontWeight = FontWeight.SemiBold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                it.transitLegs.forEach { leg -> LegBadge(leg) }
            }
            axis?.let { a -> TimelineBar(it, a.first, a.second) }
            val first = it.transitLegs.firstOrNull()
            Text(listOfNotNull(
                if (it.transfers == 0) L10n.t("Direct") else if (it.transfers == 1) L10n.t("1 change") else L10n.f("%lld changes", it.transfers),
                first?.let { f -> L10n.f("%@ from %@", f.lineName, f.from.name) },
                if (it.isCancelled) L10n.t("Connection cancelled") else null,
                io.github.elija7580.publit.data.Fares.estimate(it)?.formattedAmount,
            ).joinToString(" · "), color = if (it.isCancelled) ink.danger else ink.muted, fontSize = 13.sp, maxLines = 2)
        }
    }
}

@Composable
private fun LegBadge(leg: Leg) {
    val c = leg.lineColors
    Row(Modifier.background(hexColor(c.background), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(modeIcon(leg.mode), null, Modifier.size(14.dp), tint = hexColor(c.text))
        Spacer(Modifier.width(3.dp))
        Text(leg.lineName, color = hexColor(c.text), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun ItineraryDetail(it: Itinerary, onBack: () -> Unit, title: String? = null, top: @Composable () -> Unit = {}) {
    val ink = LocalInk.current
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                if (title != null) Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) else
                Text(Fmt.time(it.startTime, it.legs.firstOrNull()?.from?.tz) + " – " + Fmt.time(it.endTime, it.legs.lastOrNull()?.to?.tz) + " · " + Fmt.duration(it.duration),
                    fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
        item("coverage") { CoverageBanner(io.github.elija7580.publit.data.CoverageGuide.notices(it)) }
        item("top") { top() }
        item("map") {
            var full by remember { mutableStateOf(false) }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                Box(Modifier.height(200.dp)) {
                    RouteMap(it, Modifier.fillMaxSize())
                    Box(Modifier.matchParentSize().clickable { full = true })
                }
            }
            if (full) androidx.compose.ui.window.Dialog(onDismissRequest = { full = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize()) {
                    RouteMap(it, Modifier.fillMaxSize())
                    androidx.compose.material3.TextButton(onClick = { full = false },
                        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) { Text(L10n.t("Done"), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        val changes = io.github.elija7580.publit.data.SmartRouting.changes(it)
        if (io.github.elija7580.publit.data.SmartRouting.isEnabled && !it.isDirect) item("felt") {
            val felt = io.github.elija7580.publit.data.SmartRouting.feltMinutes(it).roundToInt()
            Text(L10n.f("Feels like %@", Fmt.duration(felt * 60)) + if (changes.isEmpty()) " · " + L10n.t("No change: nothing to miss") else "",
                color = ink.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 28.dp))
        }
        if (changes.isNotEmpty()) item("changes") { ChangesCard(changes) }
        if (it.transitLegs.any { l -> l.endTime > java.time.Instant.now() }) item("getoff") { GetOffCard(it) }
        items(it.legs.size) { i ->
            val leg = it.legs[i]
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                if (leg.isTransit) TransitLeg(leg) else Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = ink.muted)
                    Spacer(Modifier.width(8.dp))
                    val dist = leg.distance?.let { d -> " · " + Fmt.distance(d) } ?: ""
                    Text(L10n.f("%@ walk", Fmt.duration(leg.duration)) + dist + " → " + leg.to.name, color = ink.muted, fontSize = 14.sp)
                }
            }
        }
        val products = io.github.elija7580.publit.data.Fares.products(it)
        if (products.isNotEmpty()) item("fares") { FareCard(it, products) }
    }
}

@Composable
private fun FareCard(it: Itinerary, products: List<io.github.elija7580.publit.data.FareProduct>) {
    val ink = LocalInk.current
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text(L10n.t("Fare information"), color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = ink.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                io.github.elija7580.publit.data.Fares.estimate(it)?.let { e ->
                    Row { Text(L10n.t("Fare estimate"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text(e.formattedAmount, fontWeight = FontWeight.SemiBold) }
                }
                products.take(6).forEach { p ->
                    Column {
                        Row { Text(p.name, Modifier.weight(1f)); Text(p.formattedAmount) }
                        p.riderCategory?.let { c -> Text(c.riderCategoryName, color = ink.muted, fontSize = 12.sp) }
                        p.media?.fareMediaName?.let { m -> Text(m, color = ink.muted, fontSize = 12.sp) }
                    }
                }
            }
        }
        Text(L10n.t("Experimental operator fare data, not a binding quote. Product options are not necessarily the total trip price. Check eligibility and the operator's price before buying."),
            color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
    }
}

@Composable
private fun ChangesCard(changes: List<io.github.elija7580.publit.data.SmartRouting.Change>) {
    val ink = LocalInk.current
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            changes.forEachIndexed { i, c ->
                val color = when (c.level) { ChangeLevel.SAFE -> ink.live; ChangeLevel.LIKELY -> ink.delay; ChangeLevel.RISKY -> ink.danger }
                val verdict = L10n.t(when (c.level) { ChangeLevel.SAFE -> "Safe change"; ChangeLevel.LIKELY -> "Change should work"; ChangeLevel.RISKY -> "Risky change" })
                val spare = if (c.spareMinutes >= 1) L10n.f("%@ to spare", Fmt.duration((c.spareMinutes * 60).toInt())) else L10n.t("Very tight change")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(L10n.f("Change at %@", c.stopName), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.percent} %", color = color, fontWeight = FontWeight.Bold)
                    }
                    Text("$verdict · $spare", color = ink.muted, fontSize = 12.sp)
                    val b = io.github.elija7580.publit.data.SmartRouting.backup(c.outgoing)
                    if (b != null) {
                        val then = if (i == changes.lastIndex) L10n.f("Arrive %@", Fmt.time(b.arrival, c.outgoing.to.tz)) else L10n.t("still makes the next change")
                        Text(L10n.f("If missed: %@ at %@", b.leg.lineName, Fmt.time(b.leg.startTime, b.leg.from.tz)) + " · " + then, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    } else if (c.level != ChangeLevel.SAFE) Text(L10n.t("No backup found if you miss it."), color = ink.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Route options (RouteOptionsView on iOS): modes, first mile, changes, walking, step-free, sharing, comparison. */
@Composable
fun RouteOptionsScreen(initial: io.github.elija7580.publit.data.RouteOptions, onDone: (io.github.elija7580.publit.data.RouteOptions) -> Unit) {
    val ink = LocalInk.current
    var o by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ o = io.github.elija7580.publit.data.RouteOptions(compareStreetModes = o.compareStreetModes, sort = o.sort) }, enabled = !o.isDefault) { Text(L10n.t("Reset")) }
            Text(L10n.t("Route options"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton({ onDone(o) }) { Text(L10n.t("Done"), fontWeight = FontWeight.SemiBold, color = ink.ink) }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                OptionGroup(L10n.t("Transport modes"), L10n.t("Routes only use the modes that are switched on.")) {
                    val f = o.filter
                    io.github.elija7580.publit.data.ModeGroup.entries.forEach { g ->
                        val on = f.allows(g)
                        val last = f.isActive && f.included == setOf(g)
                        SwitchRow(g.title, on, enabled = !last) { v ->
                            val base = if (f.isActive) f.included else io.github.elija7580.publit.data.ModeGroup.entries.toSet()
                            val next = if (v) base + g else base - g
                            o = o.copy(modes = if (next.size == io.github.elija7580.publit.data.ModeGroup.entries.size) emptyList() else next.map { it.raw })
                        }
                    }
                }
            }
            item {
                OptionGroup(L10n.t("To the first stop"), L10n.t("Bike & Ride: cycle to a station and park there. Park & Ride: drive to a car park at a station; this search can take up to half a minute. Walking routes stay in the list for comparison; buses, trams and trains are always included.")) {
                    io.github.elija7580.publit.data.FirstMile.entries.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable { o = o.copy(firstMile = m) }.padding(16.dp)) {
                            Text(m.summary, Modifier.weight(1f)); if (o.firstMile == m) Text("✓", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item { SmartRoutingSettings() }
            item {
                OptionGroup(L10n.t("Changes"), null) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                        io.github.elija7580.publit.data.RouteOptions.transferChoices.forEachIndexed { i, n ->
                            SegmentedButton(o.maxTransfers == n, { o = o.copy(maxTransfers = n) }, SegmentedButtonDefaults.itemShape(i, 4)) {
                                Text(io.github.elija7580.publit.data.RouteOptions.transferTitle(n), maxLines = 1, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            item {
                OptionGroup(null, L10n.t("Step-free routes avoid stairs where the data allows it. With sharing, routes can start or end on a rented bike or e-scooter; the search takes a little longer.")) {
                    Text(L10n.t("Walk to and from stops"), modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                        io.github.elija7580.publit.data.RouteOptions.walkChoices.forEachIndexed { i, m ->
                            SegmentedButton(o.maxWalkMinutes == m, { o = o.copy(maxWalkMinutes = m) }, SegmentedButtonDefaults.itemShape(i, 5)) { Text("$m", fontSize = 12.sp) }
                        }
                    }
                    SwitchRow(L10n.t("Step-free (wheelchair)"), o.wheelchair) { o = o.copy(wheelchair = it) }
                    SwitchRow(L10n.t("Shared bikes and e-scooters"), o.sharing) { o = o.copy(sharing = it) }
                }
            }
            item {
                OptionGroup(null, L10n.t("Driving times are without traffic and parking.")) {
                    SwitchRow(L10n.t("Compare with walking, cycling and driving"), o.compareStreetModes) { o = o.copy(compareStreetModes = it) }
                }
            }
        }
    }
}

@Composable
fun OptionGroup(title: String?, footer: String?, content: @Composable () -> Unit) {
    val ink = LocalInk.current
    Column {
        title?.let { Text(it, color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)) }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = ink.surface) { Column { content() } }
        footer?.let { Text(it, color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) }
    }
}

@Composable
fun SwitchRow(title: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        androidx.compose.material3.Switch(on, onChange, enabled = enabled, colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedTrackColor = LocalInk.current.ink, checkedThumbColor = LocalInk.current.onInk))
    }
}

/** Settings → Smart routing (SmartRoutingSettingsSection on iOS). Walking speed from the step counter is not on Android yet. */
/** "Use my walking speed" from Health Connect (the step counter), measured on the device. */
@Composable
private fun WalkingSpeedRows(p: android.content.SharedPreferences) {
    val S = io.github.elija7580.publit.data.SmartRouting
    val W = io.github.elija7580.publit.location.WalkingPace
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (!W.available(ctx)) return
    val ink = LocalInk.current
    var on by remember { mutableStateOf(p.getBoolean(S.OWN_SPEED, false)) }
    var speed by remember { mutableStateOf(p.getFloat(S.MEASURED_SPEED, 0f).toDouble()) }
    var measuring by remember { mutableStateOf(false) }
    var trigger by remember { mutableStateOf(0) }
    LaunchedEffect(on, trigger) {
        if (!on) return@LaunchedEffect
        measuring = true
        W.measure(ctx)?.let { speed = it }
        measuring = false
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()) { granted ->
        on = granted.isNotEmpty(); p.edit().putBoolean(S.OWN_SPEED, on).apply(); trigger++
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SwitchRow(L10n.t("Use my walking speed"), on) { v ->
        if (!v) { on = false; p.edit().putBoolean(S.OWN_SPEED, false).apply() }
        else scope.launch { if (W.granted(ctx)) { on = true; p.edit().putBoolean(S.OWN_SPEED, true).apply(); trigger++ } else ask.launch(W.permissions) }
    }
    if (on) Text(when {
        measuring -> L10n.t("Your walking speed") + " …"
        speed > 0 -> L10n.t("Your walking speed") + ": " + String.format(L10n.locale, "%.1f km/h", speed * 3.6) + " · " + L10n.t("From the step counter")
        else -> L10n.t("Not enough walking measured yet.")
    }, color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
fun SmartRoutingSettings() {
    val S = io.github.elija7580.publit.data.SmartRouting
    val p = S.prefs ?: return
    var enabled by remember { mutableStateOf(S.isEnabled) }
    var minutes by remember { mutableStateOf(S.weights.transferMinutes.toFloat()) }
    var extra by remember { mutableStateOf(p.getInt(S.EXTRA_TRANSFER, 0)) }
    var flat by remember { mutableStateOf(p.getBoolean(S.FLAT_WALKS, false)) }
    var learn by remember { mutableStateOf(S.learns) }
    OptionGroup(L10n.t("Smart routing"), L10n.t(if (enabled)
        "Routes stay in time order. The one that feels shortest is marked “Recommended”: waiting, walking and changes count more than riding. Each change shows how likely it works and what to take if you miss it. Your choices tune this on your iPhone only."
        else "Routes are listed by departure, as the planner returns them.").replace("iPhone", "Android")) {
        SwitchRow(L10n.t("Smart routing"), enabled) { enabled = it; p.edit().putBoolean(S.ENABLED, it).apply() }
        if (enabled) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row { Text(L10n.t("Faster"), fontSize = 13.sp); Spacer(Modifier.weight(1f)); Text(L10n.t("Fewer changes"), fontSize = 13.sp) }
                androidx.compose.material3.Slider(minutes, { minutes = it }, valueRange = 3f..30f,
                    onValueChangeFinished = { p.edit().putFloat(S.TRANSFER_MINUTES, minutes).apply() })
                Text(L10n.f("A change counts like %lld extra minutes.", minutes.toInt()), color = LocalInk.current.muted, fontSize = 12.sp)
            }
            Text(L10n.t("Extra time for changes"), modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                S.extraTransferChoices.forEachIndexed { i, m ->
                    SegmentedButton(extra == m, { extra = m; p.edit().putInt(S.EXTRA_TRANSFER, m).apply() }, SegmentedButtonDefaults.itemShape(i, S.extraTransferChoices.size)) {
                        Text(if (m == 0) L10n.t("None") else L10n.f("+%lld min", m), fontSize = 12.sp)
                    }
                }
            }
            WalkingSpeedRows(p)
            SwitchRow(L10n.t("Avoid steep walks"), flat) { flat = it; p.edit().putBoolean(S.FLAT_WALKS, it).apply() }
            SwitchRow(L10n.t("Learn from my choices"), learn) { learn = it; p.edit().putBoolean(S.LEARN, it).apply() }
            TextButton({ S.resetLearning(); minutes = S.DEFAULT_TRANSFER_MINUTES.toFloat() }, Modifier.padding(horizontal = 4.dp)) { Text(L10n.t("Reset learned preferences")) }
        }
    }
}

@Composable
private fun TransitLeg(leg: Leg) {
    val ink = LocalInk.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegBadge(leg)
            Spacer(Modifier.width(8.dp))
            Text(leg.shownDirection?.let { L10n.t("Towards") + " " + it } ?: "", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2)
        }
        StopLine(leg.startTime, leg.from.tz, leg.from.name, leg.from.track, leg.delayMinutes, leg.realTime)
        val n = leg.intermediateStops?.size ?: 0
        Text(Fmt.duration(leg.duration) + if (n > 0) " · " + (if (n == 1) L10n.t("1 stop") else L10n.f("%lld stops", n)) else "",
            color = ink.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 56.dp))
        StopLine(leg.endTime, leg.to.tz, leg.to.name, leg.to.track, 0, leg.realTime)
        if (leg.cancelled == true) Text(L10n.t("Cancelled"), color = ink.danger, fontWeight = FontWeight.SemiBold)
        leg.serviceAlerts.firstOrNull()?.let { Text(it.headerText, color = ink.delay, fontSize = 13.sp) }
    }
}

@Composable
private fun StopLine(time: Instant, tz: String?, name: String, track: String?, delay: Int, live: Boolean) {
    val ink = LocalInk.current
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.width(56.dp)) {
            Text(Fmt.time(time, tz), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (delay > 0) ink.delay else ink.ink)
            if (delay > 0) Text(L10n.f("+%lld min", delay), color = ink.delay, fontSize = 11.sp)
        }
        Column {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            track?.takeIf { it.isNotBlank() }?.let { Text(L10n.f("Platform %@", it), color = ink.muted, fontSize = 12.sp) }
        }
    }
}

/** Search for a place or stop, as PlaceSearchView on iOS. */
@Composable
fun PlaceSearch(vm: PlanViewModel, allowHere: Boolean, onBack: () -> Unit, onMatch: ((io.github.elija7580.publit.data.GeocodeMatch) -> Unit)? = null,
                onPick: (Endpoint) -> Unit) {
    val ink = LocalInk.current
    BackHandler(onBack = onBack)
    var text by remember { mutableStateOf("") }
    val matches by vm.matches.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { vm.query(""); focus.requestFocus() }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            OutlinedTextField(text, { text = it; vm.query(it) }, singleLine = true, placeholder = { Text(L10n.t("Search")) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            if (allowHere) item {
                Row(Modifier.fillMaxWidth().clickable { onPick(Endpoint.Here) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MyLocation, null)
                    Spacer(Modifier.width(12.dp))
                    Text(L10n.t("Current location"), fontWeight = FontWeight.SemiBold)
                }
            }
            items(matches, key = { it.id + it.lat + it.lon }) { m ->
                Row(Modifier.fillMaxWidth().clickable { if (onMatch != null) onMatch(m) else onPick(Endpoint.At(m.name, LatLon(m.lat, m.lon))) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (m.isStop) modeIcon("BUS") else Icons.Filled.Place, null, tint = ink.muted)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(m.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (m.subtitle.isNotEmpty()) Text(m.subtitle, color = ink.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Searched before (RecentSearchesSection on iOS): one tap searches again with current departures. */
@Composable
fun RecentSearches(vm: PlanViewModel) {
    val ink = LocalInk.current
    val recents by vm.recents.collectAsStateWithLifecycle()
    if (recents.isEmpty()) return
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L10n.t("Recently searched").uppercase(), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 16.dp))
            TextButton(onClick = vm::clearRecents) { Text(L10n.t("Clear history"), fontSize = 13.sp) }
        }
        Surface(shape = RoundedCornerShape(14.dp), color = ink.surface) {
            Column {
                recents.take(8).forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(start = 52.dp))
                    Row(Modifier.fillMaxWidth().clickable { vm.route(r.from, r.to) }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.History, null, tint = ink.muted)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.to.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(L10n.f("From %@", r.from.title), color = ink.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { vm.removeRecent(r) }) { Icon(Icons.Filled.Close, L10n.t("Remove"), tint = ink.muted) }
                    }
                }
            }
        }
    }
}

/** "Remind me to get off" per ride (GetOffButton on iOS). */
@Composable
private fun GetOffCard(it: Itinerary) {
    val ink = LocalInk.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val N = io.github.elija7580.publit.notify.TripNotifications
    var tick by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { tick++ }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
        Column {
            it.transitLegs.filter { l -> l.endTime > java.time.Instant.now() }.forEachIndexed { i, leg ->
                if (i > 0) HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                val set = remember(tick) { N.isGetOffSet(ctx, leg) }
                Row(Modifier.fillMaxWidth().clickable {
                    if (!N.allowed(ctx)) {
                        if (android.os.Build.VERSION.SDK_INT >= 33) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        note = L10n.t("Notifications are off. Turn them on in Settings to get reminders."); return@clickable
                    }
                    val at = N.scheduleGetOff(ctx, leg)
                    note = if (at == null) L10n.t("This stop is less than two minutes away.") else L10n.t("Reminder set") + " – " + L10n.f("We'll notify you at %@.", Fmt.time(at, leg.to.tz))
                    tick++
                }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineBadge(leg); Spacer(Modifier.width(10.dp))
                    Text(if (set) L10n.t("Reminder set") else L10n.t("Remind me to get off"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(leg.to.name, color = ink.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                }
            }
            note?.let { n -> Text(n, color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) }
        }
    }
}

/** All rows on one clock (TimelineBar on iOS): rides coloured, walks thin grey, waits dotted. */
@Composable
fun TimelineBar(it: Itinerary, start: java.time.Instant, end: java.time.Instant) {
    val ink = LocalInk.current
    val total = (end.epochSecond - start.epochSecond).coerceAtLeast(60).toFloat()
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        fun x(t: java.time.Instant) = size.width * ((t.epochSecond - start.epochSecond) / total)
        val mid = size.height / 2
        var last: java.time.Instant? = null
        it.legs.forEach { l ->
            last?.let { p -> if (l.startTime > p) drawLine(ink.muted, androidx.compose.ui.geometry.Offset(x(p), mid), androidx.compose.ui.geometry.Offset(x(l.startTime), mid), 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 6f))) }
            if (l.isTransit) drawRoundRect(lineStroke(l, ink.dark), androidx.compose.ui.geometry.Offset(x(l.startTime), 0f),
                androidx.compose.ui.geometry.Size((x(l.endTime) - x(l.startTime)).coerceAtLeast(4f), size.height), androidx.compose.ui.geometry.CornerRadius(6f))
            else drawLine(ink.hairline, androidx.compose.ui.geometry.Offset(x(l.startTime), mid), androidx.compose.ui.geometry.Offset(x(l.endTime), mid), 6f)
            last = l.endTime
        }
    }
}

@Composable
private fun TimeAxis(start: java.time.Instant, end: java.time.Instant, tz: String?) {
    val ink = LocalInk.current
    var guide by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Fmt.time(start, tz), color = ink.muted, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { guide = true }) { Text(L10n.t("Time axis"), fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        Text(Fmt.time(end, tz), color = ink.muted, fontSize = 12.sp)
    }
    if (guide) ConnectionGuide { guide = false }
}

/** "How to read connections" (ConnectionGuideView on iOS). */
@Composable
fun ConnectionGuide(onDismiss: () -> Unit) {
    val ink = LocalInk.current
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text(L10n.t("Done")) } },
        title = { Text(L10n.t("How to read connections")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L10n.t("All rows use the same clock. Later departures start farther right."))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp, 10.dp).background(ink.ink, RoundedCornerShape(3.dp))); Text(L10n.t("Ride"))
                    Box(Modifier.size(22.dp, 3.dp).background(ink.hairline)); Text(L10n.t("Walk"))
                    Text("···", color = ink.muted); Text(L10n.t("Wait"))
                }
                Text(L10n.t("The coloured blocks are rides. Thin grey sections are walking; dotted gaps are waiting between legs."))
                Text(L10n.t("This is a comparison, not live progress. Check the departure times, line names and change warnings."), color = ink.muted, fontSize = 13.sp)
            }
        })
}
