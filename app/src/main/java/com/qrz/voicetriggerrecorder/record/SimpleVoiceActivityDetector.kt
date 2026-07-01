package com.qrz.voicetriggerrecorder.record

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

class SimpleVoiceActivityDetector(
    private val sampleRate: Int,
    private val sensitivity: Float = 0.55f
) {
    private var noiseFloorDb = -55.0
    private val recent = ArrayDeque<Boolean>()

    fun isSpeech(samples: ShortArray, length: Int): Boolean {
        if (length <= 0) return false

        val rms = computeRms(samples, length)
        val db = 20.0 * ln(rms / 32768.0 + 1e-9) / ln(10.0)
        val zcr = computeZcr(samples, length)

        val thresholdOffset = (14.0 - sensitivity * 10.0)
        val threshold = max(-45.0, noiseFloorDb + thresholdOffset)
        val energyCondition = db > threshold
        val amplitudeCondition = rms > 300.0
        val zcrCondition = zcr in 0.01..0.25
        val raw = energyCondition && amplitudeCondition && zcrCondition

        if (!raw) {
            noiseFloorDb = noiseFloorDb * 0.98 + db * 0.02
        }

        recent.addLast(raw)
        while (recent.size > 10) {
            recent.removeFirst()
        }

        return recent.count { it } >= 4
    }

    fun reset() {
        noiseFloorDb = -55.0
        recent.clear()
    }

    private fun computeRms(samples: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / length)
    }

    private fun computeZcr(samples: ShortArray, length: Int): Double {
        if (length < 2) return 0.0
        var crossings = 0
        for (i in 1 until length) {
            if ((samples[i] >= 0) != (samples[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toDouble() / (length - 1)
    }
}
