package com.jarvis.assistant.ui.documents

import android.Manifest
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.directives.DirectiveActivity
import com.jarvis.assistant.ui.directives.LocalDictation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentStudioActivity : DirectiveActivity() {
    class DraftState : ViewModel() {
        var title = ""
        var body = ""
        var pendingPdf: ByteArray? = null
    }

    private lateinit var draft: DraftState
    private lateinit var titleInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var dictation: LocalDictation

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) dictation.start()
        else if (!granted) notice(getString(R.string.studio_microphone_denied))
    }

    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val bytes = draft.pendingPdf
        draft.pendingPdf = null
        if (uri == null) return@registerForActivityResult
        if (bytes == null) {
            notice(getString(R.string.studio_export_failed))
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(contentResolver.openOutputStream(uri)).use { it.write(bytes) }
                }
                notice(getString(R.string.studio_export_saved))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice(getString(R.string.studio_export_failed))
            }
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        draft = ViewModelProvider(this)[DraftState::class.java]
        heading(getString(R.string.studio_title))
        titleInput = input(getString(R.string.studio_document_title), 1).apply {
            setSingleLine(true)
            isSaveEnabled = false
            filters = arrayOf(InputFilter.LengthFilter(120))
            setText(draft.title)
        }
        bodyInput = input(getString(R.string.studio_document_body), 8).apply {
            isSaveEnabled = false
            filters = arrayOf(InputFilter.LengthFilter(12_000))
            setText(draft.body)
        }
        dictation = LocalDictation(this, { transcript ->
            bodyInput.append(if (bodyInput.text.isEmpty()) transcript else "\n$transcript")
        }, ::notice)
        action(getString(R.string.studio_dictate)) { microphone.launch(Manifest.permission.RECORD_AUDIO) }
        action(getString(R.string.studio_export)) { button ->
            val title = titleInput.text.toString().trim()
            val body = bodyInput.text.toString().trim()
            if (title.isEmpty() || body.isEmpty()) {
                notice(getString(R.string.studio_empty))
                return@action
            }
            button.isEnabled = false
            lifecycleScope.launch {
                try {
                    draft.pendingPdf = withContext(Dispatchers.Default) { LocalPdfDocument.render(title, body) }
                    val fileName = title.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").trim('.').take(90)
                    createPdf.launch(fileName.ifBlank { "Jarvis document" } + ".pdf")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    draft.pendingPdf = null
                    notice(getString(R.string.studio_export_failed))
                } finally {
                    button.isEnabled = true
                }
            }
        }
    }

    override fun onStop() {
        draft.title = titleInput.text.toString()
        draft.body = bodyInput.text.toString()
        dictation.close()
        super.onStop()
    }
}
