# Architecture Audit: Voice-Triggered Recorder Core

Audit date: 2026-07-01

Scope: document the current implementation only. This audit intentionally does not prescribe a broad rewrite or change runtime behavior.

## Current Core Chain

```text
MainScreen
  -> RecordForegroundService
  -> AudioCaptureEngine
  -> VadEngine / RuleBasedVadEngine
  -> RecordingStateMachine
  -> WavFileWriter
  -> RecordingRepository
```

### 1. MainScreen

Source: `app/src/main/java/com/qrz/voicetriggerrecorder/ui/MainScreen.kt`

Responsibilities observed:

- Collects the service-owned `RecordForegroundService.uiState` directly in Compose.
- Starts and stops the foreground service through explicit intents.
- Owns permission prompts, notification permission prompts, playback, delete confirmation, and the recordings list refresh.
- Creates `RecordingRepository(context)` directly and uses it to list and delete saved WAV files.
- Persists settings through `RecorderPreferences`, then calls `RecordForegroundService.requestSettingsRefresh(context)` when settings should affect a running session.

Important details:

- `uiState` is not produced by a view model or app-level store; Compose reads the service companion object's static `StateFlow`. In 2.0 this static flow is only a UI bridge for rendering and refresh triggers; the active session lifecycle is owned by the service instance, `AudioCaptureEngine`, and `RecordingStateMachine`.
- File list refresh is driven by `uiState.savedCount`, `uiState.serviceRunning`, and `ON_RESUME`, not by file-system observation.
- `startService(context)` uses `ContextCompat.startForegroundService(...)`; `stopService(context)` uses `context.startService(...)` with `ACTION_STOP`.

### 2. RecordForegroundService

Source: `app/src/main/java/com/qrz/voicetriggerrecorder/record/RecordForegroundService.kt`

Responsibilities observed:

- Owns the global recorder UI state in a companion-object `MutableStateFlow`.
- Translates service actions into session lifecycle operations:
  - `ACTION_START` starts the foreground session.
  - `ACTION_STOP` stops the engine, clears UI state, removes the foreground notification, and stops the service.
  - `ACTION_REFRESH_SETTINGS` recomputes auto-stop for the current session.
- Creates the notification channel and starts foreground mode.
- Cleans stale `.wav.part` files and constructs `AudioCaptureEngine(applicationContext, sensitivityPreset, applyUiMutation)`.
- Schedules the auto-stop coroutine based on `RecorderPreferences.loadAutoStopHours()`.

Important details:

- `startServiceSafely()` cleans stale `.wav.part` files, starts foreground mode, publishes a fresh `RecorderUiState(serviceRunning = true, recorderPhase = LISTENING)`, constructs the engine, then calls `refreshSessionSettingsIfRunning()`.
- The engine runs in `scope.launch { engine?.start() }` on a `SupervisorJob() + Dispatchers.Default` service scope.
- `ACTION_STOP` is asynchronous. It launches a coroutine, cancels auto-stop, calls `engine?.close(ManualStop)`, joins `engineJob`, resets the state flow to `RecorderUiState()`, removes foreground mode, and calls `stopSelf()`.
- `onDestroy()` calls `engine?.close(Destroy)`, runs the same foreground/UI cleanup path, cancels the service scope, and then calls `super.onDestroy()`.

### 3. AudioCaptureEngine

Source: `app/src/main/java/com/qrz/voicetriggerrecorder/record/AudioCaptureEngine.kt`

Responsibilities observed:

- Creates and owns `AudioRecord` and `NoiseSuppressor`.
- Selects one of these hard-coded capture candidates:
  - `VOICE_RECOGNITION`, 16 kHz mono
  - `MIC`, 16 kHz mono
  - `VOICE_RECOGNITION`, 44.1 kHz mono
  - `MIC`, 44.1 kHz mono
- Reads 20 ms frames from the microphone.
- Runs the configured `VadEngine` on each frame.
- Feeds each frame and speech decision into `RecordingStateMachine`.
- Emits `speechDetected` and `countdownRemainingMs` UI mutations when those values change.
- On repeated audio-read failures, finalizes any valid active clip with `ReadError`, emits `RECORDER_FAILED`, and exits the read loop.
- On normal exit, closes any open state-machine file using the requested close reason and clears speech/countdown UI fields.

Important details:

- The engine creates `RecorderConfig(preferredSampleRate = config.sampleRate)`, so the selected runtime sample rate becomes the recording sample rate.
- `close(reason)` stores the requested `RecordingCloseReason`, flips `running = false`, stops/releases `AudioRecord`, releases `NoiseSuppressor`, and nulls both references.
- `stateMachine.closeCurrentFileIfNeeded(requestedCloseReason)` is always called after the read loop exits, which preserves manual-stop, service-stop, read-error, and destroy semantics.

### 4. VadEngine / RuleBasedVadEngine

Sources:

- `app/src/main/java/com/qrz/voicetriggerrecorder/record/VadEngine.kt`
- `app/src/main/java/com/qrz/voicetriggerrecorder/record/RuleBasedVadEngine.kt`
- `app/src/main/java/com/qrz/voicetriggerrecorder/record/SimpleVoiceActivityDetector.kt`

Responsibilities observed:

- Computes RMS, dB level, and zero-crossing rate per frame.
- Maintains an adaptive noise floor after an initial calibration period.
- Applies the sensitivity preset as a threshold offset.
- Returns `VadResult` with speech, confidence, level, threshold, and noise-floor fields.
- Keeps `SimpleVoiceActivityDetector` as a compatibility wrapper around `RuleBasedVadEngine`.

Important details:

- `AudioCaptureEngine` still feeds the state machine a boolean speech decision; the richer `VadResult` is currently used inside the capture layer.

### 5. RecordingStateMachine

Source: `app/src/main/java/com/qrz/voicetriggerrecorder/record/RecordingStateMachine.kt`

Responsibilities observed:

- Owns the internal recording states:
  - `LISTENING`
  - `RECORDING`
  - `HANGOVER`
- Buffers pre-roll audio in `RingBuffer`.
- Creates the output WAV file when enough consecutive speech frames confirm start.
- Writes pre-roll, speech, and short tail audio into `WavFileWriter`.
- Computes the finish countdown while in `HANGOVER`.
- Finalizes or deletes the current `.wav.part` file on timeout, too-short speech, manual stop, service stop, read error, or destroy.
- Emits UI mutations for `RECORDING`, `WAITING_TO_FINISH`, `SAVED`, and return-to-listening states.

Important details:

- The start threshold is `RecorderConfig.startConfirmMs` converted to 20 ms frames.
- The end threshold is `RecorderConfig.endSilenceMs`, currently 30 seconds.
- `RecordingCloseReason.EndSilence`, `ServiceStop`, and `ReadError` save only when speech duration meets the configured minimum; `ManualStop` can save shorter valid active audio; `Destroy` discards partial audio.
- Normal hangover completion only saves if the computed speech duration meets `minSpeechMs`.

### 6. WavFileWriter

Source: `app/src/main/java/com/qrz/voicetriggerrecorder/record/WavFileWriter.kt`

Responsibilities observed:

- Opens a `RandomAccessFile` for a temporary `.wav.part` file next to the target WAV file.
- Writes a 44-byte placeholder header during initialization.
- Writes 16-bit little-endian PCM samples.
- On commit, seeks back to byte 0, writes the RIFF/WAVE/fmt/data header using the accumulated data byte count, syncs, closes, and moves the finalized part file to `.wav`.
- On abort, deletes the part file and does not create a `.wav`.

Important details:

- The writer assumes PCM, mono by default, 16-bit by default.
- It is synchronous and called directly from the audio capture coroutine.
- It reports commit success as a boolean but still does not expose structured failure reasons.

### 7. RecordingRepository

Source: `app/src/main/java/com/qrz/voicetriggerrecorder/record/RecordingRepository.kt`

Responsibilities observed:

- Points to the same app-specific recordings directory used by `RecordingStateMachine`.
- Lists `.wav` files, loads or creates JSON sidecar metadata, maps them to `RecordingFile`, and sorts newest first.
- Deletes a file by name and removes the matching metadata sidecar.

Important details:

- Legacy WAV files without metadata are inferred from the WAV header and get a sidecar written on first listing.
- Corrupt or incomplete WAV headers are marked through metadata instead of being treated as fully finalized clips.

## State Flow

Primary state path:

```text
RecordingStateMachine / AudioCaptureEngine
  -> RecorderUiStateMutation
  -> RecordForegroundService.applyUiMutation(...)
  -> companion MutableStateFlow<RecorderUiState>
  -> MainScreen.collectAsState()
  -> Compose status cards and controls
```

State producers:

- `RecordForegroundService` publishes coarse lifecycle state: idle/listening, foreground running, auto-stop deadline, setup failure, and reset-to-default on stop.
- `AudioCaptureEngine` publishes live detection state: `speechDetected`, `countdownRemainingMs`, and read-failure state.
- `RecordingStateMachine` publishes recording state: current file name, saved count, last saved file name, and recorder phase.

State consumers:

- `MainScreen` renders all current session status from `RecorderUiState`.
- `MainScreen` uses `savedCount` as a trigger to refresh the repository listing.
- Settings UI uses `uiState.serviceRunning` to decide whether to request live session refresh after preferences change.

Audit finding:

- There is no single owner for all recorder state transitions. Service, engine, and state machine all mutate the same UI state object through callbacks. This keeps the implementation compact, but it makes transition ordering and reset behavior harder to reason about.

## File Write Path

Current write path:

```text
AudioRecord.read(...)
  -> ShortArray frame
  -> VadEngine.isSpeech(...).isSpeech
  -> RecordingStateMachine.onFrame(frame, speech)
  -> RingBuffer pre-roll while LISTENING
  -> WavFileWriter(file, sampleRate) after start confirmation, writing to .wav.part
  -> RandomAccessFile writes PCM samples
  -> WavFileWriter.closeAndCommit() writes WAV header and moves .wav.part to .wav
  -> RecordingMetadataStore.writeFinalized(...) writes the sidecar JSON
  -> RecordingRepository.listRecordings() discovers saved .wav files and metadata later
```

Storage path:

```text
context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
  / voice-recordings
  / voice_yyyyMMdd_HHmmss.wav
```

The same path is duplicated in:

- `RecordingStateMachine`, for file creation.
- `RecordingRepository`, for listing and deletion.

Audit finding:

- This duplication is a strong coupling point. Any future directory, naming, retention, or sharing change must update both components together.

## Strong Coupling Points

### UI to service static state

`MainScreen` imports and observes `RecordForegroundService.uiState` directly. This bypasses an app state holder or view model and means the UI is coupled to a concrete Android service class.

Risk:

- UI tests and previewability are harder.
- Process/service state restoration is implicit.
- A future service split or repository-backed state model would require UI changes.

### Recorder internals to UI model

`AudioCaptureEngine` and `RecordingStateMachine` depend on `RecorderUiStateMutation`, which is defined in the UI package.

Risk:

- The record package cannot evolve independently from UI state shape.
- Low-level audio and file-writing code directly knows which UI fields to update.

### State machine to Android Context and storage

`RecordingStateMachine` receives `Context`, creates directories, formats file names, creates files, and emits localized UI-related state.

Risk:

- Recording transition logic is coupled to Android storage and UI mutation side effects.
- Unit testing the state machine requires Android context or additional test scaffolding.

### Repository and state machine duplicate storage knowledge

Both components independently construct `getExternalFilesDir(Environment.DIRECTORY_MUSIC)/voice-recordings`.

Risk:

- Directory drift would silently break listing/deletion or writing.
- Repository duration assumptions can diverge from actual writer sample rate.

### Settings refresh reaches through service actions

`MainScreen` writes preferences and calls `RecordForegroundService.requestSettingsRefresh(context)` when a service is running.

Risk:

- Preferences are the data transport between settings UI and live session behavior.
- Sensitivity changes do not affect an already-created `AudioCaptureEngine`; auto-stop changes do.

### Notification and service lifecycle mixed with engine lifecycle

`RecordForegroundService` owns notification channel creation, foreground mode, engine creation, auto-stop scheduling, UI state, and service shutdown.

Risk:

- It is difficult to test lifecycle edge cases without running Android service behavior.
- Start, stop, refresh, foreground notification, and engine failure handling are tightly interleaved.

## Service Lifecycle Issues

### Engine failure stops the foreground service state

If `captureEngine.start()` throws during setup, the catch block publishes `MICROPHONE_SETUP_FAILED` with `serviceRunning = false`, removes foreground mode, and stops the service.

Risk:

- The failure state is intentionally preserved for the UI, while notification and running state are cleared.
- Regression tests should keep covering microphone setup failures because the actual Android service path is hard to exercise in JVM tests.

### Audio read failure stops the foreground service after finalization

After repeated read failures, `AudioCaptureEngine` asks the state machine to close with `ReadError` and returns that close reason. The service then calls `finishStoppedService(preserveFailureState = true)`.

Risk:

- The final UI state intentionally preserves the failure phase while clearing `serviceRunning`.
- Tests should keep covering that valid active audio is finalized on `ReadError` and that partial files are removed.

### Stop is asynchronous and state reset happens after engine join

`ACTION_STOP` launches a coroutine that stops the engine, joins the engine job, clears UI state, removes foreground mode, and calls `stopSelf()`.

Risk:

- UI state can briefly report a running session after stop is requested.
- If engine shutdown blocks longer than expected, notification removal and state reset are delayed.

### Service companion state is process-local

`RecordForegroundService.uiState` is a process-local static flow, not persisted session state. It is now treated as a UI bridge only; the real active-session state is held in the service instance and the capture/state-machine objects, so process death or service teardown must not be inferred from the static flow alone.

Risk:

- If the process is killed, UI state is rebuilt from defaults.
- Since the service is `START_NOT_STICKY`, no recorder session is restored automatically after process death.

### Foreground start is repeated during settings refresh

`refreshSessionSettingsIfRunning()` calls `createNotificationChannel()` and `startAsForeground()` even when the service is already in foreground mode.

Risk:

- This keeps notification content fresh enough for current behavior, but it mixes settings refresh with foreground-service management.
- Future richer notifications could be accidentally reset by a settings-only refresh.

## Main Findings

1. The app has a clear working runtime chain, but it is implemented as a tightly coupled vertical slice from Compose to service to engine to state machine to file writer.
2. The strongest architectural coupling is the callback mutation of UI state from inside audio and recording internals.
3. The file write path is simple and understandable, but the storage directory is still duplicated between writer/state-machine and repository code.
4. Manual stop currently saves an in-progress clip through `closeCurrentFileIfNeeded(RecordingCloseReason.ManualStop)`, which is an important behavior to preserve.
5. Lifecycle failure paths now clear the foreground running state, but Android service setup/read failures still deserve focused regression tests.
6. Auto-stop is live-refreshable, but sensitivity changes are only applied when a new engine is created.

## Developer Risks To Track

- Do not change stop behavior casually; manual stop is currently a save path, not a discard path.
- Treat `RecorderUiState.savedCount` as a UI refresh trigger, not durable data.
- Be careful changing sample-rate fallback logic; repository metadata now infers duration/sample rate from WAV headers.
- Be careful moving storage paths; state machine and repository must stay aligned.
- Be careful changing settings semantics; auto-stop refresh applies live, but VAD sensitivity does not.
- Add focused tests around lifecycle transitions before refactoring service/engine boundaries.
