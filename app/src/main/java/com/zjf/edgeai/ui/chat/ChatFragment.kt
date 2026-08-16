package com.zjf.edgeai.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.zjf.edgeai.databinding.FragmentChatBinding
import com.zjf.edgeai.runtime.RuntimeState
import com.zjf.edgeai.ui.EdgeAiViewModel
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: EdgeAiViewModel by activityViewModels()
    private val adapter = ChatMessageAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.messageList.layoutManager = LinearLayoutManager(requireContext())
        binding.messageList.adapter = adapter
        binding.sendButton.setOnClickListener {
            val prompt = binding.promptInput.text?.toString().orEmpty()
            if (prompt.isNotBlank()) {
                viewModel.sendPrompt(prompt)
                binding.promptInput.text?.clear()
            }
        }
        binding.stopButton.setOnClickListener { viewModel.stopGeneration() }
        binding.clearButton.setOnClickListener { viewModel.clearConversation() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { ui ->
                    adapter.submitList(ui.messages)
                    if (ui.messages.isNotEmpty()) {
                        binding.messageList.scrollToPosition(ui.messages.lastIndex)
                    }
                    binding.emptyHint.visibility = if (ui.messages.isEmpty()) View.VISIBLE else View.GONE
                    val ready = ui.runtimeState is RuntimeState.Ready
                    val generating = ui.runtimeState is RuntimeState.Generating ||
                        ui.runtimeState is RuntimeState.Cancelling
                    binding.promptInput.isEnabled = ready
                    binding.sendButton.isEnabled = ready
                    binding.stopButton.isEnabled = generating
                    binding.clearButton.isEnabled = ui.messages.isNotEmpty() && !generating
                    binding.chatStatus.text = when {
                        generating -> "正在设备端生成…"
                        ready -> "模型已就绪 · 完全离线"
                        else -> "请先在模型页初始化模型"
                    }
                    ui.errorMessage?.let { message ->
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.messageList.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
