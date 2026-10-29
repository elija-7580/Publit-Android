package io.github.elija7580.publit.ui

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.tickets.PassArchive
import io.github.elija7580.publit.tickets.PassFormat
import io.github.elija7580.publit.tickets.PassStore
import io.github.elija7580.publit.tickets.PassStyle
import io.github.elija7580.publit.tickets.TicketField
import io.github.elija7580.publit.tickets.TicketImportPolicy
import io.github.elija7580.publit.tickets.TicketPass
import io.github.elija7580.publit.tickets.TicketRecognizer
import io.github.elija7580.publit.tickets.TicketScan
import io.github.elija7580.publit.tickets.TicketTextParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The Tickets tab (PassWalletView on iOS): valid now, upcoming, without dates, expired; older ones in the archive. */
@Composable
fun PassWalletScreen(store: PassStore, onOpen: (String) -> Unit, onAdd: () -> Unit, onArchive: () -> Unit, onSettings: () -> Unit) {
    val ink = LocalInk.current
    val passes by store.passes.collectAsStateWithLifecycle()
    val now = Instant.now()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L10n.t("Tickets"), fontWeight = FontWeight.Bold, fontSize = 32.sp, modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp))
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, L10n.t("Settings")) }
                IconButton(onClick = onAdd, modifier = Modifier.padding(end = 8.dp)) { Icon(Icons.Filled.Add, L10n.t("Add ticket")) }
            }
        }
        if (passes.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.ConfirmationNumber, null, Modifier.size(40.dp), tint = ink.muted)
                Text(L10n.t("No tickets yet"), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(L10n.t("Add a ticket from a PDF, a photo or a screenshot. Publit reads the details on your iPhone and keeps the code ready for the gate.").replace("iPhone", "Android"),
                    color = ink.muted, textAlign = TextAlign.Center)
                Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) { Text(L10n.t("Add ticket")) }
            }
        } else {
            val groups = PassGroup_grouped(passes, now)
            if (groups.isEmpty()) item { Text(L10n.t("No current tickets"), color = ink.muted, modifier = Modifier.padding(horizontal = 16.dp)) }
            groups.forEach { (g, list) ->
                item("h" + g.name) { Text(g.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                list.forEach { p -> item(p.id) { PassCardItem(store, p, now, onOpen) } }
            }
            val archived = store.archived(now).size
            if (archived > 0) item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface, onClick = onArchive) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Archive, null); Spacer(Modifier.width(10.dp))
                        Text(L10n.t("Archive"), modifier = Modifier.weight(1f)); Text("$archived", color = ink.muted)
                    }
                }
            }
        }
    }
}

private fun PassGroup_grouped(passes: List<TicketPass>, now: Instant) = io.github.elija7580.publit.tickets.PassGroup.grouped(passes, now)

/** A compact card that opens the pass; long press moves it to the archive or back. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PassCardItem(store: PassStore, p: TicketPass, now: Instant, onOpen: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp)) {
        Box(Modifier.combinedClickable(onClick = { onOpen(p.id) }, onLongClick = { menu = true })) { PassCard(p, compact = true, now = now) }
        DropdownMenu(menu, { menu = false }) {
            if (p.isArchived(now)) DropdownMenuItem({ Text(L10n.t("Move back to tickets")) }, { menu = false; store.unarchive(p.id) })
            else DropdownMenuItem({ Text(L10n.t("Move to archive")) }, { menu = false; store.archive(p.id) })
        }
    }
}

/** A ticket as a pass (PassCardView on iOS): operator and title, route, details, perforation and code. */
@Composable
fun PassCard(p: TicketPass, compact: Boolean, now: Instant = Instant.now(), code: (@Composable () -> Unit)? = null) {
    val bg = hexColor(p.style.background); val fg = hexColor(p.style.foreground); val label = hexColor(p.style.label)
    val expired = p.validity(now) is TicketPass.Validity.Expired
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).then(if (compact && expired) Modifier.background(Color.Black.copy(alpha = 0.25f)) else Modifier)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(fg.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (p.symbol == TicketPass.Symbol.TICKET) Icons.Filled.ConfirmationNumber else modeIcon(p.symbol.mode), null, tint = fg, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    p.operatorName?.let { Text(it.uppercase(), color = label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                    Text(p.displayTitle, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                }
                ValidityBadge(p.validity(now), fg)
            }
            if (p.hasRoute) Row(verticalAlignment = Alignment.Top) {
                Field(L10n.t("From"), p.origin!!, label, fg, true, Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = label, modifier = Modifier.padding(top = 20.dp, start = 6.dp, end = 6.dp))
                Field(L10n.t("To"), p.destination!!, label, fg, true, Modifier.weight(1f))
            } else if (!compact) Field(L10n.t("Type"), p.category.title, label, fg, true)
            if (!compact) {
                val fields = buildList {
                    if (p.hasRoute && !p.via.isNullOrEmpty()) add(L10n.t("Via") to p.via)
                    p.validFrom?.let { add(L10n.t("Valid from") to PassFormat.date(it, false)) }
                    p.validUntil?.let { add(L10n.t("Valid until") to PassFormat.date(it, true)) }
                    p.passenger?.let { add(L10n.t("Passenger") to it) }
                    p.travelClassText?.let { add(L10n.t("Class") to it) }
                    p.price?.let { add(L10n.t("Price") to it) }
                }
                fields.chunked(2).forEach { row ->
                    Row { row.forEach { (t, v) -> Field(t, v, label, fg, false, Modifier.weight(1f)) }; if (row.size == 1) Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (!compact && code != null) {
            HorizontalDivider(color = label.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 20.dp))
            Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) { code() }
        }
    }
}

@Composable
private fun Field(title: String, value: String, label: Color, fg: Color, big: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title.uppercase(), color = label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = fg, fontSize = if (big) 20.sp else 15.sp, fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ValidityBadge(v: TicketPass.Validity, fg: Color) {
    val (text, icon) = when (v) {
        is TicketPass.Validity.Valid -> L10n.t("Valid now") to Icons.Filled.CheckCircle
        is TicketPass.Validity.Upcoming -> L10n.f("From %@", PassFormat.date(v.from, false)) to Icons.Filled.Schedule
        is TicketPass.Validity.Expired -> L10n.t("Expired") to Icons.Filled.Cancel
        TicketPass.Validity.Unknown -> return
    }
    Row(Modifier.background(fg.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(13.dp), tint = fg); Spacer(Modifier.width(4.dp)); Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The code: drawn again when possible, otherwise cut out of the original exactly as issued. */
@Composable
fun PassCode(store: PassStore, p: TicketPass, onTap: (() -> Unit)? = null, big: Boolean = false) {
    val code = p.barcode
    val bmp by produceState<Bitmap?>(null, p.id, p.barcode, big) {
        value = withContext(Dispatchers.Default) {
            if (code == null) null
            else if (code.format.canRedraw) TicketRecognizer.renderCode(code, if (big) 1200 else 800, if (code.format.isSquare) (if (big) 1200 else 800) else 400)
            else {
                val doc = p.document; val f = store.documentFile(p); val r = code.region
                if (doc != null && f != null && r != null) TicketRecognizer.crop(f, doc.kind == TicketPass.Document.Kind.PDF, doc.page, r.padded()) else null
            }
        }
    }
    val fg = hexColor(p.style.foreground); val label = hexColor(p.style.label)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            code == null -> Text(if (p.document != null) L10n.t("No code found. Show the original instead.") else L10n.t("No code"), color = fg, fontSize = 14.sp)
            bmp != null -> Box(Modifier.background(Color.White, RoundedCornerShape(10.dp)).padding(12.dp).then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)) {
                Image(bmp!!.asImageBitmap(), L10n.f("%@ code", code.format.title),
                    Modifier.fillMaxWidth(if (code.format.isSquare) 0.75f else 1f).heightIn(max = if (big) 600.dp else 260.dp), contentScale = ContentScale.Fit,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
            }
            else -> CircularProgressIndicator(color = fg)
        }
        code?.message?.takeIf { code.format.isLinear }?.let { Text(it, color = label, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
    }
}

/** One pass with its code, details, original and actions (PassDetailView on iOS). Screen at full brightness for the gate. */
@Composable
fun PassDetailScreen(store: PassStore, id: String, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val ink = LocalInk.current
    val passes by store.passes.collectAsStateWithLifecycle()
    val p = passes.firstOrNull { it.id == id }
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val w = activity?.window; val old = w?.attributes?.screenBrightness
        w?.attributes = w?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        onDispose { w?.attributes = w?.attributes?.apply { screenBrightness = old ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
    }
    var fullCode by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (p == null) { LaunchedEffect(Unit) { onBack() }; return }
    if (fullCode) {
        BackHandler { fullCode = false }
        Box(Modifier.fillMaxSize().background(Color.White).clickable { fullCode = false }.padding(16.dp), contentAlignment = Alignment.Center) { PassCode(store, p.copy(style = PassStyle("FFFFFF")), big = true) }
        return
    }
    if (original) { BackHandler { original = false }; OriginalView(store, p) { original = false }; return }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onEdit(p.id) }) { Icon(Icons.Filled.Edit, L10n.t("Edit")) }
            IconButton(onClick = { if (p.isArchived()) store.unarchive(p.id) else store.archive(p.id) }) { Icon(Icons.Filled.Archive, L10n.t(if (p.isArchived()) "Move back to tickets" else "Move to archive")) }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, L10n.t("Delete")) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PassCard(p, compact = false) { PassCode(store, p, onTap = { fullCode = true }) }
            if (p.document != null) OutlinedSurfaceButton(L10n.t("Show original")) { original = true }
            p.reference?.let { InfoRow(L10n.t("Ticket number"), it) }
            p.notes?.takeIf { it.isNotBlank() }?.let { InfoRow(L10n.t("Notes"), it) }
            Text(L10n.t("A copy of your ticket for quick access. Only the ticket the operator issued is valid. Inspectors may ask for it or for the operator's app."),
                color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(L10n.t("Delete this ticket?")) },
        text = { Text(L10n.t("The copy and its original are removed from this iPhone.").replace("iPhone", "Android")) },
        confirmButton = { TextButton({ confirmDelete = false; store.remove(p.id); onBack() }) { Text(L10n.t("Delete ticket"), color = ink.danger) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(L10n.t("Cancel")) } })
}

@Composable
private fun OutlinedSurfaceButton(title: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = LocalInk.current.surface, modifier = Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    val ink = LocalInk.current
    Surface(shape = RoundedCornerShape(14.dp), color = ink.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { Text(title, color = ink.muted, fontSize = 12.sp); Text(value, fontSize = 15.sp) }
    }
}

/** The original ticket page, zoomable by the system's pinch on the image. */
@Composable
private fun OriginalView(store: PassStore, p: TicketPass, onClose: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, p.id) {
        value = withContext(Dispatchers.Default) {
            val d = p.document ?: return@withContext null
            store.documentFile(p)?.let { TicketRecognizer.page(it, d.kind == TicketPass.Document.Kind.PDF, d.page, 2600) }
        }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(Modifier.fillMaxSize().background(Color.White)) {
        bmp?.let {
            Image(it.asImageBitmap(), L10n.t("Original ticket"), Modifier.fillMaxSize()
                .pointerInputTransform { z, pan -> scale = (scale * z).coerceIn(1f, 6f); offset += pan }
                .graphicsLayerScale(scale, offset), contentScale = ContentScale.Fit)
        } ?: Text(L10n.t("Ticket image unavailable"), Modifier.align(Alignment.Center), color = Color.Black)
        TextButton(onClose, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) { Text(L10n.t("Done"), color = Color.Black, fontWeight = FontWeight.SemiBold) }
    }
}

private fun Modifier.pointerInputTransform(onTransform: (Float, androidx.compose.ui.geometry.Offset) -> Unit) =
    this.then(Modifier.pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> onTransform(zoom, pan) } })

private fun Modifier.graphicsLayerScale(scale: Float, offset: androidx.compose.ui.geometry.Offset) =
    this.then(Modifier.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))

/** Expired and archived tickets, newest first, with search (PassArchiveView on iOS). */
@Composable
fun PassArchiveScreen(store: PassStore, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ink = LocalInk.current
    val passes by store.passes.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val now = Instant.now()
    val list = remember(passes, query) { store.archived(now).filter { PassArchive.matches(it, query) } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
            Text(L10n.t("Archive"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (store.archived(now).isNotEmpty()) IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, L10n.t("Delete")) }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, placeholder = { Text(L10n.t("Search")) })
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { p -> Box(Modifier.clickable { onOpen(p.id) }) { PassCard(p, compact = true, now = now) } }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(L10n.t("Delete")) },
        confirmButton = { TextButton({ confirm = false; store.removeArchived() }) { Text(L10n.t("Delete"), color = ink.danger) } },
        dismissButton = { TextButton({ confirm = false }) { Text(L10n.t("Cancel")) } })
}

/** A ticket being added: the read pass, its original and the recognised lines for field assignment. */
class PendingImport(val pass: TicketPass, val bytes: ByteArray?, val kind: TicketPass.Document.Kind?, val page: Int, val scan: TicketScan?)

/** Adding a ticket (PassImportView on iOS): a PDF or image file, a photo, or a scan of a paper ticket. */
@Composable
fun PassImportScreen(store: PassStore, tripId: String?, onBack: () -> Unit, onRead: (PendingImport) -> Unit) {
    val ink = LocalInk.current
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf<Pair<File, Int>?>(null) }
    var loaded by remember { mutableStateOf<ByteArray?>(null) }
    var job by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var pickPage by remember { mutableStateOf<Int?>(null) }

    fun finish(bytes: ByteArray, pdf: Boolean, page: Int, s: TicketScan) {
        val draft = TicketTextParser.parse(s.lines, s.barcodes, strictValidity = pdf)
        onRead(PendingImport(io.github.elija7580.publit.tickets.TicketPass.of(draft, s.barcodes.firstOrNull()).copy(tripId = tripId), bytes,
            if (pdf) TicketPass.Document.Kind.PDF else TicketPass.Document.Kind.IMAGE, page, s))
    }

    LaunchedEffect(job, pickPage) {
        val (uri, pdfHint) = job ?: return@LaunchedEffect
        busy = true; error = null
        try {
            val bytes = loaded ?: withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: throw TicketRecognizer.Unreadable()
            if (bytes.size > PassStore.MAX_DOCUMENT_BYTES) throw PassStore.TooLarge()
            loaded = bytes
            val pdf = pdfHint || (bytes.size > 4 && String(bytes, 0, 4) == "%PDF")
            val tmp = File(ctx.cacheDir, "import." + if (pdf) "pdf" else "img").apply { writeBytes(bytes) }
            if (!pdf) { finish(bytes, false, 0, TicketRecognizer.scan(tmp, false, 0)); return@LaunchedEffect }
            val count = TicketRecognizer.pageCount(tmp)
            if (count == 0) throw TicketRecognizer.Unreadable()
            val chosen = pickPage
            when {
                chosen != null -> finish(bytes, true, chosen, TicketRecognizer.scan(tmp, true, chosen))
                count == 1 -> finish(bytes, true, 0, TicketRecognizer.scan(tmp, true, 0))
                else -> {
                    val unique = TicketRecognizer.uniqueTicket(tmp, count)
                    if (unique != null) finish(bytes, true, unique.first, unique.second) else { pages = tmp to count; busy = false }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            error = e.message ?: L10n.t("This file could not be read."); busy = false; job = null; loaded = null
        }
    }

    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { loaded = null; pickPage = null; job = it to (ctx.contentResolver.getType(it) == "application/pdf") } }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { loaded = null; pickPage = null; job = it to false } }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pdf?.uri?.let { loaded = null; pickPage = null; job = it to true }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Cancel")) }
            Text(L10n.t("Add ticket"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        val pg = pages
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                busy -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = ink.ink); Text(L10n.t("Reading ticket…"), color = ink.muted)
                }
                pg != null -> {
                    Text(L10n.t("Which page is the ticket?"), fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items((0 until pg.second).toList()) { i ->
                            val thumb by produceState<Bitmap?>(null, i) { value = withContext(Dispatchers.Default) { TicketRecognizer.renderPage(pg.first, i, 600) } }
                            Column(Modifier.clickable { pages = null; pickPage = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(140.dp, 200.dp).border(1.dp, ink.hairline).background(Color.White)) {
                                    thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                                }
                                Text("${i + 1}", color = ink.muted)
                            }
                        }
                    }
                }
                else -> {
                    ImportOption(Icons.Filled.Description, L10n.t("PDF or image file")) { file.launch(arrayOf("application/pdf", "image/*")) }
                    ImportOption(Icons.Filled.Image, L10n.t("Photo or screenshot")) { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    ImportOption(Icons.Filled.DocumentScanner, L10n.t("Scan a paper ticket")) {
                        val activity = ctx as? Activity ?: return@ImportOption
                        val opts = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(false).setPageLimit(1)
                            .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_BASE).build()
                        com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(opts).getStartScanIntent(activity)
                            .addOnSuccessListener { scanner.launch(androidx.activity.result.IntentSenderRequest.Builder(it).build()) }
                            .addOnFailureListener { e -> error = e.localizedMessage }
                    }
                    error?.let { Text(it, color = ink.danger) }
                    Text(L10n.t("Publit reads the ticket on your iPhone. Nothing is uploaded. Codes that change every few seconds only work in the operator's app.").replace("iPhone", "Android"),
                        color = ink.muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ImportOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = LocalInk.current.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(title, fontSize = 16.sp) }
    }
}

private val editDate = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private fun fmt(i: Instant?) = i?.atZone(ZoneId.systemDefault())?.format(editDate) ?: ""

/** Checking and editing a ticket (PassEditorView on iOS); for a new one, recognised lines can be assigned to fields. */
@Composable
fun PassEditorScreen(store: PassStore, initial: PendingImport, isNew: Boolean, onCancel: () -> Unit, onSaved: (TicketPass) -> Unit) {
    val ink = LocalInk.current
    var p by remember { mutableStateOf(initial.pass) }
    var from by remember { mutableStateOf(fmt(p.validFrom)) }
    var until by remember { mutableStateOf(fmt(p.validUntil)) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onCancel) { Text(L10n.t("Cancel"), color = ink.ink) }
            Text(L10n.t(if (isNew) "Add ticket" else "Edit ticket"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            TextButton({
                val vf = if (from.isBlank()) null else TicketTextParser.date(from, false)
                val vu = if (until.isBlank()) null else TicketTextParser.date(until, true)
                if (vf != null && vu != null && vu < vf) { error = L10n.t("The end date must not be before the start date."); return@TextButton }
                val final = p.copy(validFrom = vf, validUntil = vu)
                try {
                    val saved = if (isNew) {
                        val before = store.passes.value.size
                        val s = store.add(final, initial.bytes, initial.kind, initial.page, deduplicate = true)
                        if (store.passes.value.size == before && s.id != final.id) { error = L10n.t("Ticket already saved"); return@TextButton }
                        s
                    } else { store.update(final); final }
                    onSaved(saved)
                } catch (e: Exception) { error = e.message ?: L10n.t("Ticket could not be saved") }
            }) { Text(L10n.t("Save"), fontWeight = FontWeight.SemiBold, color = ink.ink) }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isNew) item { Text(L10n.t("Read on your iPhone. Check every detail before you save.").replace("iPhone", "Android"), color = ink.muted, fontSize = 13.sp) }
            error?.let { item { Text(it, color = ink.danger) } }
            item { PassCard(p.copy(validFrom = TicketTextParser.date(from, false), validUntil = TicketTextParser.date(until, true)), compact = true) }
            val lines = initial.scan?.lines.orEmpty()
            if (lines.isNotEmpty()) item {
                Group(L10n.t("Assign fields on the ticket")) {
                    lines.take(40).forEach { line ->
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Text(line, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clickable { menu = true }.padding(horizontal = 16.dp, vertical = 8.dp))
                            DropdownMenu(menu, { menu = false }) {
                                TicketField.entries.forEach { f -> DropdownMenuItem({ Text(f.title) }, {
                                    menu = false
                                    f.apply(line, p)?.let { np -> p = np; if (f == TicketField.VALID_FROM) from = fmt(np.validFrom); if (f == TicketField.VALID_UNTIL) until = fmt(np.validUntil) }
                                }) }
                            }
                        }
                    }
                }
            }
            item {
                Group(L10n.t("Ticket")) {
                    Edit(L10n.t("Title"), p.title) { p = p.copy(title = it) }
                    Edit(L10n.t("Operator"), p.operatorName.orEmpty()) { p = p.copy(operatorName = it.ifBlank { null }) }
                    Choice(L10n.t("Type"), p.category.title, TicketPass.Category.entries.map { it.title to it }) { p = p.copy(category = it) }
                    Choice(L10n.t("Symbol"), p.symbol.title, TicketPass.Symbol.entries.map { it.title to it }) { p = p.copy(symbol = it) }
                }
            }
            item {
                Group(L10n.t("Route")) {
                    Edit(L10n.t("From"), p.origin.orEmpty()) { p = p.copy(origin = it.ifBlank { null }) }
                    Edit(L10n.t("To"), p.destination.orEmpty()) { p = p.copy(destination = it.ifBlank { null }) }
                    Edit(L10n.t("Via"), p.via.orEmpty()) { p = p.copy(via = it.ifBlank { null }) }
                }
            }
            item {
                Group(L10n.t("Validity")) {
                    Edit(L10n.t("Valid from"), from, "dd.MM.yyyy HH:mm") { from = it }
                    Edit(L10n.t("Valid until"), until, "dd.MM.yyyy HH:mm") { until = it }
                }
            }
            item {
                Group(L10n.t("Details")) {
                    Edit(L10n.t("Passenger"), p.passenger.orEmpty()) { p = p.copy(passenger = it.ifBlank { null }) }
                    Choice(L10n.t("Class"), p.travelClassText ?: "–", listOf("–" to null, L10n.f("Class %@", "1") to "1", L10n.f("Class %@", "2") to "2")) { p = p.copy(travelClass = it) }
                    Edit(L10n.t("Price"), p.price.orEmpty()) { p = p.copy(price = it.ifBlank { null }) }
                    Edit(L10n.t("Ticket number"), p.reference.orEmpty()) { p = p.copy(reference = it.ifBlank { null }) }
                    Edit(L10n.t("Notes"), p.notes.orEmpty()) { p = p.copy(notes = it.ifBlank { null }) }
                }
            }
            item {
                Group(L10n.t("Look")) {
                    LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val presets = PassStyle.presets.map { it.first } + listOfNotNull(initial.scan?.suggestedColor)
                        items(presets.distinct()) { hex ->
                            Box(Modifier.size(36.dp).clip(CircleShape).background(hexColor(hex))
                                .border(if (p.style.background == hex) 3.dp else 1.dp, if (p.style.background == hex) ink.ink else ink.hairline, CircleShape)
                                .clickable { p = p.copy(style = PassStyle(hex)) })
                        }
                    }
                }
            }
            item {
                Group(L10n.t("Code")) {
                    val c = p.barcode
                    Text(if (c == null) (if (p.document != null) L10n.t("No code found on this page. Publit shows the original instead.") else L10n.t("No code"))
                        else c.format.title + if (!c.format.canRedraw) " · " + L10n.t("This code is shown exactly as on the original ticket.") else "",
                        fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                    val codes = initial.scan?.barcodes.orEmpty()
                    if (codes.size > 1) codes.forEach { b ->
                        Text(b.format.title + " · " + (b.message?.take(24) ?: "…"), fontSize = 14.sp, fontWeight = if (b == c) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { p = p.copy(barcode = b) }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    if (c != null) TextButton({ p = p.copy(barcode = null) }) { Text(L10n.t("Remove code"), color = ink.danger) }
                    Text(L10n.t("Only codes that stay the same work here. Codes that change every few seconds only work in the operator's app."),
                        color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val ink = LocalInk.current
    Column {
        Text(title, color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = ink.surface, modifier = Modifier.fillMaxWidth()) { Column { content() } }
    }
}

@Composable
private fun Edit(label: String, value: String, placeholder: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } }, singleLine = true)
}

@Composable
private fun <T> Choice(label: String, current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(16.dp)) {
            Text(label, modifier = Modifier.weight(1f)); Text(current, color = LocalInk.current.muted)
        }
        DropdownMenu(open, { open = false }) { options.forEach { (t, v) -> DropdownMenuItem({ Text(t) }, { open = false; onPick(v) }) } }
    }
}

/** Tickets for a trip (TripPassesView on iOS): its own tickets, then passes valid at its time. */
@Composable
fun TripPasses(store: PassStore, tripId: String?, at: Instant, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val ink = LocalInk.current
    val all by store.passes.collectAsStateWithLifecycle()
    val list = remember(all, tripId, at) { store.forTrip(tripId, at) }
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L10n.t("Tickets"), color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp))
        if (list.isEmpty()) Text(L10n.t("No ticket for this trip"), color = ink.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp))
        list.forEach { p -> Box(Modifier.clickable { onOpen(p.id) }) { PassCard(p, compact = true) } }
        Surface(onClick = onAdd, shape = RoundedCornerShape(14.dp), color = ink.surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(10.dp)); Text(L10n.t("Add ticket for this trip"))
            }
        }
        Text(L10n.t("Add the ticket from a PDF, a photo or a screenshot. Day, weekly and annual passes show up for every trip while they are valid."),
            color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
    }
}
