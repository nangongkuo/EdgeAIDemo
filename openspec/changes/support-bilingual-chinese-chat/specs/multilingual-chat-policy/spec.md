## ADDED Requirements

### Requirement: Transparent multilingual prompt handling
The application SHALL accept Chinese, English, and mixed-language prompt text as Unicode input and MUST forward each nonblank prompt to the on-device runtime without translation, transliteration, locale-based rewriting, character filtering, whitespace trimming, current-turn instructions, XML/markup envelopes, or any other prompt decoration. The exact prompt text MUST be the complete user-turn payload passed to LiteRT-LM.

#### Scenario: Send a Chinese and English mixed prompt
- **WHEN** a ready runtime receives `请 explain JVM 的 GC, include a Java example`
- **THEN** the runtime MUST receive exactly that prompt text, LiteRT-LM MUST receive exactly that same string as its user-turn payload, and the chat UI SHALL retain the same text in the user message

#### Scenario: Reject an all-whitespace prompt
- **WHEN** the user submits text containing only whitespace
- **THEN** the application SHALL not create a user or assistant chat message and MUST NOT start generation

### Requirement: Default Simplified-Chinese response policy
Every new LiteRT-LM Conversation SHALL receive a Chinese-first system instruction, with sampling parameters topK 10, topP 0.95, temperature 0.2, seed 0, and a 512-token output limit. The language policy MUST NOT decorate, repeat, or replace the visible user-turn payload.

#### Scenario: Answer a Chinese, English, or mixed request
- **WHEN** a user sends a request in Chinese, English, or a mixture of both without specifying an output language
- **THEN** the Conversation SHALL be instructed to answer in Simplified Chinese

#### Scenario: Preserve literal content
- **WHEN** a prompt contains code, a command, URL, filename, product name, abbreviation, quoted text, or explicitly requested English term
- **THEN** the Conversation MUST be instructed to retain that literal content verbatim while explaining it in Simplified Chinese, and the runtime MUST append any missing recognized URL, inline-code, common filename, all-caps abbreviation, or common command span after streaming completes

#### Scenario: Honor an explicit target language
- **WHEN** a user explicitly requests translation or a response in a named target language
- **THEN** the Conversation SHALL be instructed to honor that explicit request instead of the default Simplified-Chinese response language

### Requirement: Direct-answer integrity
The application MUST use LiteRT-LM's conversation template for role separation and SHALL NOT represent a user's question as quoted, delimited, or nested content in a second current-turn instruction. For representative Chinese questions, the visible assistant response SHALL be generated as an answer rather than a client-side echo of the user message.

#### Scenario: Ask a Chinese capability question
- **WHEN** a ready runtime receives `请用一句话说明你能做什么，不要复述这句话。`
- **THEN** LiteRT-LM MUST receive that exact string, and the completed assistant response on the acceptance device MUST be nonblank and MUST NOT equal that user question after ordinary surrounding whitespace and terminal punctuation are ignored


### Requirement: Policy continuity and disclosure
The application SHALL apply the language policy during automatic startup initialization, manual model initialization, backend fallback initialization, and conversation reset, and MUST disclose the input and response behavior in the chat tab.

#### Scenario: Clear conversation
- **WHEN** the user clears a ready conversation and sends a new English or mixed-language prompt
- **THEN** the replacement Conversation MUST retain the Chinese-first policy

#### Scenario: View supported behavior
- **WHEN** the user opens the chat tab
- **THEN** the UI SHALL state that Chinese, English, and mixed input are supported and that replies default to Simplified Chinese

#### Scenario: Model language policy does not meet device acceptance
- **WHEN** a connected Android 12+ ARM64 device fails any required multilingual offline acceptance case
- **THEN** the change MUST remain incomplete and the application SHALL not claim that multilingual language behavior is validated
