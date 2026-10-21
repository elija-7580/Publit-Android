// Publit for Android: the Kotlin side of the platform services (called from Swift via
// AnyDynamicObject, see Sources/Publit/Android/Droid.swift). Results come back through the
// bridged Swift class DroidEvents.
package publit.module

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Tram

object Droid {
    const val LOCATION_REQUEST = 7580

    @JvmStatic fun context(): Context = skip.foundation.ProcessInfo.processInfo.androidContext
    @JvmStatic fun activity(): androidx.activity.ComponentActivity? = skip.ui.UIApplication.shared.androidActivity

    @JvmStatic fun walletSigner(): String {
        val info = context().packageManager.getApplicationInfo(context().packageName, PackageManager.GET_META_DATA)
        return info.metaData?.getString("publit.walletSignerUrl") ?: ""
    }
    @JvmStatic fun walletText(key: String): String = DroidNotify.res(context(), key, key)

    // MARK: Display

    @JvmStatic fun density(): Double = context().resources.displayMetrics.density.toDouble()

    @JvmStatic fun screenWidthDp(): Double = context().resources.configuration.screenWidthDp.toDouble()
    @JvmStatic fun screenHeightDp(): Double = context().resources.configuration.screenHeightDp.toDouble()
    @JvmStatic fun androidVersion(): String = android.os.Build.VERSION.RELEASE ?: ""
    @JvmStatic fun deviceModel(): String = android.os.Build.MODEL ?: "Android"
    @JvmStatic fun appVersion(): String = context().packageManager.getPackageInfo(context().packageName, 0).versionName ?: "0.6.0"
    @JvmStatic fun appBuild(): String {
        val info = context().packageManager.getPackageInfo(context().packageName, 0)
        @Suppress("DEPRECATION")
        return (if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()).toString()
    }


    /// The window's brightness override, -1 while the system brightness applies.
    @JvmStatic fun screenBrightness(): Double = activity()?.window?.attributes?.screenBrightness?.toDouble() ?: -1.0

    @JvmStatic fun setScreenBrightness(value: Double) {
        val a = activity() ?: return
        a.runOnUiThread {
            val attributes = a.window.attributes
            attributes.screenBrightness = if (value < 0) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else value.toFloat().coerceIn(0f, 1f)
            a.window.attributes = attributes
        }
    }

    /// "5 minutes ago", "now" (Fmt.ago): ICU's relative formatter in the app language.
    @JvmStatic fun relativeTime(seconds: Double): String {
        val f = android.icu.text.RelativeDateTimeFormatter.getInstance(java.util.Locale.getDefault())
        val s = seconds.toLong()
        val Last = android.icu.text.RelativeDateTimeFormatter.Direction.LAST
        return when {
            s < 60 -> f.format(android.icu.text.RelativeDateTimeFormatter.Direction.PLAIN, android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.NOW)
            s < 3600 -> f.format((s / 60).toDouble(), Last, android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES)
            s < 86400 -> f.format((s / 3600).toDouble(), Last, android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS)
            s < 2 * 86400 -> f.format(Last, android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.DAY)
            s < 7 * 86400 -> f.format((s / 86400).toDouble(), Last, android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS)
            s < 30 * 86400 -> f.format((s / (7 * 86400)).toDouble(), Last, android.icu.text.RelativeDateTimeFormatter.RelativeUnit.WEEKS)
            else -> f.format((s / (30 * 86400)).toDouble(), Last, android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MONTHS)
        }
    }

    @JvmStatic fun fontScale(): Double = context().resources.configuration.fontScale.toDouble()

    @JvmStatic fun isDark(): Boolean =
        (context().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // MARK: Location

    private var locationListener: LocationListener? = null
    private var asked = false

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** "0" not asked yet, "1" denied, "2" allowed. */
    @JvmStatic fun locationPermission(): String {
        if (hasLocationPermission()) return "2"
        val prefs = context().getSharedPreferences("publit.android", Context.MODE_PRIVATE)
        return if (prefs.getBoolean("locationAsked", false)) "1" else "0"
    }

    @JvmStatic fun requestLocationPermission() {
        val activity = activity() ?: return
        context().getSharedPreferences("publit.android", Context.MODE_PRIVATE).edit().putBoolean("locationAsked", true).apply()
        asked = true
        ActivityCompat.requestPermissions(activity,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST)
    }

    /** From MainActivity.onRequestPermissionsResult and onResume (the answer may come from Settings). */
    @JvmStatic fun permissionsChanged() {
        DroidEvents.shared.locationPermission(locationPermission().toInt())
    }

    @JvmStatic fun startLocation(precise: Boolean) {
        if (!hasLocationPermission()) return
        val lm = context().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationListener?.let { lm.removeUpdates(it) }
        val listener = LocationListener { l -> report(l) }
        locationListener = listener
        val minTime = if (precise) 1000L else 5000L
        val minDistance = if (precise) 5f else 50f
        try {
            val providers = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                providers.add(LocationManager.FUSED_PROVIDER)
            } else {
                if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER)
                if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER)
            }
            // The last fix right away: departures can load before GPS answers.
            providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { report(it) }
            for (p in providers) lm.requestLocationUpdates(p, minTime, minDistance, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            DroidEvents.shared.locationPermission(1)
        }
    }

    private fun report(l: Location) {
        DroidEvents.shared.location(l.latitude, l.longitude,
            if (l.hasAccuracy()) l.accuracy.toDouble() else -1.0,
            if (l.hasSpeed()) l.speed.toDouble() else -1.0,
            if (l.hasBearing()) l.bearing.toDouble() else -1.0,
            l.time.toDouble())
    }

    @JvmStatic fun stopLocation() {
        val lm = context().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationListener?.let { lm.removeUpdates(it) }
        locationListener = null
    }

    // MARK: Compass

    private var headingListener: SensorEventListener? = null

    @JvmStatic fun setHeading(on: Boolean) {
        val sm = context().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        headingListener?.let { sm.unregisterListener(it) }
        headingListener = null
        if (!on) return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        var last = -999.0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val r = FloatArray(9); val o = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(r, event.values)
                SensorManager.getOrientation(r, o)
                val deg = (Math.toDegrees(o[0].toDouble()) + 360.0) % 360.0
                // About every 8° (as the iOS heading filter).
                if (Math.abs(deg - last) >= 8 || last < 0) { last = deg; DroidEvents.shared.heading(deg) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        headingListener = listener
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    // MARK: Notifications (Swift: UserNotificationsShim.swift, Kotlin: DroidNotifications.kt)

    @JvmStatic fun notificationsAllowed(): Boolean = DroidNotify.allowed(context())

    @JvmStatic fun notificationsAsked(): Boolean =
        context().getSharedPreferences("publit.android", Context.MODE_PRIVATE).getBoolean("notificationsAsked", false)

    @JvmStatic fun requestNotificationPermission(requestId: String) {
        val activity = activity()
        if (Build.VERSION.SDK_INT < 33 || activity == null) {
            DroidEvents.shared.pickerResult(requestId, if (notificationsAllowed()) "true" else "false")
            return
        }
        context().getSharedPreferences("publit.android", Context.MODE_PRIVATE).edit().putBoolean("notificationsAsked", true).apply()
        DroidNotify.pendingPermissionRequest = requestId
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), DroidNotify.PERMISSION_REQUEST)
    }

    /** MainActivity.onRequestPermissionsResult */
    @JvmStatic fun onPermissionsResult(requestCode: Int) {
        if (requestCode == DroidNotify.PERMISSION_REQUEST) {
            val id = DroidNotify.pendingPermissionRequest ?: return
            DroidNotify.pendingPermissionRequest = null
            DroidEvents.shared.pickerResult(id, if (notificationsAllowed()) "true" else "false")
        } else if (requestCode == LOCATION_REQUEST) {
            permissionsChanged()
        }
    }

    @JvmStatic fun scheduleNotification(id: String, title: String, body: String, url: String, fireMillis: Double, urgent: Boolean, sound: Boolean) {
        val json = org.json.JSONObject().put("title", title).put("body", body).put("url", url)
            .put("fire", fireMillis).put("urgent", urgent).put("sound", sound)
        DroidNotify.schedule(context(), id, json)
    }

    @JvmStatic fun cancelNotification(id: String) = DroidNotify.cancel(context(), id)

    @JvmStatic fun removeDeliveredNotification(id: String) =
        androidx.core.app.NotificationManagerCompat.from(context()).cancel(id, 0)

    @JvmStatic fun cancelAllNotifications() {
        for (id in DroidNotify.pending(context()).keys) DroidNotify.cancel(context(), id)
    }

    @JvmStatic fun pendingNotificationIds(): String = DroidNotify.pending(context()).keys.joinToString("\n")

    @JvmStatic fun liveShow(json: String) = DroidLive.show(context(), org.json.JSONObject(json))
    @JvmStatic fun liveEnd(id: String) = DroidLive.end(context(), id)

    @JvmStatic fun cacheDir(): String = context().cacheDir.absolutePath

    // MARK: Tickets (DroidTickets.kt); numbers cross as Double

    @JvmStatic fun pdfPageCount(path: String): Double = DroidTickets.pageCount(path).toDouble()

    @JvmStatic fun renderTicketPage(path: String, pdf: Boolean, page: Double, maxPixels: Double, out: String): Boolean {
        val bmp = DroidTickets.page(path, pdf, page.toInt(), maxPixels.toInt()) ?: return false
        return DroidTickets.save(bmp, out)
    }

    @JvmStatic fun scanTicket(path: String, pdf: Boolean, page: Double, recognizeText: Boolean): String =
        DroidTickets.scan(path, pdf, page.toInt(), recognizeText)

    @JvmStatic fun ticketPieces(path: String, pdf: Boolean, page: Double): String = DroidTickets.pieces(path, pdf, page.toInt())

    @JvmStatic fun cropTicket(path: String, pdf: Boolean, page: Double, x: Double, y: Double, w: Double, h: Double, out: String): Boolean =
        DroidTickets.crop(path, pdf, page.toInt(), x, y, w, h, out)

    @JvmStatic fun renderCode(format: String, text: String, bytes: String, maxW: Double, maxH: Double, out: String): Boolean =
        DroidTickets.renderCode(format, text.ifEmpty { null }, bytes.ifEmpty { null }, maxW.toInt(), maxH.toInt(), out)

    /** Logos: decoded, at most maxW × maxH pixels (never enlarged), PNG with transparency. */
    @JvmStatic fun fitLogo(path: String, maxW: Double, maxH: Double, out: String): Boolean {
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: return false
        if (bmp.width < 16 || bmp.height < 16) return false
        val f = minOf(1.0, minOf(maxW / bmp.width, maxH / bmp.height))
        val w = maxOf(1, Math.round(bmp.width * f).toInt()); val h = maxOf(1, Math.round(bmp.height * f).toInt())
        return DroidTickets.save(if (f < 1.0) android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true) else bmp, out)
    }

    @JvmStatic fun canScanDocuments(): Boolean = DroidTickets.canScanDocuments()

    // MARK: Pickers (photo, file, document scanner); the result path goes to DroidEvents.pickerResult

    const val PICK_IMAGE = 7101
    const val PICK_FILE = 7102
    const val SCAN_DOCUMENT = 7103
    private var pendingPick: String? = null

    private fun finishPick(path: String) {
        val id = pendingPick ?: return
        pendingPick = null
        DroidEvents.shared.pickerResult(id, path)
    }

    @JvmStatic fun pickImage(requestId: String) {
        val activity = activity() ?: return DroidEvents.shared.pickerResult(requestId, "")
        pendingPick = requestId
        val intent = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia().createIntent(activity,
            androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        try { activity.startActivityForResult(intent, PICK_IMAGE) } catch (e: Exception) { finishPick("") }
    }

    @JvmStatic fun pickFile(requestId: String, types: String) {
        val activity = activity() ?: return DroidEvents.shared.pickerResult(requestId, "")
        pendingPick = requestId
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(android.content.Intent.EXTRA_MIME_TYPES, types.split(",").toTypedArray())
        try { activity.startActivityForResult(intent, PICK_FILE) } catch (e: Exception) { finishPick("") }
    }

    @JvmStatic fun scanDocument(requestId: String) {
        val activity = activity() ?: return DroidEvents.shared.pickerResult(requestId, "")
        pendingPick = requestId
        val options = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(1)
            .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                try { activity.startIntentSenderForResult(sender, SCAN_DOCUMENT, null, 0, 0, 0) } catch (e: Exception) { finishPick("") }
            }
            .addOnFailureListener { finishPick("") }
    }

    /** Called by MainActivity.onActivityResult. */
    @JvmStatic fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode != PICK_IMAGE && requestCode != PICK_FILE && requestCode != SCAN_DOCUMENT) return
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return finishPick("")
        val uri = if (requestCode == SCAN_DOCUMENT) {
            com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages?.firstOrNull()?.imageUri
        } else data.data
        if (uri == null) return finishPick("")
        val ctx = context()
        Thread {
            val path = try {
                var name = "picked"
                ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
                if (!name.contains('.')) {
                    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(ctx.contentResolver.getType(uri)) ?: "jpg"
                    name += ".$ext"
                }
                val dir = java.io.File(ctx.cacheDir, "Picked/" + System.currentTimeMillis()).apply { mkdirs() }
                val file = java.io.File(dir, name.replace('/', '_'))
                ctx.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                file.absolutePath
            } catch (e: Exception) { "" }
            android.os.Handler(android.os.Looper.getMainLooper()).post { finishPick(path) }
        }.start()
    }

    // MARK: Other apps

    /** The calendar app's "New event" screen, prefilled (Android needs no permission for this). */
    @JvmStatic fun addCalendarEvent(title: String, startMillis: Double, endMillis: Double, location: String, notes: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_INSERT)
            .setData(android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis.toLong())
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endMillis.toLong())
            .putExtra(android.provider.CalendarContract.Events.TITLE, title)
            .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
            .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, notes)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context().startActivity(intent) } catch (e: Exception) {}
    }

    /** Opens a link (web, geo:, tel:, mailto:, another app's scheme); false when no app takes it. */
    @JvmStatic fun openURL(url: String): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { (activity() ?: context()).startActivity(intent); true } catch (e: Exception) { false }
    }

    /** The system share sheet for a text or link. */
    @JvmStatic fun shareText(text: String, subject: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        if (subject.isNotEmpty()) send.putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
        val chooser = android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { (activity() ?: context()).startActivity(chooser) } catch (e: Exception) {}
    }

    // MARK: Spoken guidance (GuideVoice)

    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var pending: Pair<String, String>? = null

    @JvmStatic fun speak(text: String, language: String) {
        val engine = tts
        if (engine == null) {
            pending = Pair(text, language)
            tts = android.speech.tts.TextToSpeech(context()) { status ->
                ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
                pending?.let { p -> pending = null; if (ttsReady) say(p.first, p.second) }
            }
            return
        }
        if (ttsReady) say(text, language) else pending = Pair(text, language)
    }

    private fun say(text: String, language: String) {
        val engine = tts ?: return
        if (language.isNotEmpty()) engine.setLanguage(java.util.Locale.forLanguageTag(language))
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
        engine.setAudioAttributes(attrs)
        engine.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "publit-guide")
    }

    @JvmStatic fun stopSpeaking() { tts?.stop() }

    @JvmStatic fun isSpeaking(): Boolean = tts?.isSpeaking == true

    // MARK: Launcher shortcuts (the iOS app's Siri shortcuts)

    // Background work, diagnostics, Health Connect (DroidBackground.kt)
    @JvmStatic fun scheduleLineCheck(on: Boolean) = DroidWork.scheduleLineCheck(context(), on)
    @JvmStatic fun startDiagnostics(build: String) = DroidDiagnostics.start(context(), build)
    @JvmStatic fun diagnosticReports(sinceMillis: Double): String = DroidDiagnostics.reports(context(), sinceMillis)
    @JvmStatic fun stepsAvailable(): Boolean = DroidHealth.available(context())
    @JvmStatic fun measureWalkingSpeed(requestId: String) = DroidHealth.measure(context(), requestId)

    @JvmStatic fun setShortcuts(json: String) {
        val ctx = context()
        val list = org.json.JSONArray(json)
        val shortcuts = (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            val icon = when (o.optString("icon")) {
                "home" -> androidx.compose.material.icons.Icons.Filled.Home
                "work" -> androidx.compose.material.icons.Icons.Filled.Work
                else -> androidx.compose.material.icons.Icons.Filled.Tram
            }
            androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, o.getString("id"))
                .setShortLabel(o.getString("label"))
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(PublitIcons.bitmap(ctx, icon, 0xFF111111.toInt(), 0xFFFFFFFF.toInt(), 48)))
                .setIntent(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(o.getString("url")))
                    .setPackage(ctx.packageName))
                .build()
        }
        try { androidx.core.content.pm.ShortcutManagerCompat.setDynamicShortcuts(ctx, shortcuts) } catch (e: Exception) {}
    }
}
