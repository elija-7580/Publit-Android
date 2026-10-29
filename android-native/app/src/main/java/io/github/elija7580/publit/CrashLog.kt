package io.github.elija7580.publit

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kotlin crashes arrive as plain stack traces on every Android version. The last one is kept in a
 * file and shown at the next start, ready to copy: no adb needed.
 */
class CrashLog(private val context: Context) {
    private val file get() = File(context.filesDir, "last-crash.txt")
    private val notes = ArrayDeque<String>()

    /** Breadcrumbs: what the app was doing just before (screen, permission, fix). Kept short. */
    @Synchronized fun note(text: String) {
        notes.addLast(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + " " + text)
        while (notes.size > 40) notes.removeFirst()
    }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun report(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val app = BuildConfigInfo.version
        return buildString {
            appendLine("Publit $app, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine(trace.take(8000))
            appendLine("Last steps:")
            synchronized(this@CrashLog) { notes.forEach { appendLine(it) } }
        }
    }

    /** The report of the last crash, once. */
    fun take(): String? = runCatching {
        file.takeIf { it.exists() }?.readText()?.also { file.delete() }
    }.getOrNull()
}
