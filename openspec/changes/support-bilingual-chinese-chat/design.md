## Context

`edge-ai` owns LiteRT-LM `Engine` and `Conversation` creation; `app` owns the XML chat UI and its activity-scoped ViewModel. The current conversation configuration contains only an English system instruction and samples at temperature 0.8. The ViewModel also trims the user text before forwarding it. Neither behavior establishes the required Chinese-first contract for English, Chinese, or code-switched requests.

The model is a locally bundled Gemma3-1B-IT int4 `.litertlm` asset. It must remain offline, ARM64-only, and isolated from UI code behind `EdgeAiRuntime`. All native calls continue on the runtime's single dispatcher, with the existing Engine/Conversation lifecycle and GPU-to-CPU fallback unchanged.

## Goals / Non-Goals

**Goals:**

- Establish one deterministic conversation policy that accepts raw mixed-language text and answers in Simplified Chinese by default.
- Preserve literal technical and quoted content while allowing an explicitly requested target language to take precedence.
- Apply the same policy for startup initialization, manual initialization, and every reset Conversation.
- Add UI disclosure and regression coverage, then validate actual inference offline on connected Android 12+ ARM64 hardware.

**Non-Goals:**

- No cloud translation, network language detection, third-party language SDK, or locale-driven rewrite.
- No user language selector, model replacement, tokenizer conversion, fine-tuning, or changes to GPU fallback and model storage.
- No claim that the 1B int4 model has production-grade translation quality; acceptance tests validate language behavior and stream stability only.

## Decisions

### Static Chinese-first policy in `edge-ai`

Introduce a project-owned `ChatLanguagePolicy` in `edge-ai`. It owns a Unicode-safe system-instruction string and the low-variance sampler configuration. `LiteRtLmRuntime` consumes it when creating `ConversationConfig`; the app module never builds prompts or imports LiteRT-LM classes.

The policy instructs the model to understand Chinese, English, and mixed sentences; answer in Simplified Chinese by default; retain source spelling for code, commands, URLs, filenames, product names, abbreviations, quoted text, and requested terms; and honor an explicit target-language or translation request. The ViewModel and `EdgeAiRuntime.generate()` carry the original string unchanged, and `LiteRtLmRuntime` supplies that exact string directly to `Conversation.sendMessageAsync()`.

### Direct-answer regression correction

**Root cause: confirmed.** On the connected ARM64 device, the completed assistant messages `你问我呢？`, `你能做什么呢？`, and `你是什么模型？` reproduce the user-visible symptom for the respective user messages. The UI hierarchy captures the same distinct user/assistant bubbles. The only production transformation between `EdgeAiViewModel.sendPrompt()` and `Conversation.sendMessageAsync()` is `ChatLanguagePolicy.wrapPrompt(prompt)`, which replaces the model's user turn with a multi-paragraph instruction containing `<user_prompt>…</user_prompt>`. This means the Gemma 3 1B model is asked to handle the user's question as nested text instead of as the direct chat turn, and it paraphrases that nested text. No UI adapter or ViewModel path writes assistant text from the user message.

The correction removes the model-facing envelope entirely. `ConversationConfig.systemInstruction` remains the sole policy carrier and the raw nonblank prompt becomes the complete `sendMessageAsync()` argument. This restores LiteRT-LM's role template and prevents the application from creating an echo-prone nested task. It intentionally does not introduce client-side answer synthesis, language detection, translation, or model replacement.

The current 1B model's observed failure to answer every post-reset English prompt in Chinese is independent of this regression and remains an incomplete acceptance item. Removing the envelope prioritizes direct answering over attempting to override that model limitation with a second user-turn instruction.

### Deterministic literal preservation fallback

The 1B model can omit an explicitly requested URL even when it answers in Chinese. While collecting streaming deltas, the runtime retains a local copy of the generated text. On completion it extracts URL, inline-code, common filename, all-caps abbreviation, and common shell-command spans from the untouched user prompt; it appends only the spans absent from the model output in an `原文保留` suffix. This is local deterministic preservation, not translation or a rewrite of either the user message or model explanation.

### Raw prompt forwarding

`EdgeAiViewModel` SHALL use `isBlank()` only to reject all-whitespace input, retain the original nonblank string for the user message, and pass that exact string to `EdgeAiRuntime.generate()`. This removes the existing `trim()` rewrite while keeping empty-input behavior.

### Reproducible sampling

Use the existing topK 10 and topP 0.95 with `temperature = 0.2` and `seed = 0`. Lower temperature is chosen to make language-policy adherence less stochastic in the bundled 1B quantized model. The 512-token output limit remains unchanged.

### Lifecycle and UI

`defaultConversationConfig()` remains the single creation path, so its policy is applied during automatic startup, manual reload, backend fallback retry, and `resetConversation()`. Each `generate()` call supplies its raw prompt directly to LiteRT-LM. The chat tab adds a non-interactive status disclosure stating that Chinese, English, and mixed input are accepted and replies default to Simplified Chinese. No language setting is persisted.

## Risks / Trade-offs

- [The 1B int4 model can still ignore an instruction] → validate the fixed corpus on a physical ARM64 device; do not complete the change if the language contract fails.
- [Lower temperature reduces creative variety] → this validation demo prioritizes deterministic language behavior over creativity.
- [Literal-span preservation is probabilistic model behavior] → constrain acceptance to representative code, URL, filename, abbreviation, and quoted-text cases; do not perform unsafe client-side transformations.
- [Policy changes could be omitted from a new Conversation] → retain one config factory and cover initialization/reset paths through tests and device acceptance.
- [The model omits a literal despite an explicit instruction] → append only the missing recognized span after streaming completes; keep the raw user message and model-produced explanation intact.
- [The 1B model ignores the Chinese policy after conversation reset] → leave the language acceptance task incomplete and evaluate a stronger LiteRT-LM model in a separate OpenSpec change before claiming full compatibility.
- [A current-turn language envelope makes a question appear to be quoted text] → never add a current-turn envelope; cover exact SDK payload forwarding in unit coverage and direct-answer behavior in the physical-device acceptance corpus.

## Test matrix

| ID | Layer | Required | Preconditions | Steps | Expected result | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| BI-1 | unit | yes | JVM test runtime | Run `:edge-ai:testDebugUnitTest` | The policy has no current-turn wrapper and a raw mixed prompt is unchanged at the runtime boundary helper. | Gradle output |
| BI-2 | app unit | yes | JVM test runtime | Run `:app:testDebugUnitTest` | ViewModel keeps original whitespace/CJK text and never writes a user message as an assistant response. | Gradle output |
| BI-3 | build/lint | yes | JDK 17 | Run `:app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | Debug and test artifacts compile; lint passes. | Gradle output |
| BI-4 | device/visual | yes | Android 12+ ARM64, bundled model initialized; application declares no `INTERNET` permission | Send `请用一句话说明你能做什么，不要复述这句话。` through the real LiteRT-LM runtime, then send an English and mixed-language prompt. Capture its test log plus a packaged-app startup screenshot and UI hierarchy. | Each stream completes; Chinese capability reply is not a normalized echo; no crash; input is still raw. The ViewModel UI message-role mapping is covered by BI-2 because AndroidX instrumentation cannot reliably idle the Activity while LiteRT performs startup initialization. This is a direct-answer regression test, not a benchmark of the 1B model's arithmetic or factual accuracy. | `build/bugfix-reports/support-bilingual-chinese-chat/` |
