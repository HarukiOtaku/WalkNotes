package com.civicwalk.app

import android.app.Application
import org.maplibre.android.MapLibre
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two jobs:
 *
 * 1. MapLibre must be initialised before any MapView is inflated, otherwise MapView throws during
 *    inflation and the whole activity dies. Doing it here means it can never be forgotten.
 * 2. Keep the last uncaught exception on disk so a crash on a phone we cannot attach a debugger to
 *    is still diagnosable — the settings tab shows the text and it can be selected and copied.
 */
class CivicWalkApp : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            MapLibre.getInstance(this)
        } catch (t: Throwable) {
            // The street map is optional: without an instance the app forces the offline grid.
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { writeCrash(error) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrash(error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        File(filesDir, CRASH_FILE).writeText("$stamp\n$sw")
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"

        fun lastCrash(context: android.content.Context): String? = try {
            val f = File(context.filesDir, CRASH_FILE)
            if (f.isFile) f.readText() else null
        } catch (_: Throwable) { null }
    }
}
