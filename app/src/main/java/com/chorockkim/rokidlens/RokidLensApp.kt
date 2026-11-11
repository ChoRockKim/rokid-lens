package com.chorockkim.rokidlens

import android.app.Application
import com.chorockkim.rokidlens.update.Updater
import java.io.File
import okhttp3.OkHttpClient

class RokidLensApp : Application() {
    val http by lazy { OkHttpClient() }
    val updater by lazy { Updater(this) { http } }

    private val crashFile get() = File(filesDir, "last_crash.txt")

    override fun onCreate() {
        super.onCreate()
        // There is no logcat on the glasses: keep why the app died, and show it on the next start
        // (MainActivity) so it can be reported.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { crashFile.writeText(crashSummary(e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The reason the app died last time, once (the file is removed); null if it did not. */
    fun takeLastCrash(): String? = runCatching {
        crashFile.takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
    }.getOrNull()

    companion object {
        /** "OutOfMemoryError: … @ LensPipeline.kt:42": the root cause and where it was thrown in this app. */
        fun crashSummary(e: Throwable): String {
            var root = e
            while (root.cause != null && root.cause !== root) root = root.cause!!
            val here = (root.stackTrace + e.stackTrace).firstOrNull { it.className.startsWith("com.chorockkim") }
                ?: root.stackTrace.firstOrNull()
            val where = here?.let { " @ ${it.fileName}:${it.lineNumber}" }.orEmpty()
            return "${root.javaClass.simpleName}: ${root.message?.take(80) ?: ""}$where".trim()
        }
    }
}
