package com.ridelink.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date

/**
 * Handler for the app's long-lived background coroutine scopes (sessions,
 * notification updates, voice chat). Without one, any exception thrown inside
 * a collector -- e.g. from a Nearby callback or a notification tap -- would
 * kill the whole process; with it the failure is logged and kept in
 * [CrashLog] and the rest of the app keeps running.
 */
fun crashGuard(tag: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
    Log.e(tag, "Unhandled error in a background task", error)
    CrashLog.record("Background task ($tag)", error)
}

/**
 * Keeps the last crash (or swallowed background error) on disk so it can be
 * shown on the role picker and shared, without needing adb. Plain text, local
 * only -- nothing leaves the phone unless the user taps Share.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"
    private var appContext: Context? = null

    /** Call once from Application.onCreate. Chains to the previous handler so the system still reports crashes normally. */
    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record("Crash on thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(label: String, error: Throwable) {
        val context = appContext ?: return
        runCatching {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val text = "$label\n${DateFormat.getDateTimeInstance().format(Date())}\n\n$trace"
            File(context.filesDir, FILE_NAME).writeText(text.take(20_000))
        }
    }

    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
