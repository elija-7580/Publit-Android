package io.github.elija7580.publit.ui

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elija7580.publit.billing.SupportBilling
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.Support
import io.github.elija7580.publit.data.WhatsNew
import io.github.elija7580.publit.data.Wish
import io.github.elija7580.publit.data.WishesClient
import kotlinx.coroutines.launch

/** "Publit is a community project": wishes and support, at the top of Settings and in What's new. */
@Composable
fun CommunityCard(onWishes: () -> Unit, onSupport: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = ink.fill) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Groups, null, Modifier.size(22.dp), tint = ink.ink)
                Spacer(Modifier.width(8.dp))
                Text(L10n.t("Publit is a community project"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Text(L10n.t("Vote for what comes next, or suggest your own idea. No account needed."), color = ink.muted, fontSize = 14.sp)
            Button(onWishes, Modifier.fillMaxWidth().heightIn(min = 46.dp), colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) {
                Icon(Icons.Filled.Lightbulb, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(L10n.t("Suggest features and vote"), fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onSupport, Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                Icon(Icons.Filled.FavoriteBorder, null, Modifier.size(18.dp), tint = ink.ink); Spacer(Modifier.width(6.dp))
                Text(L10n.t("Support Publit"), fontWeight = FontWeight.SemiBold, color = ink.ink)
            }
        }
    }
}

/**
 * After an update (not on a fresh install): what's new, with the community card below.
 * [freshInstall] is true when this start showed the onboarding.
 */
@Composable
fun WhatsNewDialog(prefs: SharedPreferences, freshInstall: Boolean, ready: Boolean, onWishes: () -> Unit, onSupport: () -> Unit) {
    var items by remember { mutableStateOf<List<WhatsNew.Item>>(emptyList()) }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        if (freshInstall) { prefs.edit().putInt(WhatsNew.SEEN_KEY, WhatsNew.latest).apply(); return@LaunchedEffect }
        val new = WhatsNew.items(prefs.getInt(WhatsNew.SEEN_KEY, 0))
        if (new.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        items = new
    }
    if (items.isEmpty()) return
    val ink = LocalInk.current
    fun close() { prefs.edit().putInt(WhatsNew.SEEN_KEY, WhatsNew.latest).apply(); items = emptyList() }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(L10n.t("What's new in Publit"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items.forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(icon(item.icon), null, Modifier.size(24.dp), tint = ink.ink)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(L10n.t(item.title), fontWeight = FontWeight.SemiBold)
                            Text(L10n.t(item.text), color = ink.muted, fontSize = 14.sp)
                        }
                    }
                }
                CommunityCard(onWishes = { close(); onWishes() }, onSupport = { close(); onSupport() })
            }
        },
        confirmButton = { TextButton(::close) { Text(L10n.t("Continue"), color = ink.ink, fontWeight = FontWeight.SemiBold) } },
        containerColor = ink.surface,
    )
}

private fun icon(name: String): ImageVector = when (name) {
    "swap" -> Icons.Filled.SwapHoriz
    "community" -> Icons.Filled.Groups
    "heart" -> Icons.Filled.Favorite
    "ticket" -> Icons.Filled.ConfirmationNumber
    "map" -> Icons.Filled.Map
    "bell" -> Icons.Filled.NotificationsActive
    "scope" -> Icons.Filled.GpsFixed
    "walk" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "car" -> Icons.Filled.DirectionsCar
    "widget" -> Icons.Filled.Widgets
    "star" -> Icons.Filled.Star
    "history" -> Icons.Filled.History
    else -> Icons.Filled.Lightbulb
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

/** Suggest features and vote (WishesView on iOS): the same list as publit.eu/wuensche. */
@Composable
fun WishesScreen(prefs: SharedPreferences, onBack: () -> Unit) {
    val ink = LocalInk.current
    val context = LocalContext.current
    val client = remember { WishesClient(WishesClient.storedVoter(prefs)) }
    val scope = rememberCoroutineScope()
    var sort by remember { mutableStateOf(WishesClient.Sort.TOP) }
    val wishes = remember { mutableStateListOf<Wish>() }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun text(f: WishesClient.Failure) = L10n.t(when (f) {
        WishesClient.Failure.TOO_SHORT -> "Please describe the wish in at least 6 characters."
        WishesClient.Failure.LINKS -> "At most two links, please."
        WishesClient.Failure.EXISTS -> "This wish already exists – just vote for it."
        WishesClient.Failure.SLOW_DOWN -> "Too many requests. Please wait a moment."
        WishesClient.Failure.UNAVAILABLE -> "The wish list is not reachable right now. Please try again later."
    })
    suspend fun load() {
        try { val l = client.list(sort); wishes.clear(); wishes.addAll(l); failed = false } catch (e: WishesClient.Error) { failed = true }
        loaded = true
    }
    LaunchedEffect(sort) { load() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader(L10n.t("Suggest features and vote"), onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            item {
                Text(L10n.t("Suggest a feature"), color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = ink.surface) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), placeholder = { Text(L10n.t("What should Publit do?")) }, singleLine = true)
                        OutlinedTextField(details, { details = it }, Modifier.fillMaxWidth(), placeholder = { Text(L10n.t("Details (optional)")) }, minLines = 2, maxLines = 6)
                        Button({
                            sending = true
                            scope.launch {
                                try { client.suggest(title, details); title = ""; details = ""; message = L10n.t("Thanks! Your wish is in.") to false; load() }
                                catch (e: WishesClient.Error) { message = text(e.failure) to true }
                                sending = false
                            }
                        }, Modifier.fillMaxWidth(), enabled = !sending && title.trim().length >= WishesClient.MIN_TITLE,
                            colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) { Text(L10n.t("Send wish")) }
                        message?.let { (m, err) -> Text(m, color = if (err) ink.delay else ink.muted, fontSize = 14.sp) }
                    }
                }
                Text(L10n.t("Check the list first: if your idea is there, vote for it instead."), color = ink.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    listOf(WishesClient.Sort.TOP to L10n.t("Most votes"), WishesClient.Sort.NEW to L10n.t("Newest")).forEachIndexed { i, (s, t) ->
                        SegmentedButton(sort == s, { sort = s }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(t) }
                    }
                }
            }
            when {
                !loaded -> item { Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = ink.ink) } }
                failed -> item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(L10n.t("Wishes not reachable"), fontWeight = FontWeight.SemiBold)
                        Text(L10n.t("The wish list is not reachable right now. Please try again later."), color = ink.muted, textAlign = TextAlign.Center)
                        TextButton({ scope.launch { load() } }) { Text(L10n.t("Try again"), color = ink.ink) }
                    }
                }
                else -> items(wishes, key = { it.id }) { w ->
                    Surface(shape = RoundedCornerShape(14.dp), color = ink.surface) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.widthIn(min = 52.dp).background(if (w.mine) ink.ink else ink.fill, RoundedCornerShape(10.dp))
                                .clickable {
                                    scope.launch {
                                        try {
                                            val (votes, mine) = client.toggleVote(w.id)
                                            val i = wishes.indexOfFirst { it.id == w.id }
                                            if (i >= 0) wishes[i] = w.copy(votes = votes, mine = mine)
                                        } catch (e: WishesClient.Error) { message = text(e.failure) to true }
                                    }
                                }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.KeyboardArrowUp, L10n.f("Vote for %@", w.title), tint = if (w.mine) ink.onInk else ink.ink)
                                Text("${w.votes}", fontWeight = FontWeight.Bold, color = if (w.mine) ink.onInk else ink.ink)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(w.title, fontWeight = FontWeight.SemiBold)
                                if (w.body.isNotBlank()) Text(w.body, color = ink.muted, fontSize = 14.sp, maxLines = 4)
                                when (w.status) {
                                    Wish.Status.PLANNED -> L10n.t("Planned"); Wish.Status.PROGRESS -> L10n.t("In progress")
                                    Wish.Status.DONE -> L10n.t("Done"); else -> null
                                }?.let { Text(it, color = ink.live, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
            }
            item {
                TextButton({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WishesClient.PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    Modifier.fillMaxWidth()) { Text(L10n.t("Also on publit.eu"), color = ink.ink) }
            }
        }
    }
}

/** Support Publit (SupportView on iOS): monthly first, one-time below. Unlocks nothing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportScreen(onBack: () -> Unit) {
    val ink = LocalInk.current
    val context = LocalContext.current
    val state by SupportBilling.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { SupportBilling.load(context) }
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    @Composable
    fun Amount(o: SupportBilling.Offer) {
        val suggested = o.monthly && Support.parse(o.id)?.second == Support.SUGGESTED_MONTHLY_CENTS && state.activeMonthly == null
        val active = state.activeMonthly == o.id
        val strong = suggested || active
        Surface(Modifier.widthIn(min = if (o.monthly) 100.dp else 84.dp).heightIn(min = if (o.monthly) 58.dp else 46.dp)
            .clickable(enabled = state.purchasing == null && !active) { (context as? Activity)?.let { SupportBilling.buy(it, o) } },
            shape = RoundedCornerShape(12.dp), color = if (strong) ink.ink else ink.fill) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (state.purchasing == o.id) CircularProgressIndicator(Modifier.size(18.dp), color = if (strong) ink.onInk else ink.ink, strokeWidth = 2.dp)
                else Text(o.price, fontWeight = FontWeight.Bold, fontSize = if (o.monthly) 17.sp else 15.sp, color = if (strong) ink.onInk else ink.ink, maxLines = 1)
                if (o.monthly) Text(L10n.t(if (active) "Active" else "per month"), fontSize = 11.sp, color = if (strong) ink.onInk else ink.muted)
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader(L10n.t("Support Publit"), onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            item {
                Surface(shape = RoundedCornerShape(14.dp), color = ink.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Favorite, null, Modifier.size(36.dp), tint = Signature)
                        Text(L10n.t("Support Publit"), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text(L10n.t("Publit has no ads and no tracking. Your support pays for servers and keeps development going."), color = ink.muted, fontSize = 14.sp)
                        if (state.thanked || state.activeMonthly != null) {
                            val active = state.monthly.firstOrNull { it.id == state.activeMonthly }
                            Text(active?.let { L10n.f("Thank you! You support Publit with %@ a month.", it.price) } ?: L10n.t("Thank you for supporting Publit!"),
                                fontWeight = FontWeight.SemiBold)
                            if (active != null) TextButton({ open("https://play.google.com/store/account/subscriptions?sku=${active.id}&package=${context.packageName}") }) {
                                Text(L10n.t("Manage subscription"), color = ink.ink)
                            }
                        }
                    }
                }
            }
            when {
                !state.loaded -> item { Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = ink.ink) } }
                // Build without Google services: no store purchases, support goes through publit.eu.
                SupportBilling.webOnly -> item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton({ open("https://publit.eu/unterstuetzen/") }) { Text(L10n.t("Also on publit.eu"), color = ink.ink, fontWeight = FontWeight.SemiBold) }
                    }
                }
                state.monthly.isEmpty() && state.oneTime.isEmpty() -> item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(L10n.t("Support is not available right now."), color = ink.muted)
                        TextButton({ SupportBilling.load(context) }) { Text(L10n.t("Try again"), color = ink.ink) }
                    }
                }
                else -> {
                    if (state.monthly.isNotEmpty()) item {
                        Text(L10n.t("Monthly"), color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { state.monthly.forEach { Amount(it) } }
                        Text(L10n.t("Monthly support renews every month until you cancel it. Cancel it any time in Google Play."), color = ink.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
                    }
                    if (state.oneTime.isNotEmpty()) item {
                        Text(L10n.t("One-time"), color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { state.oneTime.forEach { Amount(it) } }
                    }
                }
            }
            item {
                Text(L10n.t("Support unlocks nothing: every feature stays free for everyone."), color = ink.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                TextButton({ open("https://publit.eu/privacy/") }) { Text(L10n.t("Privacy policy"), color = ink.ink) }
            }
        }
    }
}
