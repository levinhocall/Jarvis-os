package com.jarvis.assistant.ui.memory

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.memory.MemoryEntity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class RecallCoreBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "RecallCoreBottomSheet"
        fun show(fm: FragmentManager) {
            RecallCoreBottomSheet().show(fm, TAG)
        }
    }

    private var allMemories = listOf<MemoryEntity>()
    private var filteredMemories = listOf<MemoryEntity>()
    private lateinit var adapter: RecallMemoryAdapter
    private lateinit var emptyView: TextView
    private lateinit var searchInput: EditText

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#12131A"))
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
            }
        }

        // Header bar
        val header = RelativeLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val title = TextView(ctx).apply {
            text = "🧠 RECALL CORE • MEMORIES"
            textSize = 16f
            setTextColor(Color.parseColor("#E2E8F0"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val closeBtn = TextView(ctx).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { dismiss() }
            val lp = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = lp
        }
        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        // Search Input
        searchInput = EditText(ctx).apply {
            hint = "Search memories, prices, notes..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E202C"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        }
        root.addView(searchInput)

        // Empty view
        emptyView = TextView(ctx).apply {
            text = "No saved memories in Recall Core yet.\nAsk Jarvis to remember anything or scan products to log them here."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(32))
            visibility = View.GONE
        }
        root.addView(emptyView)

        // RecyclerView
        val rv = RecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(380))
            layoutManager = LinearLayoutManager(ctx)
        }
        adapter = RecallMemoryAdapter(
            onShare = { memory ->
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, memory.rawText)
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, "Share Memory"))
            },
            onDelete = { memory ->
                AlertDialog.Builder(ctx)
                    .setTitle("Delete Memory")
                    .setMessage("Remove '${memory.key}' from Recall Core?")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            JarvisApp.instance.memoryRepository.deleteMemoryById(memory.id)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        rv.adapter = adapter
        root.addView(rv)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            JarvisApp.instance.memoryRepository.getAllMemoriesFlow().collectLatest { list ->
                allMemories = list
                filterList(searchInput.text?.toString() ?: "")
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterList(query: String) {
        val q = query.trim().lowercase(Locale.ROOT)
        filteredMemories = if (q.isBlank()) {
            allMemories
        } else {
            allMemories.filter {
                it.rawText.lowercase(Locale.ROOT).contains(q) ||
                it.key.lowercase(Locale.ROOT).contains(q) ||
                it.category.lowercase(Locale.ROOT).contains(q)
            }
        }
        adapter.submitList(filteredMemories)
        emptyView.visibility = if (filteredMemories.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private class RecallMemoryAdapter(
        val onShare: (MemoryEntity) -> Unit,
        val onDelete: (MemoryEntity) -> Unit
    ) : RecyclerView.Adapter<RecallMemoryAdapter.ViewHolder>() {

        private var items = listOf<MemoryEntity>()

        fun submitList(newItems: List<MemoryEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ctx = parent.context
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12, ctx), dp(10, ctx), dp(12, ctx), dp(10, ctx))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1C28"))
                    cornerRadius = dp(10, ctx).toFloat()
                    setStroke(dp(1, ctx), Color.parseColor("#2E3348"))
                }
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, dp(8, ctx))
                }
            }

            // Top row: category pill + date
            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val catView = TextView(ctx).apply {
                textSize = 10f
                setTextColor(Color.parseColor("#38BDF8"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val dateView = TextView(ctx).apply {
                textSize = 10f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(dp(8, ctx), 0, 0, 0)
            }
            topRow.addView(catView)
            topRow.addView(dateView)
            card.addView(topRow)

            // Content text
            val content = TextView(ctx).apply {
                textSize = 13f
                setTextColor(Color.parseColor("#F1F5F9"))
                setPadding(0, dp(4, ctx), 0, dp(6, ctx))
            }
            card.addView(content)

            // Action row: Share and Delete
            val actions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.RIGHT
            }
            val shareBtn = TextView(ctx).apply {
                text = "Share"
                textSize = 11f
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(dp(8, ctx), dp(4, ctx), dp(12, ctx), dp(4, ctx))
            }
            val deleteBtn = TextView(ctx).apply {
                text = "Delete"
                textSize = 11f
                setTextColor(Color.parseColor("#EF4444"))
                setPadding(dp(8, ctx), dp(4, ctx), dp(4, ctx), dp(4, ctx))
            }
            actions.addView(shareBtn)
            actions.addView(deleteBtn)
            card.addView(actions)

            return ViewHolder(card, catView, dateView, content, shareBtn, deleteBtn)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.catView.text = item.category.uppercase(Locale.ROOT)
            holder.dateView.text = item.formattedDate
            holder.content.text = item.rawText
            holder.shareBtn.setOnClickListener { onShare(item) }
            holder.deleteBtn.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount(): Int = items.size

        private fun dp(v: Int, ctx: Context): Int = (v * ctx.resources.displayMetrics.density).toInt()

        class ViewHolder(
            view: View,
            val catView: TextView,
            val dateView: TextView,
            val content: TextView,
            val shareBtn: TextView,
            val deleteBtn: TextView
        ) : RecyclerView.ViewHolder(view)
    }
}
