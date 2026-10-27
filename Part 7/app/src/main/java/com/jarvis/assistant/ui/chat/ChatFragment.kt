package com.jarvis.assistant.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.FragmentChatBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel

    private var pendingAttachment: com.jarvis.assistant.data.files.ProcessedAttachment? = null

    private val filePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val processed = com.jarvis.assistant.data.files.FileIntelligenceEngine.processFile(requireContext(), uri)
                pendingAttachment = processed
                val notice = if (processed.hasPromptInjection) {
                    "Attached '${processed.fileName}' (untrusted instructions sanitized)"
                } else {
                    "Attached: ${processed.fileName} (${processed.sizeBytes / 1024} KB)"
                }
                Toast.makeText(requireContext(), notice, Toast.LENGTH_LONG).show()
                binding.etMessageInput.hint = "📎 ${processed.fileName.take(15)}..."
            }
        }
    }

    private val chatAdapter = JarvisChatAdapter(
        onEditUserMessage = { text ->
            binding.etMessageInput.setText(text)
            binding.etMessageInput.setSelection(text.length)
            binding.etMessageInput.requestFocus()
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        // Setup Chat List
        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter

        // Send Message
        binding.btnSendMessage.setOnClickListener {
            sendMessage()
        }

        // New Chat / Clear
        binding.btnChatNew.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("New Conversation")
                .setMessage("Start a new conversation and clear current history?")
                .setPositiveButton("New Chat") { _, _ ->
                    JarvisApp.instance.chatRepository.clearHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Back / Menu
        binding.btnChatMenu.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME)
        }

        // Mode Chips
        binding.chipPersona.setOnClickListener {
            Toast.makeText(requireContext(), "Persona: Jarvis Active", Toast.LENGTH_SHORT).show()
        }

        binding.chipMode.setOnClickListener {
            Toast.makeText(requireContext(), "Mode: Code & Assistant", Toast.LENGTH_SHORT).show()
        }

        binding.btnAttach.setOnClickListener {
            try {
                filePickerLauncher.launch("*/*")
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        val prefs = (activity?.applicationContext as? JarvisApp)?.preferences
        val assistantName = prefs?.assistantName ?: "Jarvis"
        binding.tvChatTitle.text = assistantName
        binding.etMessageInput.hint = "Message $assistantName..."

        if (prefs?.isNewLookBetaEnabled == true && android.os.Build.VERSION.SDK_INT >= 33 && !prefs.isReduceMotionEnabled) {
            binding.chatHeaderOrb.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                com.jarvis.assistant.background.JarvisConversationService.conversationState.collect { state ->
                    binding.chatHeaderOrb.updateState(state)
                }
            }
        }

        observeChatTurns()
    }

    private fun sendMessage() {
        val text = binding.etMessageInput.text?.toString()?.trim() ?: ""
        if (text.isBlank() && pendingAttachment == null) return

        if (!JarvisConversationService.isServiceRunning()) {
            (activity as? MainActivity)?.handleMicOrOrbClick()
        }

        val fullMessage = if (pendingAttachment != null) {
            val att = pendingAttachment!!
            pendingAttachment = null
            binding.etMessageInput.hint = "Ask Jarvis anything..."
            "[FILE: ${att.fileName}]\n${att.summary}\n\n$text".trim()
        } else {
            text
        }

        // Direct Forge Intent recognition
        if (com.jarvis.assistant.forge.ForgeIntent.matches(fullMessage)) {
            val forgePrompt = com.jarvis.assistant.forge.ForgeIntent.extractPrompt(fullMessage)
            val ctx = requireContext()
            com.jarvis.assistant.forge.ForgeController.init(ctx)
            com.jarvis.assistant.forge.ForgeController.start(forgePrompt)
            val intent = Intent(ctx, com.jarvis.assistant.ui.forge.ForgeActivity::class.java).apply {
                putExtra(com.jarvis.assistant.ui.forge.ForgeActivity.EXTRA_PROMPT, forgePrompt)
            }
            startActivity(intent)
            binding.etMessageInput.text?.clear()
            return
        }

        JarvisConversationService.sendTextMessage(fullMessage)
        binding.etMessageInput.text?.clear()
    }

    private fun observeChatTurns() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chatTurns.collect { turns ->
                    chatAdapter.submitList(turns) {
                        if (turns.isNotEmpty()) {
                            binding.rvChatMessages.smoothScrollToPosition(turns.size - 1)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
