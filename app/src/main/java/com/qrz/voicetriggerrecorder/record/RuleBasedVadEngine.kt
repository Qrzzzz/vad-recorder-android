package com.qrz.voicetriggerrecorder.record

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class RuleBasedVadEngine(
    override val sampleRate: Int,
    private val parameters: Parameters = Parameters()
) : VadEngine {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    data class Parameters(
        val initialNoiseFloorDb: Double = -55.0,
        val calibrationDurationMs: Long = 12_000L,
        val minThresholdDb: Double = -45.0,
        val thresholdOffsetDb: Double = 8.5,
        val strongSignalOffsetDb: Double = 15.0,
        val minAmplitude: Double = 300.0,
        val strongSignalMinAmplitude: Double = 850.0,
        val minZeroCrossingRate: Double = 0.01,
        val maxZeroCrossingRate: Double = 0.25,
        val noiseAdaptationAlpha: Double = 0.02,
        val calibrationNoiseAlpha: Double = 0.08,
        val smoothingWindowFrames: Int = 10,
        val requiredSpeechFrames: Int = 4
    )

    private var noiseFloorDb = parameters.initialNoiseFloorDb
    private var calibratedMs = 0L
    private val recent = ArrayDeque<Boolean>()

    override fun analyze(samples: ShortArray, length: Int): VadResult {
        val safeLength = min(length, samples.size)
        if (safeLength <= 0) {
            return VadResult(
                isSpeech = false,
                isCalibrating = isCalibrating(),
                confidence = 0f,
                rmsDb = parameters.initialNoiseFloorDb,
                noiseFloorDb = noiseFloorDb,
                zeroCrossingRate = 0.0
            )
        }

        val rms = computeRms(samples, safeLength)
        val db = toDb(rms)
        val zcr = computeZcr(samples, safeLength)
        val humanVoiceBand = zcr in parameters.minZeroCrossingRate..parameters.maxZeroCrossingRate
        val threshold = max(parameters.minThresholdDb, noiseFloorDb + parameters.thresholdOffsetDb)
        val strongThreshold = max(
            parameters.minThresholdDb,
            noiseFloorDb + parameters.strongSignalOffsetDb
        )
        val rawSpeech = db > threshold && rms > parameters.minAmplitude && humanVoiceBand
        val strongSpeech = db > strongThreshold &&
            rms > parameters.strongSignalMinAmplitude &&
            humanVoiceBand
        val calibrating = isCalibrating()

        if (calibrating) {
            calibratedMs += frameDurationMs(safeLength)
            if (!strongSpeech) {
                adaptNoiseFloor(db, parameters.calibrationNoiseAlpha)
            }
            recent.clear()
            return VadResult(
                isSpeech = strongSpeech,
                isCalibrating = true,
                confidence = confidence(db, strongThreshold, zcr, strongSpeech),
                rmsDb = db,
                noiseFloorDb = noiseFloorDb,
                zeroCrossingRate = zcr
            )
        }

        if (!rawSpeech) {
            adaptNoiseFloor(db, parameters.noiseAdaptationAlpha)
        }

        recent.addLast(rawSpeech)
        while (recent.size > parameters.smoothingWindowFrames) {
            recent.removeFirst()
        }

        val speech = recent.count { it } >= parameters.requiredSpeechFrames
        return VadResult(
            isSpeech = speech,
            isCalibrating = false,
            confidence = confidence(db, threshold, zcr, speech),
            rmsDb = db,
            noiseFloorDb = noiseFloorDb,
            zeroCrossingRate = zcr
        )
    }

    override fun reset() {
        noiseFloorDb = parameters.initialNoiseFloorDb
        calibratedMs = 0L
        recent.clear()
    }

    private fun isCalibrating(): Boolean {
        return calibratedMs < parameters.calibrationDurationMs
    }

    private fun adaptNoiseFloor(db: Double, alpha: Double) {
        noiseFloorDb = noiseFloorDb * (1.0 - alpha) + db * alpha
    }

    private fun frameDurationMs(length: Int): Long {
        return (length * 1000L / sampleRate).coerceAtLeast(1L)
    }

    private fun computeRms(samples: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / length)
    }

    private fun toDb(rms: Double): Double {
        return 20.0 * ln(rms / 32768.0 + 1e-9) / ln(10.0)
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

    private fun confidence(db: Double, threshold: Double, zcr: Double, speech: Boolean): Float {
        if (!speech) return 0f
        val energyScore = ((db - threshold) / 18.0).coerceIn(0.0, 1.0)
        val zcrCenter = (parameters.minZeroCrossingRate + parameters.maxZeroCrossingRate) / 2.0
        val zcrRadius = (parameters.maxZeroCrossingRate - parameters.minZeroCrossingRate) / 2.0
        val zcrScore = (1.0 - kotlin.math.abs(zcr - zcrCenter) / zcrRadius).coerceIn(0.0, 1.0)
        return ((energyScore * 0.75) + (zcrScore * 0.25)).toFloat()
    }
}
