package com.qrz.voicetriggerrecorder.record

data class RecorderConfig(
    val preferredSampleRate: Int = 16000,
    val fallbackSampleRates: List<Int> = listOf(44100, 48000, 22050),
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    val audioSource: Int = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
    val fallbackAudioSource: Int = android.media.MediaRecorder.AudioSource.MIC,
    val frameMs: Int = 20,
    val preRollMs: Int = 1500,
    val endSilenceMs: Int = 30000,
    val minSpeechMs: Int = 800,
    val minEndSilenceSpeechMs: Int = 300,
    val tailKeepMs: Int = 500,
    val startConfirmMs: Int = 120,
    val resumeConfirmMs: Int = 120
) {
    val samplesPerFrame: Int
        get() = preferredSampleRate * frameMs / 1000

    val preRollFrames: Int
        get() = preRollMs / frameMs

    val endSilenceFrames: Int
        get() = endSilenceMs / frameMs

    val minSpeechFrames: Int
        get() = minSpeechMs / frameMs

    val minEndSilenceSpeechFrames: Int
        get() = minEndSilenceSpeechMs / frameMs

    val tailKeepFrames: Int
        get() = tailKeepMs / frameMs

    val startConfirmFrames: Int
        get() = startConfirmMs / frameMs

    val resumeConfirmFrames: Int
        get() = resumeConfirmMs / frameMs
}
