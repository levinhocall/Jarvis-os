package com.jarvis.assistant.ui.memory

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.databinding.ActivityMemoryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts
import com.jarvis.assistant.data.memory.MemoryBackupPolicy
import java.util.Locale

class MemoryActivity : AppCompatActivity() {
    private val storageErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
        val message = com.jarvis.assistant.core.diagnostics.StorageHealth.record(error)
        runOnUiThread { if (::binding.isInitialized) { binding.tvNoMemories.visibility = View.VISIBLE; binding.tvNoMemories.text = message } }
    }

    private lateinit var binding: ActivityMemoryBinding
    private val memoryRepo by lazy { JarvisApp.instance.memoryRepository }
    private lateinit var adapter: MemoryAdapter

    private var allMemories: List<MemoryEntity> = emptyList()
    private var selectedCategory: String = "All"
    private var searchQuery: String = ""

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) transferBackup(uri, export = true)
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) transferBackup(uri, export = false)
    }

    private fun transferBackup(uri: android.net.Uri, export: Boolean) {
        if (!MemoryBackupPolicy.isOnDeviceDocument(uri.scheme, uri.authority)) {
            Toast.makeText(this, "Choose an on-device Documents or Downloads location. Cloud providers are disabled for memory backups.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch(storageErrors) {
            try {
                val message = withContext(Dispatchers.IO) {
                    if (export) {
                        val json = memoryRepo.exportMemoriesAsJson()
                        val output = contentResolver.openOutputStream(uri, "wt") ?: error("Output unavailable")
                        output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        "Memory backup saved on this device"
                    } else {
                        val input = contentResolver.openInputStream(uri) ?: error("Input unavailable")
                        val json = input.use { MemoryBackupPolicy.readBoundedJson(it) }
                        val count = memoryRepo.importMemoriesFromJson(json)
                        if (count > 0) "Imported $count memories" else "No valid active memories were imported"
                    }
                }
                Toast.makeText(this@MemoryActivity, message, Toast.LENGTH_LONG).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@MemoryActivity, "Memory backup could not be processed", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMemoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setupToolbar()
        setupRecyclerView()
        setupCategoryChips()
        setupSearch()
        observeMemories()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddMemory.setOnClickListener { showAddMemoryDialog() }
        binding.btnImportMemories.setOnClickListener { importBackup.launch(arrayOf("application/json", "text/plain")) }
        binding.btnExportMemories.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Export private memories?")
                .setMessage("This creates a readable JSON backup on this device. Anyone with access to the file can read it. Cloud destinations are disabled.")
                .setPositiveButton("EXPORT") { _, _ -> exportBackup.launch("jarvis-memory.json") }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    private fun setupRecyclerView() {
        adapter = MemoryAdapter(
            onEdit = { memory -> showEditMemoryDialog(memory) },
            onDelete = { memory -> confirmDeleteMemory(memory) }
        )
        binding.rvMemories.layoutManager = LinearLayoutManager(this)
        binding.rvMemories.adapter = adapter
    }

    private fun setupCategoryChips() {
        val chips = listOf(
            Pair(binding.chipCategoryAll, "All"),
            Pair(binding.chipCategoryPersonal, MemoryEntity.CATEGORY_PERSONAL),
            Pair(binding.chipCategoryPreferences, MemoryEntity.CATEGORY_PREFERENCES),
            Pair(binding.chipCategoryContacts, MemoryEntity.CATEGORY_CONTACTS),
            Pair(binding.chipCategoryRoutines, MemoryEntity.CATEGORY_ROUTINES),
            Pair(binding.chipCategoryNotes, MemoryEntity.CATEGORY_NOTES)
        )

        for ((view, category) in chips) {
            view.setOnClickListener {
                selectedCategory = category
                updateChipStyles(chips, view)
                filterAndDisplay()
            }
        }
    }

    private fun updateChipStyles(chips: List<Pair<TextView, String>>, selectedView: TextView) {
        val tvAccent = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
        val accentColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.primary_cyan)

        val tvSecondary = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextSecondary, tvSecondary, true)
        val secondaryColor = if (tvSecondary.data != 0) tvSecondary.data else ContextCompat.getColor(this, R.color.text_secondary)

        for ((view, _) in chips) {
            if (view == selectedView) {
                view.setBackgroundResource(R.drawable.bg_chip_selected)
                view.setTextColor(accentColor)
            } else {
                view.setBackgroundResource(R.drawable.bg_chip_unselected)
                view.setTextColor(secondaryColor)
            }
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                filterAndDisplay()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeMemories() {
        lifecycleScope.launch(storageErrors) {
            memoryRepo.getAllMemoriesFlow().collectLatest { memories ->
                allMemories = memories
                filterAndDisplay()
            }
        }
    }

    private fun filterAndDisplay() {
        val filtered = allMemories.filter { mem ->
            val matchesCategory = (selectedCategory == "All" || mem.category.equals(selectedCategory, ignoreCase = true))
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                mem.rawText.contains(searchQuery, ignoreCase = true) ||
                mem.key.contains(searchQuery, ignoreCase = true) ||
                mem.category.contains(searchQuery, ignoreCase = true)
            }
            matchesCategory && matchesSearch
        }

        binding.tvMemoryCount.text = "SAVED MEMORIES (${filtered.size})"
        adapter.submitList(filtered)

        if (filtered.isEmpty()) {
            binding.tvNoMemories.visibility = View.VISIBLE
        } else {
            binding.tvNoMemories.visibility = View.GONE
        }
    }

    private fun showAddMemoryDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MemoryActivity,
                android.R.layout.simple_spinner_dropdown_item,
                MemoryEntity.ALL_CATEGORIES
            )
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(16, 16, 16, 16)
        }

        val tvPrimary = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextPrimary, tvPrimary, true)
        val textPrimaryColor = if (tvPrimary.data != 0) tvPrimary.data else ContextCompat.getColor(this@MemoryActivity, R.color.text_primary)

        val tvMuted = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextMuted, tvMuted, true)
        val textMutedColor = if (tvMuted.data != 0) tvMuted.data else ContextCompat.getColor(this@MemoryActivity, R.color.text_muted)

        val editText = EditText(this).apply {
            hint = "e.g., My favorite song is Believer"
            setTextColor(textPrimaryColor)
            setHintTextColor(textMutedColor)
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }

        layout.addView(spinner)
        layout.addView(editText)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add New Memory")
            .setView(layout)
            .setPositiveButton("SAVE") { _, _ ->
                val fact = editText.text.toString().trim()
                val category = spinner.selectedItem.toString()
                if (fact.isNotBlank()) {
                    lifecycleScope.launch(storageErrors) {
                        memoryRepo.saveMemory(
                            rawText = fact,
                            category = category,
                            key = fact.take(30).trim(),
                            value = fact
                        )
                        Toast.makeText(this@MemoryActivity, "Memory saved", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showEditMemoryDialog(memory: MemoryEntity) {
        val tvPrimary = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextPrimary, tvPrimary, true)
        val textPrimaryColor = if (tvPrimary.data != 0) tvPrimary.data else ContextCompat.getColor(this@MemoryActivity, R.color.text_primary)

        val editText = EditText(this).apply {
            setText(memory.rawText)
            setTextColor(textPrimaryColor)
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(24, 24, 24, 24)
        }

        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 16)
            addView(editText)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Memory")
            .setView(container)
            .setPositiveButton("UPDATE") { _, _ ->
                val newFact = editText.text.toString().trim()
                if (newFact.isNotBlank()) {
                    lifecycleScope.launch(storageErrors) {
                        val updated = withContext(Dispatchers.IO) {
                            memoryRepo.correct(memory.id, newFact, expectedMemory = memory)
                        }
                        Toast.makeText(this@MemoryActivity,
                            if (updated) "Memory updated" else "Memory changed or was deleted. Reopen it before editing.",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirmDeleteMemory(memory: MemoryEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Memory")
            .setMessage("Are you sure you want JARVIS to forget this fact?\n\n\"${memory.rawText}\"")
            .setPositiveButton("DELETE") { _, _ ->
                lifecycleScope.launch(storageErrors) {
                    memoryRepo.deleteMemoryById(memory.id)
                    Toast.makeText(this@MemoryActivity, "Memory deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("ERASE PERMANENTLY") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Erase this fact permanently?")
                    .setMessage("This removes all stored versions of this fact. Existing exports are unaffected.")
                    .setPositiveButton("ERASE") { _, _ ->
                        lifecycleScope.launch(storageErrors) {
                            try {
                                val removed = memoryRepo.permanentlyDeleteMemoryById(memory.id)
                                Toast.makeText(this@MemoryActivity, if (removed > 0) "Stored fact erased" else "Fact was already removed", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {
                                Toast.makeText(this@MemoryActivity, "Could not erase the fact", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
