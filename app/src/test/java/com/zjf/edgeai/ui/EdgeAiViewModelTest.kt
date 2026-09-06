package com.zjf.edgeai.ui

import android.net.Uri
import com.zjf.edgeai.runtime.EdgeAiRuntime
import com.zjf.edgeai.runtime.GenerationEvent
import com.zjf.edgeai.runtime.RuntimeBackend
import com.zjf.edgeai.runtime.RuntimeDiagnostics
import com.zjf.edgeai.runtime.RuntimeState
import com.zjf.edgeai.runtime.model.ModelDescriptor
import com.zjf.edgeai.runtime.model.ModelImportEvent
import com.zjf.edgeai.runtime.model.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EdgeAiViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun streamingDeltasUpdateOneAssistantMessage() = runTest {
        val runtime = FakeRuntime().apply {
            generation = flowOf(
                GenerationEvent.Delta("设备端 "),
                GenerationEvent.Delta("AI"),
                GenerationEvent.Completed(diagnostics.value)
            )
        }
        val viewModel = EdgeAiViewModel(FakeModelStore(selected = testModel), runtime)

        viewModel.sendPrompt("你好，Edge AI")
        advanceUntilIdle()

        val messages = viewModel.uiState.value.messages
        assertEquals(2, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
        assertEquals("你好，Edge AI", messages[0].text)
        assertEquals(ChatRole.ASSISTANT, messages[1].role)
        assertEquals("设备端 AI", messages[1].text)
        assertFalse(messages[1].streaming)
    }

    @Test
    fun mixedLanguagePromptIsForwardedAndRenderedWithoutRewriting() = runTest {
        val runtime = FakeRuntime().apply {
            generation = flowOf(GenerationEvent.Delta("这是中文说明。"))
        }
        val viewModel = EdgeAiViewModel(FakeModelStore(selected = testModel), runtime)
        val prompt = "  请 explain JVM 的 GC，并保留 Java  "

        viewModel.sendPrompt(prompt)
        advanceUntilIdle()

        assertEquals(listOf(prompt), runtime.receivedPrompts)
        assertEquals(prompt, viewModel.uiState.value.messages.first().text)
        assertEquals(ChatRole.USER, viewModel.uiState.value.messages.first().role)
        assertEquals("这是中文说明。", viewModel.uiState.value.messages.last().text)
        assertEquals(ChatRole.ASSISTANT, viewModel.uiState.value.messages.last().role)
    }

    @Test
    fun allWhitespacePromptDoesNotStartGeneration() = runTest {
        val runtime = FakeRuntime()
        val viewModel = EdgeAiViewModel(FakeModelStore(selected = testModel), runtime)

        viewModel.sendPrompt(" \n\t ")
        advanceUntilIdle()

        assertTrue(runtime.receivedPrompts.isEmpty())
        assertTrue(viewModel.uiState.value.messages.isEmpty())
    }

    @Test
    fun generationFailureIsRenderedAndExposed() = runTest {
        val runtime = FakeRuntime().apply {
            generation = flowOf(GenerationEvent.Failed("native failure"))
        }
        val viewModel = EdgeAiViewModel(FakeModelStore(selected = testModel), runtime)

        viewModel.sendPrompt("hello")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("native failure", state.errorMessage)
        assertTrue(state.messages.last().text.contains("native failure"))
        assertFalse(state.messages.last().streaming)
    }

    @Test
    fun clearConversationResetsRuntimeAndMessages() = runTest {
        val runtime = FakeRuntime().apply {
            generation = flowOf(GenerationEvent.Delta("answer"))
        }
        val viewModel = EdgeAiViewModel(FakeModelStore(selected = testModel), runtime)
        viewModel.sendPrompt("hello")
        advanceUntilIdle()

        viewModel.clearConversation()
        advanceUntilIdle()

        assertTrue(runtime.resetCalled)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
    }

    @Test
    fun startupInstallsBundledModelAndInitializesGpu() = runTest {
        val modelStore = FakeModelStore(
            selected = null,
            bundledEvents = flowOf(
                ModelImportEvent.Started(testModel.displayName, testModel.sizeBytes),
                ModelImportEvent.Progress(testModel.sizeBytes, testModel.sizeBytes),
                ModelImportEvent.Completed(testModel)
            )
        )
        val runtime = FakeRuntime(initialState = RuntimeState.Unloaded)

        val viewModel = EdgeAiViewModel(modelStore, runtime)
        advanceUntilIdle()

        assertEquals(testModel, runtime.initializedModel)
        assertEquals(RuntimeBackend.GPU, runtime.initializedBackend)
        assertEquals("模型已自动初始化", viewModel.uiState.value.importStatus)
        assertFalse(viewModel.uiState.value.isImporting)
    }

    private class FakeModelStore(
        selected: ModelDescriptor?,
        private val bundledEvents: Flow<ModelImportEvent> = emptyFlow()
    ) : ModelStore {
        override val selectedModel: StateFlow<ModelDescriptor?> = MutableStateFlow(selected)
        override fun installBundledModel(): Flow<ModelImportEvent> = bundledEvents
        override fun importModel(uri: Uri): Flow<ModelImportEvent> = emptyFlow()
        override suspend fun deleteSelectedModel() = Unit
    }

    private class FakeRuntime(initialState: RuntimeState = readyState) : EdgeAiRuntime {
        companion object {
            private val readyState = RuntimeState.Ready(RuntimeBackend.GPU, RuntimeBackend.GPU)
        }

        private val ready = RuntimeState.Ready(RuntimeBackend.GPU, RuntimeBackend.GPU)
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<RuntimeState> = mutableState
        override val diagnostics: StateFlow<RuntimeDiagnostics> = MutableStateFlow(
            RuntimeDiagnostics(
                deviceManufacturer = "test",
                deviceModel = "test",
                androidApi = 31,
                supportedAbis = listOf("arm64-v8a"),
                preferredBackend = RuntimeBackend.GPU,
                effectiveBackend = RuntimeBackend.GPU
            )
        )
        var generation: Flow<GenerationEvent> = emptyFlow()
        val receivedPrompts = mutableListOf<String>()
        var resetCalled = false
        var initializedModel: ModelDescriptor? = null
        var initializedBackend: RuntimeBackend? = null

        override suspend fun initialize(model: ModelDescriptor, preferred: RuntimeBackend) {
            initializedModel = model
            initializedBackend = preferred
            mutableState.value = ready
        }
        override fun generate(prompt: String): Flow<GenerationEvent> {
            receivedPrompts += prompt
            return generation
        }
        override fun cancel() = Unit
        override suspend fun resetConversation() {
            resetCalled = true
        }
        override suspend fun unload() = Unit
        override fun close() = Unit
    }

    private companion object {
        val testModel = ModelDescriptor(
            displayName = "gemma.litertlm",
            absolutePath = "/tmp/gemma.litertlm",
            sizeBytes = 1024L,
            sha256 = "abc123",
            importedAtEpochMillis = 1L
        )
    }
}
