## ADDED Requirements

### Requirement: Bundled model provisioning
The application SHALL package the approved Gemma `.litertlm` as an uncompressed asset and MUST transactionally install it into app-private storage when no valid selected model exists.

#### Scenario: Bundled asset integrity
- **WHEN** the application extracts the bundled Gemma model
- **THEN** it MUST verify the expected byte length and SHA-256 before selecting the model, and MUST delete a mismatched extraction with a recoverable error

#### Scenario: Fresh application launch
- **WHEN** the application starts without a valid selected model and the bundled asset is readable
- **THEN** the application SHALL copy it with progress and SHA-256 verification, persist the resulting descriptor, and expose it for automatic initialization

#### Scenario: Existing selected model
- **WHEN** the application starts with a valid selected private model
- **THEN** the application MUST reuse that model without copying or replacing it from the bundled asset

#### Scenario: Bundled asset missing or installation fails
- **WHEN** the asset is absent, unreadable, cancelled, or cannot be written due to storage exhaustion
- **THEN** the application MUST remove any `.partial` file and show a recoverable error while retaining manual SAF import

### Requirement: Local model import
The application SHALL allow a user to select a `.litertlm` document and import it into app-private storage without requesting broad storage permission.

#### Scenario: Import a valid model document
- **WHEN** the user selects a readable, non-empty `.litertlm` document with sufficient destination space
- **THEN** the application SHALL copy it into app-private storage and report determinate progress when the source length is known

#### Scenario: Reject an invalid document
- **WHEN** the selected document is empty, unreadable, or does not have the `.litertlm` extension
- **THEN** the application MUST reject the import and display a recoverable error without changing the selected model

### Requirement: Transactional model persistence
The application MUST calculate SHA-256 while copying, write through a temporary `.partial` file, and persist model metadata only after the complete file is atomically installed.

#### Scenario: Successful transactional import
- **WHEN** all source bytes are copied successfully
- **THEN** the application SHALL rename the file to `<sha256>.litertlm`, persist its metadata, and restore it after application restart

#### Scenario: Interrupted transactional import
- **WHEN** import is cancelled, fails, or the application later discovers an orphaned `.partial` file
- **THEN** the application MUST delete the incomplete file and retain the previously selected model

### Requirement: Storage safety
The application MUST validate known source length against usable destination space with a safety margin before copying and MUST surface write failures for sources with unknown length.

#### Scenario: Insufficient storage
- **WHEN** known available storage is less than the source length plus the configured safety margin
- **THEN** the application SHALL refuse the copy and report insufficient storage

### Requirement: Model replacement and deletion
The application SHALL support replacing or deleting the selected model only after active generation and native runtime resources have been stopped.

#### Scenario: Replace an existing model
- **WHEN** a new model finishes importing successfully
- **THEN** the application SHALL select the new model and delete the previous private model file if it is no longer referenced

#### Scenario: Delete the selected model
- **WHEN** the user confirms model deletion
- **THEN** the application MUST unload the runtime, remove the private file and metadata, and return to the no-model state
