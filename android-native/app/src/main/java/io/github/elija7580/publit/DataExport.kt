package io.github.elija7580.publit

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Export my data" (DataExport on iOS): every saved thing (places, lines, trips, search history,
 * tickets without their images) as one JSON file through the share sheet. Diagnostics go the same way.
 */
object DataExport {
    fun share(ctx: Context) {
        val out = JSONObject().put("app", "Publit").put("exported", java.time.Instant.now().toString())
        val prefs = ctx.getSharedPreferences("publit", Context.MODE_PRIVATE)
        for ((k, v) in prefs.all) {
            val s = v as? String ?: continue
            if (!(s.startsWith("[") || s.startsWith("{"))) continue
            runCatching { out.put(k, if (s.startsWith("[")) JSONArray(s) else JSONObject(s)) }
        }
        ctx.filesDir.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { f ->
            runCatching { val t = f.readText(); out.put(f.relativeTo(ctx.filesDir).path, if (t.trimStart().startsWith("[")) JSONArray(t) else JSONObject(t)) }
        }
        send(ctx, "publit-data.json", out.toString(2), "application/json")
    }

    fun shareDiagnostics(ctx: Context) {
        val text = buildString {
            appendLine("Publit ${BuildConfigInfo.version} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}")
            appendLine(CrashLog.peek() ?: "No crashes recorded.")
        }
        send(ctx, "publit-diagnostics.txt", text, "text/plain")
    }

    private fun send(ctx: Context, name: String, text: String, type: String) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, name).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Which app opens walking directions ("Open directions in" on iOS). */
object DirectionsApp {
    val choices = listOf("google" to "Google Maps", "osmand" to "OsmAnd", "citymapper" to "Citymapper")
    fun current(ctx: Context) = ctx.getSharedPreferences("publit", Context.MODE_PRIVATE).getString("directionsApp", "google") ?: "google"
    fun open(ctx: Context, lat: Double, lon: Double, name: String) {
        val enc = android.net.Uri.encode(name)
        val url = when (current(ctx)) {
            "osmand" -> "osmand.navigation:q=$lat,$lon"
            "citymapper" -> "https://citymapper.com/directions?endcoord=$lat,$lon&endname=$enc"
            else -> "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=walking"
        }
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon($enc)")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
