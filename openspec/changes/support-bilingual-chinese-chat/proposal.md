## Why

The bundled Gemma 3 1B model accepts Unicode text, but the demo currently gives it an English-only system instruction and has no language-behavior contract. As a result, Chinese, English, and code-switched prompts can receive an unexpectedly English response, which makes the offline chat validation unsuitable for Chinese-speaking users.

## What Changes

- Define an offline chat language policy: accept Chinese, English, and mixed-language input without rewriting it, and answer in Simplified Chinese by default.
- Preserve code, commands, URLs, file names, product names, abbreviations, quoted text, and explicitly requested English terms verbatim; allow an explicit target-language request to override the default.
- Apply the policy to every LiteRT-LM Conversation, including automatic startup, model initialization, and conversation reset. The current user turn is sent to LiteRT-LM exactly as entered; only the Conversation-level system instruction carries the Chinese-first policy. This prevents the bundled 1B model from treating a nested prompt envelope as text to paraphrase. Lower temperature to improve consistency.
- Append only missing recognized literal spans to the completed local response so a small model cannot silently omit an explicitly supplied URL, command, filename, or abbreviation.
- Surface the supported input and default response language in the chat UI and add deterministic unit plus ARM64-device acceptance coverage.
- Restore direct answers for Chinese prompts that the current nested current-turn envelope causes the model to repeat or paraphrase.

## Capabilities

### New Capabilities

- `multilingual-chat-policy`: Defines raw multilingual prompt handling and the default Simplified-Chinese response contract for the on-device chat experience.

### Modified Capabilities

- None. `on-device-chat` currently exists only as an unarchived delta in the independent `integrate-litert-lm-demo` change, so this change records its language-policy contract separately rather than editing that active change.

## Impact

- Affects `LiteRtLmRuntime` conversation configuration, the chat-tab status copy, and the runtime/ViewModel tests.
- Keeps the existing Gemma3-1B-IT int4 bundled model, LiteRT-LM API, private model storage, offline operation, and ARM64-only packaging unchanged.
- Does not add network calls, language-detection SDKs, translation services, new models, or user-selectable language settings.
- Does not claim that the existing 1B model consistently honors the default-Chinese response policy for English prompts after a conversation reset; that separate acceptance failure remains open.
