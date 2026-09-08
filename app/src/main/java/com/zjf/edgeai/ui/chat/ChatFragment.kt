package com.zjf.edgeai.ui.chat

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.zjf.edgeai.databinding.FragmentChatBinding
import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.agent.api.ApprovalId
import com.zjf.edgeai.runtime.RuntimeState
import com.zjf.edgeai.ui.EdgeAiViewModel
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: EdgeAiViewModel by activityViewModels()
    private val adapter = ChatMessageAdapter()
    private var permissionApproval: Pair<ApprovalId, ApprovalDecision>? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val pending = permissionApproval
        permissionApproval = null
        val approvalId = pending?.first
            ?: viewModel.uiState.value.approvals.firstOrNull()?.approvalId
            ?: return@registerForActivityResult
        val approvedDecision = pending?.second ?: ApprovalDecision.APPROVE_ONCE
        val decision = if (result.values.all { it }) approvedDecision else ApprovalDecision.DENY
        viewModel.decideApproval(approvalId, decision)
    }

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
                val pending = viewModel.uiState.value.pendingInputs.firstOrNull()
                if (pending == null) viewModel.sendPrompt(prompt)
                else viewModel.continueInput(pending.requestId, prompt)
                binding.promptInput.text?.clear()
            }
        }
        binding.chatApprove.setOnClickListener {
            approveWithPermissions(ApprovalDecision.APPROVE_ONCE)
        }
        binding.chatDeny.setOnClickListener {
            viewModel.uiState.value.approvals.firstOrNull()?.let { approval ->
                viewModel.decideApproval(approval.approvalId, ApprovalDecision.DENY)
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
                    val runActive = ui.activeRunId != null && ui.agentRunState?.terminal != true
                    val approval = ui.approvals.firstOrNull()
                    binding.chatApprovalPanel.visibility = if (approval == null) View.GONE else View.VISIBLE
                    binding.chatApprovalText.text = approval?.let {
                        "需要确认：${it.capabilityId.value}\n${it.reason}\n参数预览：${it.argumentsJson}"
                    }.orEmpty()
                    val pendingInput = ui.pendingInputs.firstOrNull()
                    binding.chatInputRequest.visibility = if (pendingInput == null) View.GONE else View.VISIBLE
                    binding.chatInputRequest.text = pendingInput?.let {
                        it.prompt + it.choices.takeIf(List<String>::isNotEmpty)
                            ?.joinToString(prefix = "\n可选：", separator = " / ").orEmpty()
                    }.orEmpty()
                    val canSubmitText = ready && (!runActive || pendingInput != null)
                    binding.promptInput.isEnabled = canSubmitText
                    binding.sendButton.isEnabled = canSubmitText
                    binding.stopButton.isEnabled = runActive
                    binding.clearButton.isEnabled = ui.messages.isNotEmpty() && !runActive
                    binding.chatStatus.text = when {
                        approval != null -> "等待批准后写入系统日历"
                        pendingInput != null -> "等待补充信息"
                        generating -> "正在设备端生成…"
                        ready -> "模型已就绪 · 完全离线"
                        else -> "请先在模型页初始化模型"
                    }
                    ui.errorMessage?.let { message ->
                        Snackbar.make(
                            requireActivity().findViewById(android.R.id.content),
                            message,
                            Snackbar.LENGTH_LONG,
                        ).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun approveWithPermissions(decision: ApprovalDecision) {
        val approval = viewModel.uiState.value.approvals.firstOrNull() ?: return
        val missing = approval.requiredAndroidPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            viewModel.decideApproval(approval.approvalId, decision)
        } else {
            permissionApproval = approval.approvalId to decision
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroyView() {
        binding.messageList.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
