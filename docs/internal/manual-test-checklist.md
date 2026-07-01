# Manual Test Checklist

Use this checklist after recorder-core changes and before publishing a debug build.

## Environment

- Build and install the current debug APK on a physical Android device.
- Grant microphone permission when prompted.
- On Android 13+, grant notification permission when prompted.
- Confirm the recordings directory is empty before the run:

```powershell
adb shell rm -f /sdcard/Android/data/com.qrz.voicetriggerrecorder/files/Music/voice-recordings/*.wav
adb shell rm -f /sdcard/Android/data/com.qrz.voicetriggerrecorder/files/Music/voice-recordings/*.wav.json
adb shell rm -f /sdcard/Android/data/com.qrz.voicetriggerrecorder/files/Music/voice-recordings/*.wav.part
```

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
