package com.qrz.voicetriggerrecorder.record

class SimpleVoiceActivityDetector(
    private val sampleRate: Int,
    sensitivity: Float = 0.55f
) {
    private val engine = VadEngineFactory.ruleBased(
        sampleRate = sampleRate,
        sensitivity = sensitivity
    )

    fun isSpeech(samples: ShortArray, length: Int): Boolean {
        return engine.isSpeech(samples.copyOf(length.coerceIn(0, samples.size)), sampleRate).isSpeech
    }

    fun reset() {
        engine.reset()
    }
}
