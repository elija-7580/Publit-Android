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
        val bytes = try {
            exit.traceInputStream?.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
        // Android 12+ stores a native crash as a binary (protobuf) tombstone, not as text. Read as
        // text it was only the process's memory map; decode the useful parts instead.
        val tombstone = if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && bytes != null)
            try { TombstoneReader.summarize(bytes) } catch (_: Throwable) { null } else null
        val trace = bytes?.toString(Charsets.UTF_8)
        val text = buildString {
            append(kind).append("\n").append(stamp(exit.timestamp)).append("\n")
            append(exit.description ?: "").append("\n\n")
            when {
                tombstone != null -> append(tombstone)
                trace != null -> append(summarize(trace, exit.reason))
                else -> append("(no trace recorded)")
            }
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

/// The readable parts of an Android tombstone (system/core/debuggerd/proto/tombstone.proto):
/// signal, abort message, cause, the crashing thread's frames and the last log lines.
/// A minimal protobuf reader, so no protobuf library is needed.
internal object TombstoneReader {
    private class Reader(val b: ByteArray, var p: Int, val end: Int) {
        fun more() = p < end
        fun varint(): Long {
            var shift = 0; var result = 0L
            while (p < end && shift < 64) {
                val x = b[p++].toInt() and 0xFF
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
            }
            return result
        }
        /// Calls `field(number, wireType, reader-for-length-delimited or null, varint value)`.
        fun forEach(field: (Int, Int, Reader?, Long) -> Unit) {
            while (more()) {
                val tag = varint()
                val number = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
                when (wire) {
                    0 -> field(number, wire, null, varint())
                    1 -> { p += 8; if (p > end) return }
                    2 -> {
                        val len = varint().toInt()
                        if (len < 0 || p + len > end) return
                        field(number, wire, Reader(b, p, p + len), 0)
                        p += len
                    }
                    5 -> { p += 4; if (p > end) return }
                    else -> return
                }
            }
        }
        fun string() = String(b, p, end - p, Charsets.UTF_8)
    }

    private class Frame(var relPc: Long = 0, var function: String = "", var offset: Long = 0, var file: String = "", var buildId: String = "")
    private class CrashThread(var id: Int = 0, var name: String = "", val frames: MutableList<Frame> = mutableListOf(), val notes: MutableList<String> = mutableListOf())

    fun summarize(bytes: ByteArray): String? {
        var tid = 0
        var abort = ""
        var signal = ""
        val causes = mutableListOf<String>()
        val threads = mutableMapOf<Int, CrashThread>()
        val logs = mutableListOf<String>()
        var command = ""
        var pageSize = 0L
        var compat16k = false
        var fields = 0
        Reader(bytes, 0, bytes.size).forEach { n, _, r, v ->
            fields++
            when (n) {
                6 -> tid = v.toInt()
                9 -> if (r != null && command.isEmpty()) command = r.string()
                10 -> if (r != null) signal = readSignal(r)
                14 -> if (r != null) abort = r.string()
                15 -> r?.forEach { cn, _, cr, _ -> if (cn == 1 && cr != null) causes.add(cr.string()) }
                16 -> if (r != null) {
                    var key = 0; var thread: CrashThread? = null
                    r.forEach { mn, _, mr, mv -> if (mn == 1) key = mv.toInt() else if (mn == 2 && mr != null) thread = readThread(mr) }
                    thread?.let { threads[if (key != 0) key else it.id] = it }
                }
                18 -> if (r != null) readLogs(r, logs)
                22 -> pageSize = v
                23 -> compat16k = v != 0L
            }
        }
        if (fields == 0 || (signal.isEmpty() && abort.isEmpty() && threads.isEmpty())) return null
        val crashing = threads[tid] ?: threads.values.firstOrNull()
        return buildString {
            if (command.isNotEmpty()) append("Process: ").append(command).append("\n")
            if (pageSize > 0) append("Page size: ").append(pageSize / 1024).append(" KB").append(if (compat16k) " (16 KB mode)" else "").append("\n")
            if (signal.isNotEmpty()) append(signal).append("\n")
            if (abort.isNotEmpty()) append("Abort message: ").append(abort.take(2000)).append("\n")
            for (c in causes.take(3)) append("Cause: ").append(c).append("\n")
            if (crashing != null) {
                append("\nCrashing thread \"").append(crashing.name).append("\" (tid ").append(crashing.id).append("):\n")
                for (note in crashing.notes.take(5)) append("  ").append(note).append("\n")
                crashing.frames.take(48).forEachIndexed { i, f ->
                    append("  #").append(i.toString().padStart(2, '0'))
                    append(" pc ").append(java.lang.Long.toHexString(f.relPc).padStart(16, '0'))
                    append("  ").append(f.file)
                    if (f.function.isNotEmpty()) append(" (").append(f.function).append('+').append(f.offset).append(')')
                    if (f.buildId.isNotEmpty()) append(" (BuildId: ").append(f.buildId).append(')')
                    append("\n")
                }
            }
            if (logs.isNotEmpty()) {
                append("\nLast log lines:\n")
                for (l in logs.takeLast(40)) append("  ").append(l).append("\n")
            }
        }
    }

    private fun readSignal(r: Reader): String {
        var number = 0L; var name = ""; var code = 0L; var codeName = ""; var hasFault = false; var fault = 0L
        r.forEach { n, _, sr, v ->
            when (n) {
                1 -> number = v
                2 -> name = sr?.string() ?: ""
                3 -> code = v
                4 -> codeName = sr?.string() ?: ""
                8 -> hasFault = v != 0L
                9 -> fault = v
            }
        }
        return "Signal $number ($name), code ${code.toInt()} ($codeName)" + (if (hasFault) ", fault addr 0x" + java.lang.Long.toHexString(fault) else "")
    }

    private fun readThread(r: Reader): CrashThread {
        val t = CrashThread()
        r.forEach { n, _, tr, v ->
            when (n) {
                1 -> t.id = v.toInt()
                2 -> t.name = tr?.string() ?: ""
                4 -> if (tr != null) {
                    val f = Frame()
                    tr.forEach { fn, _, fr, fv ->
                        when (fn) {
                            1 -> f.relPc = fv
                            4 -> f.function = fr?.string() ?: ""
                            5 -> f.offset = fv
                            6 -> f.file = fr?.string() ?: ""
                            8 -> f.buildId = fr?.string() ?: ""
                        }
                    }
                    t.frames.add(f)
                }
                7 -> tr?.let { t.notes.add(it.string()) }
            }
        }
        return t
    }

    private fun readLogs(r: Reader, out: MutableList<String>) {
        r.forEach { n, _, lr, _ ->
            if (n == 2 && lr != null) {
                var time = ""; var priority = 0L; var tag = ""; var message = ""
                lr.forEach { mn, _, mr, mv ->
                    when (mn) {
                        1 -> time = mr?.string() ?: ""
                        4 -> priority = mv
                        5 -> tag = mr?.string() ?: ""
                        6 -> message = mr?.string() ?: ""
                    }
                }
                val level = "?VDIWEF".getOrElse(priority.toInt()) { '?' }
                out.add("$time $level $tag: ${message.trimEnd().take(400)}")
                if (out.size > 200) out.removeAt(0)
            }
        }
    }
}
