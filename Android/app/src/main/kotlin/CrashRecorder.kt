package publit.module

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// Keeps the reason for the last crash, so it can be read without a cable or logcat.
///
/// Kotlin/Java exceptions are written by an uncaught-exception handler before the process dies.
/// Native crashes in the Swift code (traps, force unwraps) and ANRs never reach that handler:
/// those come from Android's own exit records (API 30+) at the next launch. In debuggable builds
/// the report then opens in a dialog with a Copy button; it is always written to logcat too.
internal object CrashRecorder {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "publit.crashrecorder"
    private const val SEEN_KEY = "exitSeenUntil"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildString {
                    append("Kotlin/Java crash on thread \"").append(thread.name).append("\"\n")
                    append(stamp(System.currentTimeMillis())).append("\n\n")
                    append(android.util.Log.getStackTraceString(error))
                }
                File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
        try {
            collectExitReasons(app)
        } catch (e: Throwable) {
            android.util.Log.w("Publit", "exit reasons unavailable", e)
        }
    }

    /// Native crashes and ANRs since the last launch, from ActivityManager.
    private fun collectExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN_KEY, 0L)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        val newest = exits.maxOfOrNull { it.timestamp } ?: return
        prefs.edit().putLong(SEEN_KEY, maxOf(seen, newest)).apply()
        val exit = exits.filter { it.timestamp > seen }.firstOrNull {
            it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                it.reason == ApplicationExitInfo.REASON_ANR ||
                it.reason == ApplicationExitInfo.REASON_CRASH
        } ?: return
        val file = File(context.filesDir, FILE)
        // A Kotlin crash already wrote its own, better report.
        if (exit.reason == ApplicationExitInfo.REASON_CRASH && file.exists()) return
        val kind = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (Swift)"
            ApplicationExitInfo.REASON_ANR -> "App not responding (ANR)"
            else -> "Crash"
        }
        val trace = try {
            exit.traceInputStream?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Throwable) {
            null
        }
        val text = buildString {
            append(kind).append("\n").append(stamp(exit.timestamp)).append("\n")
            append(exit.description ?: "").append("\n\n")
            if (trace != null) append(summarize(trace, exit.reason)) else append("(no trace recorded)")
        }
        file.writeText(text)
    }

    /// Tombstones are long; the crashing thread's frames and the abort message are what matters.
    private fun summarize(trace: String, reason: Int): String {
        if (reason == ApplicationExitInfo.REASON_ANR) {
            val main = trace.indexOf("\"main\"")
            return if (main >= 0) trace.substring(main).take(6000) else trace.take(6000)
        }
        val lines = trace.lines()
        val keep = lines.filter {
            it.contains("Abort message") || it.contains("signal ") || it.contains("Fatal error") ||
                it.trimStart().startsWith("#")
        }
        return (if (keep.isEmpty()) lines else keep).take(60).joinToString("\n")
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))

    /// Shows (debuggable builds) and logs the last report once, then forgets it.
    fun showPendingReport(activity: android.app.Activity) {
        val file = File(activity.filesDir, FILE)
        if (!file.exists()) return
        val text = try { file.readText() } catch (_: Throwable) { return }
        file.delete()
        android.util.Log.e("Publit", "Last crash:\n$text")
        val debuggable = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val view = TextView(activity).apply {
            setText(text)
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Publit crashed last time")
            .setView(ScrollView(activity).apply { addView(view) })
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Publit crash", text))
            }
            .show()
    }
}
