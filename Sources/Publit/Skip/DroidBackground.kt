package publit.module

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/// Background check of saved lines (the iOS BGAppRefreshTask): runs the Swift `LineAlertMonitor.check`.
class LineCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            PublitAppDelegate.shared.backgroundRefresh()
            Result.success()
        } catch (e: Throwable) {
            android.util.Log.e("Publit", "line check failed", e)
            Result.retry()
        }
    }
}

/// Fresh departures on the home screen widgets every 15 minutes (WidgetKit asks again after 15 minutes too).
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            DeparturesWidget.refreshAll(applicationContext)
            Result.success()
        } catch (e: Throwable) {
            android.util.Log.e("Publit", "widget refresh failed", e)
            Result.retry()
        }
    }
}

object DroidWork {
    private const val lineCheck = "lineAlerts"
    private const val widgetRefresh = "widgetRefresh"

    fun scheduleLineCheck(context: Context, on: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!on) { manager.cancelUniqueWork(lineCheck); return }
        val request = PeriodicWorkRequestBuilder<LineCheckWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(lineCheck, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun scheduleWidgetRefresh(context: Context, on: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!on) { manager.cancelUniqueWork(widgetRefresh); return }
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(widgetRefresh, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/// Crash reports (the iOS MetricKit part): Android's own exit records (crashes, ANRs, low memory) and the
/// stack of the last uncaught Kotlin/Java exception, read by the Swift `CrashReports` at the next launch.
object DroidDiagnostics {
    private var started = false
    private const val crashFile = "last-crash.json"

    fun start(context: Context, build: String) {
        if (started) return
        started = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val out = StringWriter()
                error.printStackTrace(PrintWriter(out))
                val o = JSONObject().put("time", System.currentTimeMillis().toDouble()).put("thread", thread.name)
                    .put("build", build).put("stack", out.toString().take(16_000))
                File(context.filesDir, crashFile).writeText(o.toString())
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try { context.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(build.toByteArray()) } catch (_: Throwable) {}
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val names = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
                context.getSystemService(PowerManager::class.java)?.addThermalStatusListener { status ->
                    if (status >= PowerManager.THERMAL_STATUS_MODERATE) DroidEvents.shared.diagnosticNote("thermal ${names.getOrElse(status) { status.toString() }}")
                }
            } catch (_: Throwable) {}
        }
    }

    fun reports(context: Context, sinceMillis: Double): String {
        val out = JSONArray()
        val release = Build.VERSION.RELEASE ?: "?"
        var javaCrash = 0.0
        val file = File(context.filesDir, crashFile)
        if (file.isFile) {
            try {
                val o = JSONObject(file.readText())
                javaCrash = o.optDouble("time", 0.0)
                if (javaCrash > sinceMillis) {
                    out.put(JSONObject().put("kind", "CRASH").put("time", javaCrash)
                        .put("text", "Android: the app crashed in build ${o.optString("build")} (Android $release), thread ${o.optString("thread")}.")
                        .put("stack", o.optString("stack")))
                }
            } catch (_: Throwable) {}
            file.delete()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = context.getSystemService(ActivityManager::class.java)
                for (info in am.getHistoricalProcessExitReasons(null, 0, 16)) {
                    val time = info.timestamp.toDouble()
                    if (time <= sinceMillis) continue
                    val kind = when (info.reason) {
                        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH"
                        ApplicationExitInfo.REASON_ANR -> "HANG"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "MEMORY"
                        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "CPU"
                        else -> null
                    } ?: continue
                    // The Kotlin/Java crash with its stack is already in the list.
                    if (info.reason == ApplicationExitInfo.REASON_CRASH && javaCrash > 0 && Math.abs(time - javaCrash) < 15_000) continue
                    val build = info.processStateSummary?.let { String(it) } ?: "?"
                    val what = when (info.reason) {
                        ApplicationExitInfo.REASON_CRASH -> "a crash"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "a native crash (Swift)"
                        ApplicationExitInfo.REASON_ANR -> "a hang (app not responding)"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "an end for low memory"
                        else -> "an end for heavy resource use"
                    }
                    var text = "Android recorded $what in build $build (Android $release)."
                    info.description?.takeIf { it.isNotBlank() }?.let { text += "\nReason: $it" }
                    if (info.reason == ApplicationExitInfo.REASON_LOW_MEMORY || info.pss > 0) text += "\nMemory: ${info.pss / 1024} MB"
                    var stack = ""
                    if (info.reason == ApplicationExitInfo.REASON_ANR) {
                        stack = try { info.traceInputStream?.use { s -> String(s.readBytes()).lineSequence().filter { it.isNotBlank() }.take(80).joinToString("\n") { "    $it" } } ?: "" } catch (_: Throwable) { "" }
                    }
                    out.put(JSONObject().put("kind", kind).put("time", time).put("text", text).put("stack", stack))
                }
            } catch (e: Throwable) {
                android.util.Log.e("Publit", "exit reasons failed", e)
            }
        }
        return out.toString()
    }
}

/// Walking speed from Health Connect (the iOS pedometer part): steps and distance of the last seven days,
/// read on the device only, after the user allowed it. Result to Swift: speed in m/s, or -1.
object DroidHealth {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    fun available(context: Context): Boolean =
        try { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE } catch (_: Throwable) { false }

    fun measure(context: Context, requestId: String) {
        scope.launch {
            val speed = try { speed(context) } catch (e: Throwable) {
                android.util.Log.e("Publit", "walking speed failed", e); -1.0
            }
            DroidEvents.shared.pickerResult(requestId, speed.toString())
        }
    }

    private suspend fun speed(context: Context): Double {
        if (!available(context)) return -1.0
        val client = HealthConnectClient.getOrCreate(context)
        var granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(permissions)) {
            val activity = Droid.activity() ?: return -1.0
            granted = ask(activity)
            if (!granted.containsAll(permissions)) return -1.0
        }
        val end = Instant.now()
        val result = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL),
            TimeRangeFilter.between(end.minus(7, ChronoUnit.DAYS), end)))
        val steps = result[StepsRecord.COUNT_TOTAL] ?: 0L
        val meters = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
        // Step length at a usual walking cadence (about 1.8 steps a second), as on iOS without a measured pace.
        return if (steps > 2_000 && meters > 0) meters / steps * 1.8 else -1.0
    }

    private suspend fun ask(activity: ComponentActivity): Set<String> = suspendCancellableCoroutine { c ->
        var launcher: androidx.activity.result.ActivityResultLauncher<Set<String>>? = null
        launcher = activity.activityResultRegistry.register("publit.health", PermissionController.createRequestPermissionResultContract()) { granted ->
            launcher?.unregister()
            if (c.isActive) c.resume(granted)
        }
        launcher.launch(permissions)
    }
}
