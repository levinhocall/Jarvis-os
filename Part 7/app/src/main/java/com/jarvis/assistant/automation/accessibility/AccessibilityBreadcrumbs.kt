package com.jarvis.assistant.automation.accessibility

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Thread-safe 200-entry in-memory and on-disk ring buffer for accessibility lifecycle events,
 * state changes, FGS checks, and caught/uncaught Throwables.
 *
 * Privacy guarantee: Never records user prompts, screen text, contacts, or API keys.
 */
object AccessibilityBreadcrumbs {

    private const val TAG = "A11yBreadcrumbs"
    private const val MAX_ENTRIES = 200
    private const val LOG_FILE_NAME = "a11y-breadcrumbs.log"

    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val lock = Any()
    @Volatile
    private var logFile: File? = null
    @Volatile
    private var isHandlerInstalled = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) {
                try {
                    logFile = File(context.filesDir, LOG_FILE_NAME)
                    loadPersistedEntries()
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed initializing breadcrumbs log file: ${t.message}")
                }
            }
        }
        installUncaughtExceptionHandler()
    }

    /**
     * Initializes with an explicit file (useful for pure unit tests without Android Context).
     */
    fun initWithFile(file: File) {
        synchronized(lock) {
            logFile = file
            loadPersistedEntries()
        }
    }

    fun record(category: String, message: String) {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val entry = "[$timestamp UTC] [$category] $message"
        addEntry(entry)
    }

    fun recordLifecycle(component: String, event: String) {
        record("LIFECYCLE", "$component.$event")
    }

    fun recordStateTransition(from: String, to: String, reason: String? = null) {
        val extra = if (!reason.isNullOrBlank()) " (reason: $reason)" else ""
        record("STATE", "$from -> $to$extra")
    }

    fun recordServiceActiveOutcome(attempted: Boolean, reason: String) {
        record("FGS_CHECK", "attempted=$attempted, detail=$reason")
    }

    fun recordException(t: Throwable, contextMsg: String) {
        val topFrames = t.stackTrace.take(5).joinToString(" | ") { frame ->
            "${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
        }
        val safeMsg = t.message?.take(150)?.replace("\n", " ") ?: "no message"
        record("ERROR", "[$contextMsg] ${t.javaClass.simpleName}: $safeMsg; Frames: $topFrames")
    }

    private fun addEntry(entry: String) {
        synchronized(lock) {
            while (entries.size >= MAX_ENTRIES) {
                entries.pollFirst()
            }
            entries.addLast(entry)
            persistEntriesAsync()
        }
    }

    private fun loadPersistedEntries() {
        val file = logFile ?: return
        if (!file.exists()) return
        try {
            val lines = file.readLines().takeLast(MAX_ENTRIES)
            entries.clear()
            entries.addAll(lines)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not load persisted breadcrumbs: ${t.message}")
        }
    }

    private fun persistEntriesAsync() {
        val file = logFile ?: return
        try {
            val snapshot = ArrayList(entries)
            file.writeText(snapshot.joinToString("\n") + "\n")
        } catch (_: Throwable) {
            // Non-blocking disk failure suppression
        }
    }

    fun getBreadcrumbs(): List<String> {
        synchronized(lock) {
            return ArrayList(entries)
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            logFile?.delete()
        }
    }

    /**
     * Chains (does NOT replace) the default uncaught exception handler to capture crashes.
     */
    fun installUncaughtExceptionHandler() {
        if (isHandlerInstalled) return
        synchronized(lock) {
            if (isHandlerInstalled) return
            isHandlerInstalled = true
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    recordException(throwable, "UncaughtException in thread '${thread.name}'")
                } catch (_: Throwable) {
                } finally {
                    previousHandler?.uncaughtException(thread, throwable)
                }
            }
        }
    }
}
