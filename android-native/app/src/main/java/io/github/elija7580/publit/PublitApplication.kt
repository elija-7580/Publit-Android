package io.github.elija7580.publit

import android.app.Application
import android.text.format.DateFormat
import android.icu.text.PluralRules
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.TransitousClient
import io.github.elija7580.publit.location.LocationRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

class PublitApplication : Application() {
    lateinit var client: TransitousClient
    lateinit var location: LocationRepository
    lateinit var crashLog: CrashLog
    val passes by lazy { io.github.elija7580.publit.tickets.PassStore(filesDir) }
    val trips by lazy { io.github.elija7580.publit.data.TripStore(filesDir) }
    val favorites by lazy { io.github.elija7580.publit.data.FavoritesStore(getSharedPreferences("publit", MODE_PRIVATE)) }

    override fun onCreate() {
        super.onCreate()
        crashLog = CrashLog(this).also { it.install() }
        runCatching { version()?.let { BuildConfigInfo.version = it } }
        runCatching { loadStrings() }
        io.github.elija7580.publit.live.LiveOverlay.enabled = getSharedPreferences("publit", MODE_PRIVATE).getBoolean("extraLiveSources", true)
        io.github.elija7580.publit.data.SmartRouting.prefs = getSharedPreferences("publit", MODE_PRIVATE)
        runCatching { io.github.elija7580.publit.notify.TripNotifications.channels(this) }
        Fmt.timePattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        client = TransitousClient(cacheDir = cacheDir)
        location = LocationRepository(this)
        Thread { runCatching { client.pruneDisk() } }.start()
    }

    private fun version(): String? = packageManager.getPackageInfo(packageName, 0).versionName

    /** Translations: assets/strings/<lang>.json from the iOS string catalog (tools/strings.py). */
    private fun loadStrings() {
        val locale = Locale.getDefault()
        val available = assets.list("strings")?.map { it.removeSuffix(".json") }?.toSet().orEmpty()
        val candidates = listOf(locale.toLanguageTag(), locale.language + "-" + locale.country, locale.language)
        val lang = candidates.firstOrNull { it in available } ?: return
        val text = assets.open("strings/$lang.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = Json.parseToJsonElement(text) as JsonObject
        val table = HashMap<String, Any>(root.size)
        for ((k, v) in root) {
            table[k] = if (v is JsonPrimitive) v.content
            else (v as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        }
        val rules = PluralRules.forLocale(locale)
        L10n.install(table, locale) { rules.select(it.toDouble()) }
    }
}
