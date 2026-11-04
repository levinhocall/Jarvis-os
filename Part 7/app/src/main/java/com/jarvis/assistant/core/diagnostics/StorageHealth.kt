package com.jarvis.assistant.core.diagnostics

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Errors are surfaced without clearing the database or exposing its contents. */
object StorageHealth {
    private val mutable = MutableStateFlow<String?>(null)
    val error = mutable.asStateFlow()
    fun record(error: Throwable): String {
        val cause = generateSequence(error) { it.cause }.last()
        val code = cause.javaClass.simpleName
        Log.e("JarvisStorage", "Local operation failed ($code); data retained", error)
        return "Local storage unavailable ($code). Your stored data has not been deleted. Retry or open Diagnostics.".also { mutable.value = it }
    }
    val handler = CoroutineExceptionHandler { _, error -> record(error) }
}
