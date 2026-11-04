package com.jarvis.assistant.ui.aura

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.jarvis.assistant.ui.directives.DirectiveActivity
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class DecisionLogsActivity : DirectiveActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); heading("What AI Decided")
        label("Encrypted local history. Latest 100 checks shown; 500 retained. Android scheduling is inexact.")
        val output=label("Loading…")
        lifecycleScope.launch(com.jarvis.assistant.core.diagnostics.StorageHealth.handler) { repeatOnLifecycle(Lifecycle.State.STARTED) {
            JarvisApp.instance.memoryDatabase.directiveDao().observeDecisions().collect { rows ->
                output.text=rows.joinToString("\n\n") { "${DateFormat.getDateTimeInstance().format(Date(it.createdAt))} · ${it.action}\n${it.reason}" }.ifEmpty { "No autonomous checks yet." }
            }
        } }
    }
}
