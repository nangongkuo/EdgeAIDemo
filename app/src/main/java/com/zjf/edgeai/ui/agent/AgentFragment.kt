package com.zjf.edgeai.ui.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.databinding.FragmentAgentBinding
import com.zjf.edgeai.ui.EdgeAiUiState
import com.zjf.edgeai.ui.EdgeAiViewModel
import kotlinx.coroutines.launch

class AgentFragment : Fragment() {
    private var _binding: FragmentAgentBinding? = null
    private val binding get() = _binding!!
    private val viewModel: EdgeAiViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentAgentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.foregroundExecution.setOnCheckedChangeListener { _, enabled ->
            viewModel.setForegroundExecutionEnabled(enabled)
        }
        binding.localOnlyMode.setOnCheckedChangeListener { _, enabled ->
            viewModel.setLocalOnlyMode(enabled)
        }
        binding.approveOnce.setOnClickListener {
            viewModel.uiState.value.approvals.firstOrNull()?.let { approval ->
                viewModel.decideApproval(approval.approvalId, ApprovalDecision.APPROVE_ONCE)
            }
        }
        binding.approveSession.setOnClickListener {
            viewModel.uiState.value.approvals.firstOrNull()?.let { approval ->
                viewModel.decideApproval(approval.approvalId, ApprovalDecision.APPROVE_SESSION)
            }
        }
        binding.denyApproval.setOnClickListener {
            viewModel.uiState.value.approvals.firstOrNull()?.let { approval ->
                viewModel.decideApproval(approval.approvalId, ApprovalDecision.DENY)
            }
        }
        binding.submitInput.setOnClickListener {
            viewModel.uiState.value.pendingInputs.firstOrNull()?.let { pending ->
                viewModel.continueInput(pending.requestId, binding.continuationInput.text?.toString().orEmpty())
                binding.continuationInput.text?.clear()
            }
        }
        binding.cancelRun.setOnClickListener { viewModel.cancelActiveRun() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(ui: EdgeAiUiState) = with(binding) {
        if (foregroundExecution.isChecked != ui.foregroundExecutionEnabled) {
            foregroundExecution.isChecked = ui.foregroundExecutionEnabled
        }
        if (localOnlyMode.isChecked != ui.localOnlyMode) {
            localOnlyMode.isChecked = ui.localOnlyMode
        }
        runSummary.text = ui.activeRunId?.let { runId ->
            "Run：${runId.value}\n状态：${ui.agentRunState?.name ?: "CREATED"}"
        } ?: "当前没有 Agent 任务"
        workerGraph.text = if (ui.workers.isEmpty()) {
            "无逻辑 Worker；简单请求走单 Agent 快速路径"
        } else ui.workers.sortedBy { it.depth }.joinToString("\n") { worker ->
            "${"  ".repeat(worker.depth)}↳ ${worker.agentId.value} · ${worker.state.name}" +
                worker.outputSummary?.let { "\n${"  ".repeat(worker.depth + 1)}${it.take(240)}" }.orEmpty()
        }
        val approval = ui.approvals.firstOrNull()
        approvalPanel.visibility = if (approval == null) View.GONE else View.VISIBLE
        approvalText.text = approval?.let {
            "${it.riskLevel.name} · ${it.capabilityId.value}\n${it.reason}\n参数预览：${it.argumentsJson}"
        }.orEmpty()
        val pending = ui.pendingInputs.firstOrNull()
        inputPanel.visibility = if (pending == null) View.GONE else View.VISIBLE
        inputPrompt.text = pending?.let {
            it.prompt + it.choices.takeIf(List<String>::isNotEmpty)?.joinToString(
                prefix = "\n可选：", separator = " / ",
            ).orEmpty()
        }.orEmpty()
        artifactText.text = if (ui.artifacts.isEmpty()) "暂无 Artifact" else {
            ui.artifacts.joinToString("\n") { "${it.name} · ${it.mimeType} · ${it.sizeBytes} B\n${it.sha256}" }
        }
        timelineText.text = if (ui.timeline.isEmpty()) "暂无事件" else ui.timeline.joinToString("\n")
        capabilityText.text = ui.capabilitySummary.joinToString("\n") { "• $it" }
        cancelRun.isEnabled = ui.activeRunId != null && ui.agentRunState?.terminal != true
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
