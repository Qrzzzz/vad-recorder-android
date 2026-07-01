package com.qrz.voicetriggerrecorder.record

class SimpleVoiceActivityDetector(
    sampleRate: Int,
    sensitivity: Float = 0.55f
) {
    private val engine = VadEngineFactory.ruleBased(
        sampleRate = sampleRate,
        sensitivity = sensitivity
    )

    fun isSpeech(samples: ShortArray, length: Int): Boolean {
        return engine.analyze(samples, length).isSpeech
    }

    fun reset() {
        engine.reset()
    }
}
