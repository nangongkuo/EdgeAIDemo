package com.zjf.edgeai.ui.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zjf.edgeai.databinding.FragmentDiagnosticsBinding
import com.zjf.edgeai.runtime.RuntimeDiagnostics
import com.zjf.edgeai.runtime.model.asReadableSize
import com.zjf.edgeai.ui.EdgeAiViewModel
import kotlinx.coroutines.launch

class DiagnosticsFragment : Fragment() {
    private var _binding: FragmentDiagnosticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: EdgeAiViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { ui ->
                    binding.diagnosticsText.text = ui.diagnostics?.asDisplayText() ?: "诊断信息不可用"
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun RuntimeDiagnostics.asDisplayText(): String = buildString {
        appendLine("环境")
        appendLine("  LiteRT-LM: $sdkVersion")
        appendLine("  设备: $deviceManufacturer $deviceModel")
        appendLine("  Android API: $androidApi")
        appendLine("  ABI: ${supportedAbis.joinToString()}")
        appendLine()
        appendLine("模型")
        appendLine("  名称: ${modelName ?: "未导入"}")
        appendLine("  大小: ${modelSizeBytes?.asReadableSize() ?: "N/A"}")
        appendLine("  SHA-256: ${modelSha256 ?: "N/A"}")
        appendLine()
        appendLine("运行时")
        appendLine("  首选后端: ${preferredBackend?.name ?: "N/A"}")
        appendLine("  实际后端: ${effectiveBackend?.name ?: "N/A"}")
        appendLine("  回退原因: ${fallbackReason ?: "无"}")
        appendLine()
        appendLine("BenchmarkInfo")
        appendLine("  初始化: ${initializationSeconds.seconds()}")
        appendLine("  首 Token: ${timeToFirstTokenSeconds.seconds()}")
        appendLine("  Prefill Token: ${prefillTokenCount ?: "N/A"}")
        appendLine("  Decode Token: ${decodeTokenCount ?: "N/A"}")
        appendLine("  Prefill: ${prefillTokensPerSecond.rate()}")
        appendLine("  Decode: ${decodeTokensPerSecond.rate()}")
        appendLine("  会话 Token: ${totalConversationTokens ?: "N/A"}")
        appendLine()
        appendLine("最近生成")
        appendLine("  请求 ID: ${lastRequestId ?: "N/A"}")
        appendLine("  尝试次数: ${lastGenerationAttempts ?: "N/A"}")
        appendLine("  拒绝过回声: ${lastResponseRejectedAsEcho.yesNo()}")
        appendLine("  显式思考: ${thinkingEnabled.enabledState()}")
    }

    private fun Double?.seconds() = this?.let { "%.3f s".format(it) } ?: "N/A"
    private fun Double?.rate() = this?.let { "%.2f tokens/s".format(it) } ?: "N/A"
    private fun Boolean?.yesNo() = this?.let { if (it) "是" else "否" } ?: "N/A"
    private fun Boolean?.enabledState() = this?.let { if (it) "开启" else "关闭" } ?: "N/A"
}
