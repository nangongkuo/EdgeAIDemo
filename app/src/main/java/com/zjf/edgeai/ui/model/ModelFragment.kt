package com.zjf.edgeai.ui.model

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.zjf.edgeai.R
import com.zjf.edgeai.databinding.FragmentModelBinding
import com.zjf.edgeai.runtime.RuntimeBackend
import com.zjf.edgeai.runtime.RuntimeState
import com.zjf.edgeai.runtime.model.asReadableSize
import com.zjf.edgeai.ui.EdgeAiViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ModelFragment : Fragment() {
    private var _binding: FragmentModelBinding? = null
    private val binding get() = _binding!!
    private val viewModel: EdgeAiViewModel by activityViewModels()

    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentModelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.importButton.setOnClickListener {
            openModel.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
        }
        binding.initializeButton.setOnClickListener { viewModel.initializeModel() }
        binding.unloadButton.setOnClickListener { viewModel.unloadModel() }
        binding.deleteButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_model)
                .setMessage(R.string.delete_model_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_model) { _, _ -> viewModel.deleteModel() }
                .show()
        }
        binding.backendGroup.setOnCheckedChangeListener { _, checkedId ->
            viewModel.setPreferredBackend(
                if (checkedId == R.id.cpuRadio) RuntimeBackend.CPU else RuntimeBackend.GPU
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { ui ->
                    val model = ui.model
                    binding.modelName.text = model?.displayName ?: getString(R.string.no_model)
                    binding.modelDetails.text = model?.let {
                        getString(
                            R.string.model_details,
                            it.sizeBytes.asReadableSize(),
                            it.sha256,
                            DateFormat.getDateTimeInstance().format(Date(it.importedAtEpochMillis))
                        )
                    } ?: getString(R.string.model_import_hint)

                    if (ui.preferredBackend == RuntimeBackend.CPU) {
                        binding.cpuRadio.isChecked = true
                    } else {
                        binding.gpuRadio.isChecked = true
                    }
                    binding.runtimeStatus.text = ui.runtimeState.asDisplayText()
                    binding.importStatus.text = ui.importStatus.orEmpty()
                    binding.importProgress.visibility = if (ui.isImporting) View.VISIBLE else View.GONE
                    binding.importProgress.isIndeterminate = ui.importProgress == null
                    ui.importProgress?.let { binding.importProgress.progress = it }

                    val busy = ui.isImporting || ui.runtimeState is RuntimeState.Initializing ||
                        ui.runtimeState is RuntimeState.Generating || ui.runtimeState is RuntimeState.Cancelling
                    binding.importButton.isEnabled = !busy
                    binding.initializeButton.isEnabled = model != null && !busy
                    binding.unloadButton.isEnabled = ui.runtimeState !is RuntimeState.Unloaded && !ui.isImporting
                    binding.deleteButton.isEnabled = model != null && !busy
                    binding.backendGroup.isEnabled = !busy
                    binding.gpuRadio.isEnabled = !busy
                    binding.cpuRadio.isEnabled = !busy

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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun RuntimeState.asDisplayText(): String = when (this) {
        RuntimeState.Unloaded -> getString(R.string.runtime_unloaded)
        is RuntimeState.Initializing -> getString(R.string.runtime_initializing, preferredBackend.name)
        is RuntimeState.Ready -> if (fallbackReason == null) {
            getString(R.string.runtime_ready, effectiveBackend.name)
        } else {
            getString(R.string.runtime_ready_fallback, effectiveBackend.name, fallbackReason)
        }
        is RuntimeState.Generating -> getString(R.string.runtime_generating, effectiveBackend.name)
        is RuntimeState.Cancelling -> getString(R.string.runtime_cancelling)
        is RuntimeState.Error -> getString(R.string.runtime_error, message)
    }
}
