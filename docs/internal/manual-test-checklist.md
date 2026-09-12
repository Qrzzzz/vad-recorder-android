# Manual Test Checklist

Use this checklist after recorder-core changes and before publishing a debug build.

## Environment

- Build and install the current debug APK on a physical Android device.
- Grant microphone permission when prompted.
- On Android 13+, grant notification permission when prompted.
- Use `-PdeviceAcceptance` and disposable fixtures in the `.acceptance` package. Clean only fixtures created by the run; never clear the production recording directory to prepare a test.

## Recorder Core

- Short noise discard: start listening, make a brief non-speech noise, wait more than 30 seconds, and confirm no `.wav` or `.wav.part` remains.
- Normal speech save: speak for several seconds, stop speaking, wait for the 30 second silence countdown, and confirm one playable `.wav` plus one `.wav.json` sidecar exists.
- Silence timeout merge: speak, pause for less than 30 seconds, speak again, then wait 30 seconds; confirm one continuous clip is saved.
- Manual stop save: speak long enough to trigger recording, tap stop while recording or in the countdown, and confirm the current clip is saved and playable.
- Read/setup failure: deny or revoke microphone access, or occupy the microphone with another app, then start listening and confirm the UI leaves the running/listening state and no partial file remains.
- Partial cleanup: force-stop the app during an active recording, restart it, and confirm stale `.wav.part` files are cleaned up on the next recorder start.

## File and Metadata Checks

- WAV header: pull a saved file and verify it opens in a desktop player; optionally inspect that it is RIFF/WAVE PCM with the expected sample rate.
- Metadata sidecar: confirm the `.wav.json` file includes `durationMs`, `sampleRate`, `speechDurationMs`, `closeReason`, `vadEngineName`, `isFinalized`, and `isCorrupted`.
- Legacy WAV compatibility: copy a valid `.wav` without a `.json` sidecar into the recordings directory, reopen the app, and confirm it appears in the list with inferred duration/sample rate.
- Delete flow: delete a recording in the app and confirm both the `.wav` and `.wav.json` sidecar are removed.

## Settings and Lifecycle

- Change sensitivity before starting a session and confirm detection behavior changes on the next run.
- Change auto-stop while listening and confirm the running session updates its target stop time.
- Let auto-stop end a session and confirm any active valid clip is saved.
- Change Appearance to Follow system, Light, and Dark; confirm the screen recreates cleanly, the selected chip persists after reopening the app, and the launch background/system bars match the selected mode.
- With Appearance set to Follow system, toggle Android's system dark theme and confirm the app follows the native system setting.

## Playback 2.3

Use the independent `.acceptance` package and disposable WAV fixtures for automated playback tests; do not clear the production package or its recordings.

- Play a legacy WAV without sidecar metadata; only the selected clip expands its slider and elapsed/total time.
- Pause midway, wait, and resume: position stays fixed while paused and continues from that point.
- Drag the slider while playing and paused; confirm the position follows the final drag target without jumping back to the pre-seek position. Also exercise the accessibility SetProgress action.
- Seek near the end, let playback finish, and replay from the beginning.
- Switch to a different clip: the previous player closes and only the new clip plays.
- Delete an active or paused selected clip through More → Delete: player controls disappear and WAV/sidecar are removed together.
- Switch to Settings or send the app to the background: playback pauses. Return to the same screen instance and resume manually.
- Recreate or close the activity: playback resources are released and no stale active controls remain.
- Verify Chinese/English, light/dark appearance, readable duration labels, and the slider's accessibility label and state description.

## Export and Share 2.4

- More → Save as file opens the system picker with the WAV filename. Save to Downloads and compare SHA-256 with the source; also verify a renamed file and cancellation.
- Recreate the app behind the picker, then return a result or cancel. The pending source remains bound to the request, and the row becomes usable again afterward.
- More → Share audio opens the real Android Sharesheet. A separate test receiver must read exactly the expected bytes and be denied write/delete access. Cancelling the chooser preserves the WAV and JSON.
- Verify that unfinished, missing, corrupt, truncated, and out-of-directory sources cannot be transferred. Old complete WAV files without sidecars still work.
- Exercise null destination streams, write/close failures and cleanup failures. Report success only after the destination is closed; never change the original or claim share delivery.
- Preserve recent share snapshots while cleaning those older than 24 hours on next launch/share. Repeat sharing to confirm unique copies and filenames.
- Verify English/light and Chinese/dark menus, progress and result messages, and the contextual accessibility label on More. Regress 2.3 playback, seek, pause/resume and delete behavior.
