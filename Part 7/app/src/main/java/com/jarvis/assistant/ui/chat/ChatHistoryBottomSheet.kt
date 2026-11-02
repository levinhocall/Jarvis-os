package com.jarvis.assistant.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.data.chat.ConversationTurnMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.databinding.SheetChatHistoryBinding

class ChatHistoryBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetChatHistoryBinding? = null
    private val binding get() = _binding!!
    private val adapter = ChatAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetChatHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvChatHistory.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatHistory.adapter = adapter

        val conversationRepository = JarvisApp.instance.conversationRepository
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                conversationRepository.getRecentMessagesFlow().collect { messages ->
                    updateList(ConversationTurnMapper.toTurns(messages.reversed()))
                }
            }
        }

        binding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear History")
                .setMessage("Are you sure you want to clear the conversation history?")
                .setPositiveButton("Clear") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                conversationRepository.clearAllConversationData {
                                    JarvisApp.instance.chatRepository.clearHistory()
                                }
                            }
                        }.onFailure { error ->
                            android.util.Log.e(TAG, "Could not clear Room conversation history", error)
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Could not clear conversation history. Please try again.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateList(turns: List<com.jarvis.assistant.data.model.ChatTurn>) {
        if (turns.isEmpty()) {
            adapter.submitList(emptyList())
            binding.tvEmptyHistory.visibility = View.VISIBLE
            binding.rvChatHistory.visibility = View.GONE
        } else {
            binding.tvEmptyHistory.visibility = View.GONE
            binding.rvChatHistory.visibility = View.VISIBLE
            adapter.submitList(turns) {
                binding.rvChatHistory.scrollToPosition(turns.size - 1)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ChatHistoryBottomSheet"
        fun newInstance() = ChatHistoryBottomSheet()
    }
}
