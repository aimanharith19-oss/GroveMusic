package com.grove.music

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File

/** Keeps the reason GroveMedia last closed unexpectedly (Java crash, native crash, freeze, out of memory) so it can be shown on next open. */
object CrashReport {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file(ctx).writeText("${java.util.Date()}\nthread: ${t.name}\n\n${e.stackTraceToString()}") }
            prev?.uncaughtException(t, e)
        }
    }

    /** text of the last unexpected exit not shown yet, or null */
    fun pending(ctx: Context): String? = runCatching {
        val p = ctx.getSharedPreferences("grove", Context.MODE_PRIVATE)
        val seen = p.getLong("crash_seen", 0L)
        val sb = StringBuilder()
        val am = ctx.getSystemService(ActivityManager::class.java)
        var newest = seen
        am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).filter { it.timestamp > seen && it.processName == ctx.packageName }.forEach { x ->
            val why = when (x.reason) {
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                ApplicationExitInfo.REASON_ANR -> "froze (not responding)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "out of memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
                else -> null
            } ?: return@forEach
            newest = maxOf(newest, x.timestamp)
            sb.append("${java.util.Date(x.timestamp)} — $why\n${x.description ?: ""}\n")
            if (x.reason == ApplicationExitInfo.REASON_ANR || x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) runCatching {
                x.traceInputStream?.bufferedReader()?.use { r -> sb.append(r.readText().take(6000)).append('\n') }
            }
        }
        val f = file(ctx)
        if (f.exists()) { sb.append("\n").append(f.readText().take(8000)); f.delete() }
        p.edit().putLong("crash_seen", maxOf(newest, System.currentTimeMillis())).apply()
        sb.toString().trim().ifEmpty { null }
    }.getOrNull()
}
