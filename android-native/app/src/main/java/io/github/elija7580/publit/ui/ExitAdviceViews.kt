// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.ExitAdvice
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.StationExits

/**
 * Where to ride and which way out, under a ride that ends in a station (ExitAdviceRow on iOS): "Board at the
 * front · Closest to exit B2" and "Take exit B2". With [walk] the exit can be changed: Publit then remembers it
 * for this station and destination.
 */
@Composable
fun ExitAdviceRow(advice: ExitAdvice, walk: Leg? = null) {
    val ink = LocalInk.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val board = advice.boardText
        val part = advice.part
        if (board != null && part != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrainPartGlyph(part)
            Column {
                Text(board, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                advice.reason?.let { Text(it, color = ink.muted, fontSize = 12.sp) }
            }
        }
        advice.door?.let { door ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(GLYPH_WIDTH), contentAlignment = Alignment.Center) { Icon(Icons.Filled.MeetingRoom, null, Modifier.size(18.dp)) }
                Text(L10n.f("Take exit %@", door), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (advice.doorSaved) Text(L10n.t("Your exit"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ink.muted,
                    modifier = Modifier.background(ink.fill, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        val choices = walk?.let { StationExits.choices(it) }.orEmpty()
        if (walk != null && choices.isNotEmpty()) Box {
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = true }) {
                Text(L10n.t(if (advice.door == null) "Choose exit" else "Change exit"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = ink.ink)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Text(L10n.t("Publit remembers this exit for this station and destination."), color = ink.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(240.dp))
                DropdownMenuItem(text = { Text(L10n.t("Choose automatically")) }, onClick = { open = false; StationExits.setPreferred(null, walk) })
                choices.forEach { e ->
                    DropdownMenuItem(text = { Text(L10n.f("Exit %@", e.ref)) },
                        trailingIcon = if (advice.doorSaved && advice.door == e.ref) ({ Icon(Icons.Filled.Check, null) }) else null,
                        onClick = { open = false; StationExits.setPreferred(e, walk) })
                }
            }
        }
    }
}

/** On board: "Front of the train · Exit B2", so you are at the right door when it stops. */
@Composable
fun ExitAdviceLine(advice: ExitAdvice) {
    val text = advice.short ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val part = advice.part
        if (part != null) TrainPartGlyph(part) else Box(Modifier.width(GLYPH_WIDTH), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MeetingRoom, null, Modifier.size(18.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

private val GLYPH_WIDTH = 36.dp

/** Three cars with the one to board filled in: front on the right, as the train drives to the right. */
@Composable
fun TrainPartGlyph(part: ExitAdvice.Part) {
    val ink = LocalInk.current
    Row(Modifier.width(GLYPH_WIDTH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(ExitAdvice.Part.BACK, ExitAdvice.Part.MIDDLE, ExitAdvice.Part.FRONT).forEach { p ->
            Box(Modifier.size(width = 8.dp, height = 9.dp).background(if (p == part) ink.ink else ink.fill, RoundedCornerShape(2.dp)))
        }
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(10.dp), tint = ink.muted)
    }
}
