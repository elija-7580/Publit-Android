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

object Droid {
    const val LOCATION_REQUEST = 7580

    @JvmStatic fun context(): Context = skip.foundation.ProcessInfo.processInfo.androidContext
    @JvmStatic fun activity(): androidx.activity.ComponentActivity? = skip.ui.UIApplication.shared.androidActivity

    // MARK: Display

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
}
