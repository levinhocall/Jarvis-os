package com.jarvis.assistant.util

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches best-effort background persistence (e.g. writing a security alert or ledger entry to
 * memory) inside a scope that someone else owns.
 *
 * - Cancellation is never swallowed: if the owning scope is cancelled, the work is cancelled and
 *   `finally` blocks in [block] run.
 * - Any other failure is contained and logged by exception class only. Messages are not logged
 *   because they may carry user or financial text.
 */
internal fun CoroutineScope.launchPersisting(
    tag: String,
    what: String,
    block: suspend () -> Unit,
): Job = launch {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(tag, "$what failed (${error.javaClass.simpleName})")
    }
}

/**
 * Runs [block] on the application-owned supervised scope (`JarvisApp.applicationScope`), so
 * required persistence is not tied to a screen or service lifecycle and one failure cannot affect
 * unrelated work. Returns null (and does nothing) if the application context is not [JarvisApp],
 * which matches `RecallCoreManager`, itself a no-op in that case.
 */
internal fun launchPersistingOnAppScope(
    context: Context,
    tag: String,
    what: String,
    block: suspend () -> Unit,
): Job? {
    val scope = (context.applicationContext as? JarvisApp)?.applicationScope ?: return null
    return scope.launchPersisting(tag, what, block)
}
