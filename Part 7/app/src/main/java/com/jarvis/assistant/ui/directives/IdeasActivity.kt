package com.jarvis.assistant.ui.directives

import android.Manifest
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.directives.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

class IdeasActivity : DirectiveActivity() {
    private val failures = kotlinx.coroutines.CoroutineExceptionHandler { _, _ ->
        if (::state.isInitialized) state.text = "The operation could not finish. Stored data has not been reset; please retry."
    }
    private val app get() = application as JarvisApp
    private val dao get() = app.memoryDatabase.directiveDao()
    private val visibleLimit = MutableStateFlow(100)
    private lateinit var draft: EditText
    private lateinit var state: TextView
    private lateinit var dictation: LocalDictation
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) dictation.start() else state.text = "Microphone permission denied. Text capture remains available."
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        heading("My Ideas")
        label("Private thoughts stay in the phone's encrypted vault. AI refinements are stored separately from your original words.")
        draft = input("Capture an idea…").apply { isSaveEnabled = false }
        this.state = label("Text capture is ready. Voice uses on-device recognition only.")
        dictation = LocalDictation(this, { draft.append(if (draft.text.isBlank()) it else "\n$it") }, { this.state.text = it })
        action("Dictate privately") { mic.launch(Manifest.permission.RECORD_AUDIO) }
        action("Save idea") { button ->
            val text = draft.text.toString().trim()
            if (text.isBlank() || text.length > 8000) { notice("Enter an idea between 1 and 8,000 characters."); return@action }
            button.isEnabled = false
            lifecycleScope.launch(failures) {
                try { dao.addIdea(IdeaEntity(content = text)); draft.text.clear(); this@IdeasActivity.state.text = "Idea saved in the encrypted local vault." }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { this@IdeasActivity.state.text = "Could not save. Your draft has been kept." }
                finally { button.isEnabled = true }
            }
        }
        val background = SwitchMaterial(this).apply {
            text = "Background AI refinements (optional)"; isChecked = app.preferences.ideasCloudReviewEnabled
        }
        page.addView(background)
        label("When enabled, up to 3 unreviewed ideas are sent to Gemini about every 12 hours, subject to Android scheduling, network and battery limits. Battery Saver defers reviews.", 12f)
        background.setOnCheckedChangeListener { _, enabled ->
            if (!enabled) {
                app.preferences.ideasCloudReviewEnabled = false
                IdeaReviewWorker.schedule(applicationContext, false)
            } else MaterialAlertDialogBuilder(this).setTitle("Allow background cloud review?")
                .setMessage("Idea text will leave this phone and be processed by your configured Gemini provider. Your original ideas remain stored locally. This may use paid API quota.")
                .setPositiveButton("Enable") { _, _ ->
                    app.preferences.ideasCloudReviewEnabled = true
                    IdeaReviewWorker.schedule(applicationContext, true)
                }.setNegativeButton("Keep local", null)
                .setOnDismissListener { if (!app.preferences.ideasCloudReviewEnabled) background.isChecked = false }.show()
        }
        val adapter = IdeaAdapter()
        page.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@IdeasActivity); this.adapter = adapter
        }, LinearLayout.LayoutParams(-1, dp(440)))
        action("Load more saved ideas") { visibleLimit.value += 100 }
        lifecycleScope.launch(failures) { repeatOnLifecycle(Lifecycle.State.STARTED) {
            visibleLimit.collectLatest { limit -> dao.observeIdeas(limit).collect { adapter.submitList(it) } }
        } }
    }
    private fun refine(idea: IdeaEntity) {
        MaterialAlertDialogBuilder(this).setTitle("Refine this idea with Gemini?")
            .setMessage("Only this idea is sent to the configured provider. The original stays unchanged in your encrypted vault.")
            .setNegativeButton("Cancel", null).setPositiveButton("Send for refinement") { _, _ ->
                lifecycleScope.launch(failures) {
                    state.text = "Reviewing selected idea…"
                    try {
                        val refinement = DirectiveAiClient(app.preferences).refineIdea(idea.content)
                        val updated = dao.saveRefinement(idea.id, idea.updatedAt, refinement)
                        state.text = if (updated == 1) "Refinement saved separately." else "Idea changed or was removed; stale refinement discarded."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { state.text = "Refinement unavailable. Check AI Provider Connections; the original idea is safe." }
                }
            }.show()
    }
    override fun onStop() { dictation.close(); super.onStop() }
    private inner class IdeaAdapter : androidx.recyclerview.widget.ListAdapter<IdeaEntity, IdeaHolder>(object : DiffUtil.ItemCallback<IdeaEntity>() {
        override fun areItemsTheSame(a: IdeaEntity, b: IdeaEntity) = a.id == b.id
        override fun areContentsTheSame(a: IdeaEntity, b: IdeaEntity) = a == b
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): IdeaHolder = IdeaHolder(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12))
        })
        override fun onBindViewHolder(holder: IdeaHolder, position: Int) { holder.bind(getItem(position)) }
    }
    private inner class IdeaHolder(private val box: LinearLayout) : RecyclerView.ViewHolder(box) {
        private val text = TextView(this@IdeasActivity).apply { setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextPrimary)); textSize = 16f }
        private val suggestion = TextView(this@IdeasActivity).apply { setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextSecondary)); setTextIsSelectable(true) }
        private val refine = Button(this@IdeasActivity).apply { text = "Refine with AI" }
        private val delete = Button(this@IdeasActivity).apply { text = "Delete" }
        init { box.addView(text); box.addView(suggestion); box.addView(refine); box.addView(delete) }
        fun bind(idea: IdeaEntity) {
            text.text = idea.content; suggestion.text = idea.refinement?.let { "AI refinement\n$it" }.orEmpty()
            refine.setOnClickListener { this@IdeasActivity.refine(idea) }
            delete.setOnClickListener {
                MaterialAlertDialogBuilder(this@IdeasActivity).setTitle("Delete this idea?")
                    .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ -> lifecycleScope.launch(failures) { dao.deleteIdea(idea.id) } }.show()
            }
        }
    }
}
