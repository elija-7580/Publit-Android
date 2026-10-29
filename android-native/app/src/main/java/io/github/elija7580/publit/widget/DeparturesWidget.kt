package io.github.elija7580.publit.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer as UiSpacer
import androidx.compose.foundation.layout.Column as UiColumn
import androidx.compose.foundation.layout.fillMaxSize as uiFillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth as uiFillMaxWidth
import androidx.compose.foundation.layout.height as uiHeight
import androidx.compose.foundation.layout.padding as uiPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as UiText
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable as glanceClickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n

// The departures widget (the iOS `DeparturesWidget`): next departures at a stop of your choice.
// Departures come from the app's own client, so lines, colours and times match the app.
class DeparturesWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val stop = DroidWidgets.stop(context, widgetId)
        val texts = DroidWidgets.texts()
        val data = stop?.let { DroidWidgets.departures(context, it.optString("stopId")) }
        provideContent { WidgetContent(context, widgetId, stop, data, texts) }
    }

    companion object {
        suspend fun refreshAll(context: Context) = DeparturesWidget().updateAll(context)
    }
}

class DeparturesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DeparturesWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        DroidWidgets.remove(context, appWidgetIds)
    }
}

object DroidWidgets {
    private fun prefs(context: Context) = context.getSharedPreferences("publit.widgets", Context.MODE_PRIVATE)
    private fun app(context: Context) = context.applicationContext as io.github.elija7580.publit.PublitApplication

    fun stop(context: Context, widgetId: Int): JSONObject? =
        prefs(context).getString("stop.$widgetId", null)?.let { try { JSONObject(it) } catch (_: Throwable) { null } }

    fun save(context: Context, widgetId: Int, stop: JSONObject) {
        prefs(context).edit().putString("stop.$widgetId", stop.toString()).apply()
    }

    fun remove(context: Context, widgetIds: IntArray) {
        val edit = prefs(context).edit()
        for (id in widgetIds) edit.remove("stop.$id")
        edit.apply()
    }

    /** Next departures as rows: line, colours, direction, time text. */
    suspend fun departures(context: Context, stopId: String): JSONObject? = try {
        val client = app(context).client
        val list = io.github.elija7580.publit.data.StopTime.upcoming(client.departures(stopId, 20, 150).stopTimes)
        val now = java.time.Instant.now()
        val rows = JSONArray()
        for (st in list.take(12)) {
            val m = Math.ceil((st.departureTime.epochSecond - now.epochSecond) / 60.0).toInt()
            val text = when { m <= 0 -> L10n.t("now"); m < 60 -> L10n.f("%lld min", m); else -> Fmt.time(st.departureTime, st.place.tz) }
            val c = st.lineColors
            rows.put(JSONObject().put("line", st.lineName).put("background", c.background).put("foreground", c.text)
                .put("headsign", st.shownDirection ?: "").put("circular", st.isCircular).put("cancelled", st.isCancelled)
                .put("time", st.departureTime.toEpochMilli()).put("timeText", text))
        }
        JSONObject().put("ok", true).put("rows", rows)
    } catch (e: Throwable) { android.util.Log.e("Publit", "widget departures failed", e); null }

    /** Saved stops for an empty query, otherwise stops from the search. */
    suspend fun stops(context: Context, query: String): List<JSONObject> = try {
        if (query.isBlank()) app(context).favorites.state.value.stops.map { JSONObject().put("name", it.name).put("stopId", it.stopId ?: it.id) }
        else app(context).client.geocode(query.trim(), null, L10n.locale.language.ifEmpty { "en" }).filter { it.isStop }
            .map { JSONObject().put("name", it.name).put("stopId", it.id) }
    } catch (e: Throwable) { emptyList() }

    fun texts(): JSONObject = JSONObject().put("chooseStop", L10n.t("Choose a stop"))
        .put("description", L10n.t("Next departures at a stop of your choice.")).put("unavailable", L10n.t("Departures unavailable"))
        .put("noneSoon", L10n.t("No departures soon")).put("search", L10n.t("Search")).put("saved", L10n.t("Saved"))

    fun configIntent(context: Context, widgetId: Int): Intent =
        Intent(context, DeparturesWidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .setData(Uri.parse("publit-widget://configure/$widgetId"))

    fun openIntent(context: Context, stop: JSONObject): Intent =
        Intent(context, io.github.elija7580.publit.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("stopId", stop.optString("stopId")).putExtra("stopName", stop.optString("name"))
            .setData(Uri.parse("publit-widget://stop/" + Uri.encode(stop.optString("stopId"))))

    /** "#RRGGBB" or "RRGGBB" from the line colours. */
    fun color(hex: String, fallback: Color): Color {
        val h = hex.trim().removePrefix("#")
        if (h.length != 6) return fallback
        return try { Color(0xFF000000 or h.toLong(16)) } catch (_: Throwable) { fallback }
    }
}

// The app's Ink & Dot colours (res/values and values-night of the app module, so light and dark follow the system).
private fun appColor(context: Context, name: String, fallback: Color): ColorProvider {
    val id = context.resources.getIdentifier(name, "color", context.packageName)
    return if (id != 0) ColorProvider(id) else ColorProvider(fallback)
}
private val danger = ColorProvider(Color(0xFFD7263D))
private var ink = ColorProvider(Color(0xFF111111))

@Composable
private fun WidgetContent(context: Context, widgetId: Int, stop: JSONObject?, data: JSONObject?, texts: JSONObject) {
    val size = LocalSize.current
    ink = appColor(context, "publit_ink", Color(0xFF111111))
    val ground = appColor(context, "publit_ground", Color(0xFFF4F4F2))
    val action: Action = if (stop == null) actionStartActivity(DroidWidgets.configIntent(context, widgetId))
        else actionStartActivity(DroidWidgets.openIntent(context, stop))
    Column(GlanceModifier.fillMaxSize().background(ground).cornerRadius(18.dp).padding(12.dp).glanceClickable(action)) {
        if (stop == null) {
            Message(texts.optString("chooseStop", "Choose a stop"), texts.optString("description", "Next departures at a stop of your choice."),
                appColor(context, "publit_signature", Color(0xFFFFC531)))
            return@Column
        }
        Text(stop.optString("name"), maxLines = 1,
            style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        Spacer(GlanceModifier.height(6.dp))
        val rows = data?.optJSONArray("rows")
        if (data == null || !data.optBoolean("ok") && (rows == null || rows.length() == 0)) {
            Text(texts.optString("unavailable", "Departures unavailable"), style = TextStyle(color = ink, fontSize = 13.sp))
            return@Column
        }
        val now = System.currentTimeMillis()
        val upcoming = (0 until (rows?.length() ?: 0)).map { rows!!.getJSONObject(it) }.filter { it.optDouble("time") > now - 30_000 }
        val narrow = size.width < 180.dp
        val rowHeight = if (narrow) 40 else 26
        val room = ((size.height.value - 44) / rowHeight).toInt().coerceIn(1, 8)
        if (upcoming.isEmpty()) {
            Text(texts.optString("noneSoon", "No departures soon"), style = TextStyle(color = ink, fontSize = 13.sp))
        }
        for (d in upcoming.take(room)) {
            DepartureRow(d, narrow)
            Spacer(GlanceModifier.height(if (narrow) 6.dp else 5.dp))
        }
        data.optString("savedData").takeIf { it.isNotEmpty() && it != "null" }?.let {
            Text(it, maxLines = 1, style = TextStyle(color = ink, fontSize = 10.sp))
        }
    }
}

@Composable
private fun Message(title: String, detail: String, dot: ColorProvider) {
    Column(GlanceModifier.fillMaxSize()) {
        Text("●", style = TextStyle(color = dot, fontSize = 18.sp))
        Spacer(GlanceModifier.height(4.dp))
        Text(title, maxLines = 2, style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        Text(detail, maxLines = 3, style = TextStyle(color = ink, fontSize = 12.sp))
    }
}

@Composable
private fun DepartureRow(d: JSONObject, narrow: Boolean) {
    val cancelled = d.optBoolean("cancelled")
    val time = @Composable {
        Text(d.optString("timeText"), maxLines = 1, style = TextStyle(
            color = if (cancelled) danger else ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            textDecoration = if (cancelled) TextDecoration.LineThrough else TextDecoration.None))
    }
    val headsign = (if (d.optBoolean("circular")) "↻ " else "") + d.optString("headsign")
    if (narrow) {
        Column(GlanceModifier.fillMaxWidth()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LineBadge(d)
                Spacer(GlanceModifier.defaultWeight())
                time()
            }
            Text(headsign, maxLines = 1, style = TextStyle(color = ink, fontSize = 11.sp))
        }
    } else {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LineBadge(d)
            Spacer(GlanceModifier.width(8.dp))
            Text(headsign, maxLines = 1, modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = ink, fontSize = 12.sp))
            Spacer(GlanceModifier.width(6.dp))
            time()
        }
    }
}

@Composable
private fun LineBadge(d: JSONObject) {
    val bg = DroidWidgets.color(d.optString("background"), Color(0xFF111111))
    val fg = DroidWidgets.color(d.optString("foreground"), Color.White)
    Box(GlanceModifier.background(bg).cornerRadius(4.dp).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(d.optString("line"), maxLines = 1, style = TextStyle(color = ColorProvider(fg), fontSize = 12.sp, fontWeight = FontWeight.Bold))
    }
}

// Choosing the widget's stop (the iOS widget's "Edit Widget"): saved stops, or a search.
class DeparturesWidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        enableEdgeToEdge()
        val texts = DroidWidgets.texts()
        setContent {
            val ink = Color(0xFF111111); val paper = Color(0xFFF4F4F2); val signature = Color(0xFFFFC531)
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = signature, background = Color.Black, surface = Color.Black)
                else lightColorScheme(primary = ink, background = paper, surface = paper, onSurface = ink)
            MaterialTheme(colorScheme = scheme) { StopPicker(this@DeparturesWidgetConfigActivity, texts) { pick(it) } }
        }
    }

    private fun pick(stop: JSONObject) {
        DroidWidgets.save(this, widgetId, stop)
        val context = applicationContext
        scope.launch {
            try {
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
                DeparturesWidget().update(context, glanceId)
            } catch (e: Throwable) {
                android.util.Log.e("Publit", "widget update failed", e)
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}

@Composable
private fun StopPicker(context: Context, texts: JSONObject, onPick: (JSONObject) -> Unit) {
    var query by remember { mutableStateOf("") }
    var stops by remember { mutableStateOf(listOf<JSONObject>()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(350)
        loading = true
        stops = DroidWidgets.stops(context, query)
        loading = false
    }
    Surface(Modifier.uiFillMaxSize()) {
        UiColumn(Modifier.uiFillMaxSize().safeDrawingPadding().uiPadding(16.dp)) {
            UiText(texts.optString("chooseStop", "Choose a stop"), style = MaterialTheme.typography.headlineSmall)
            UiSpacer(Modifier.uiHeight(12.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { UiText(texts.optString("search", "Search")) }, modifier = Modifier.uiFillMaxWidth())
            UiSpacer(Modifier.uiHeight(8.dp))
            if (loading) LinearProgressIndicator(Modifier.uiFillMaxWidth())
            if (query.isBlank() && stops.isNotEmpty()) {
                UiText(texts.optString("saved", "Saved"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.uiPadding(top = 8.dp))
            }
            LazyColumn(Modifier.uiFillMaxSize()) {
                items(stops) { s ->
                    UiText(s.optString("name"), fontSize = 17.sp, modifier = Modifier.uiFillMaxWidth().clickable { onPick(s) }.uiPadding(vertical = 14.dp))
                    HorizontalDivider()
                }
            }
        }
    }
}
