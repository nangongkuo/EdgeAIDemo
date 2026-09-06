## 1. Multilingual runtime policy

- [x] 1.1 Add the project-owned Chinese-first language policy and deterministic sampler configuration in `edge-ai`.
- [x] 1.2 Use the policy for every LiteRT-LM Conversation creation path without changing native lifecycle or backend fallback behavior.
- [x] 1.3 Remove the current-turn `wrapPrompt` transformation so `Conversation.sendMessageAsync()` receives only the original nonblank user prompt; retain the Conversation-level system instruction and sampler configuration.

## 2. Chat interaction and disclosure

- [x] 2.1 Forward each nonblank user prompt unchanged through the ViewModel and retain it unchanged in the chat history.
- [x] 2.2 Add the Chinese/English/mixed-input and default-Simplified-Chinese disclosure to the chat tab.

## 3. Automated regression coverage

- [x] 3.1 Add unit tests for the language policy, raw mixed-language prompt forwarding, CJK streaming updates, and reset behavior.
- [x] 3.2 Add an ARM64 physical-device-only offline multilingual inference acceptance test using the bundled model.
- [x] 3.3 Add regressions for exact LiteRT-LM user-turn payload forwarding and protection against a normalized assistant echo of a representative Chinese question. Required: BI-1 and BI-2.

## 4. Validation and delivery gate

- [x] 4.1 Run OpenSpec strict validation, unit tests, lint, and debug/APK build checks.
- [ ] 4.2 Run the fixed Chinese, English, mixed-language, literal-content, multi-turn, and reset corpus on the connected Android 12+ ARM64 device; keep this task incomplete if the model fails the language contract.
- [x] 4.3 Run the direct-answer regression corpus on the connected Android 12+ ARM64 device with the no-`INTERNET`-permission application: capability question, English question, and mixed-language question. Capture screenshot, UI hierarchy, and relevant log; require a non-echo Chinese capability reply. Required: BI-4. The corpus verifies prompt routing and response generation, not model arithmetic or factual accuracy.
