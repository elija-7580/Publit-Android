// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.CalendarAppointment
import io.github.elija7580.publit.data.CalendarTrips
import io.github.elija7580.publit.data.SavedPlace
import io.github.elija7580.publit.location.CalendarSuggestions
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "Today", else the short weekday of the event (CalendarRouting.day on iOS). */
private fun day(i: Instant): String {
    val d = i.atZone(ZoneId.systemDefault()).toLocalDate()
    return if (d == java.time.LocalDate.now()) L10n.t("Today")
    else DateTimeFormatter.ofPattern("EEE", L10n.locale).format(d)
}

/** One event as a destination: a small calendar leaf with the time, then title and place (CalendarAppointmentRow on iOS). */
@Composable
private fun AppointmentRow(a: CalendarAppointment, resolving: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 58.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(58.dp).background(ink.fill, RoundedCornerShape(10.dp)).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(day(a.start).uppercase(), color = ink.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(Fmt.time(a.start), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Place, null, Modifier.size(14.dp), tint = ink.muted)
                Spacer(Modifier.width(4.dp))
                Text(a.placeName, color = ink.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (resolving) CircularProgressIndicator(Modifier.size(18.dp), color = ink.ink, strokeWidth = 2.dp)
        else Icon(Icons.AutoMirrored.Filled.ArrowForward, L10n.t("Shows routes that get you there on time"), Modifier.size(18.dp), tint = ink.muted)
    }
}

/**
 * Plan, before a search: the next events with a clear place, or once the offer to suggest them
 * (CalendarPlanSection on iOS). Events are read again on each appearance (at most once a minute).
 */
@Composable
fun CalendarPlanSection(vm: PlanViewModel, saved: List<SavedPlace>) {
    val ink = LocalInk.current
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("publit", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val appointments by CalendarSuggestions.appointments.collectAsStateWithLifecycle()
    val enabled by CalendarSuggestions.enabled.collectAsStateWithLifecycle()
    val resolving by vm.resolving.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(prefs.getBoolean(CalendarTrips.INVITE_DISMISSED_KEY, false)) }
    var turnedOn by remember { mutableStateOf(prefs.getBoolean(CalendarTrips.ENABLED_KEY, false)) }
    LaunchedEffect(saved) { CalendarSuggestions.refresh(ctx, vm.lastFix, saved) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        CalendarSuggestions.setEnabled(ctx, granted)
        turnedOn = granted
        if (!granted) { dismissed = true; prefs.edit().putBoolean(CalendarTrips.INVITE_DISMISSED_KEY, true).apply() }
        scope.launch { CalendarSuggestions.refresh(ctx, vm.lastFix, saved, force = true) }
    }
    when {
        enabled && appointments.isNotEmpty() -> Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.padding(start = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CalendarMonth, null, Modifier.size(14.dp), tint = ink.muted)
                Spacer(Modifier.width(6.dp))
                Text(L10n.t("From your calendar").uppercase(), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Surface(shape = RoundedCornerShape(14.dp), color = ink.surface) {
                Column {
                    appointments.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(start = 82.dp))
                        AppointmentRow(a, resolving == a.id) {
                            scope.launch {
                                if (!vm.routeTo(a)) vm.showError(L10n.f("No place found for “%@”.", a.placeName))
                            }
                        }
                    }
                }
            }
        }
        !enabled && !turnedOn && !dismissed -> Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(34.dp).background(ink.fill, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.EventAvailable, null, Modifier.size(20.dp), tint = ink.ink)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(L10n.t("Your appointments as destinations"), fontWeight = FontWeight.SemiBold)
                        Text(L10n.t("Publit suggests routes to upcoming events that have a place, so you arrive on time. Your calendar is read on this device only."),
                            color = ink.muted, fontSize = 14.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        if (CalendarSuggestions.hasPermission(ctx)) {
                            CalendarSuggestions.setEnabled(ctx, true); turnedOn = true
                            scope.launch { CalendarSuggestions.refresh(ctx, vm.lastFix, saved, force = true) }
                        } else ask.launch(Manifest.permission.READ_CALENDAR)
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) { Text(L10n.t("Turn on")) }
                    Button(onClick = { dismissed = true; prefs.edit().putBoolean(CalendarTrips.INVITE_DISMISSED_KEY, true).apply() },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ink.fill, contentColor = ink.ink)) { Text(L10n.t("Not now")) }
                }
            }
        }
    }
}

/** Above the routes to a calendar event: what it is and when to leave to be there a little early (AppointmentBanner on iOS). */
@Composable
fun AppointmentBanner(state: PlanState) {
    val a = state.appointment ?: return
    val ink = LocalInk.current
    val best = CalendarTrips.latestOnTime(state.routes, a.arriveBy)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(34.dp).background(ink.fill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CalendarMonth, null, Modifier.size(18.dp), tint = ink.ink)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("${a.displayTitle} · ${Fmt.time(a.start)}", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (best != null) {
                Text(L10n.f("Leave by %@ to arrive %lld min early", Fmt.time(best.startTime, best.legs.firstOrNull()?.from?.tz),
                    CalendarTrips.ARRIVE_EARLY_SECONDS / 60), color = ink.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            } else if (state.routes.isNotEmpty()) {
                Text(L10n.f("No connection arrives before %@", Fmt.time(a.start)), color = ink.delay, fontSize = 14.sp)
            }
        }
    }
}

/** Settings: the switch for calendar suggestions, with what it reads and where it stays (CalendarSettingsSection on iOS). */
@Composable
fun CalendarSettingsSection() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("publit", android.content.Context.MODE_PRIVATE) }
    var on by remember { mutableStateOf(CalendarSuggestions.isActive(ctx)) }
    var blocked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        CalendarSuggestions.setEnabled(ctx, granted)
        on = granted
        blocked = !granted
    }
    OptionGroup(L10n.t("Calendar"),
        if (blocked) L10n.t("Calendar access is off for Publit. Turn it on in the Settings app.")
        else L10n.t("Publit suggests routes to upcoming events that have a place, so you arrive on time. Your calendar is read on this device only.")) {
        SwitchRow(L10n.t("Suggest appointments as destinations"), on) { want ->
            if (!want) { CalendarSuggestions.setEnabled(ctx, false); on = false }
            else if (CalendarSuggestions.hasPermission(ctx)) { CalendarSuggestions.setEnabled(ctx, true); on = true }
            else { prefs.edit().putBoolean(CalendarTrips.ENABLED_KEY, true).apply(); ask.launch(Manifest.permission.READ_CALENDAR) }
        }
        if (blocked) TextButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, modifier = Modifier.padding(horizontal = 4.dp)) { Text(L10n.t("Open Settings")) }
    }
}
