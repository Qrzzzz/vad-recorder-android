package com.qrz.voicetriggerrecorder.record

object VadEngineFactory {
    fun create(sampleRate: Int, sensitivityPreset: SensitivityPreset): VadEngine {
        return RuleBasedVadEngine(
            configuredSampleRate = sampleRate,
            parameters = parametersFor(sensitivityPreset)
        )
    }

    fun ruleBased(sampleRate: Int, sensitivity: Float): VadEngine {
        return RuleBasedVadEngine(
            configuredSampleRate = sampleRate,
            parameters = parametersForSensitivity(sensitivity)
        )
    }

    fun ruleBased(
        sampleRate: Int,
        parameters: RuleBasedVadEngine.Parameters = RuleBasedVadEngine.Parameters()
    ): VadEngine {
        return RuleBasedVadEngine(sampleRate, parameters)
    }

    fun parametersFor(sensitivityPreset: SensitivityPreset): RuleBasedVadEngine.Parameters {
        return when (sensitivityPreset) {
            SensitivityPreset.QUIET_BEDROOM -> parametersForSensitivity(
                sensitivityPreset.detectorSensitivity
            ).copy(
                minAmplitude = 220.0,
                strongSignalMinAmplitude = 720.0,
                requiredSpeechFrames = 4
            )

            SensitivityPreset.NORMAL_ROOM -> parametersForSensitivity(
                sensitivityPreset.detectorSensitivity
            ).copy(
                minAmplitude = 300.0,
                strongSignalMinAmplitude = 850.0,
                requiredSpeechFrames = 4
            )

            SensitivityPreset.NOISY_ENVIRONMENT -> parametersForSensitivity(
                sensitivityPreset.detectorSensitivity
            ).copy(
                minAmplitude = 420.0,
                strongSignalMinAmplitude = 1_050.0,
                requiredSpeechFrames = 5
            )
        }
    }

    fun parametersForSensitivity(sensitivity: Float): RuleBasedVadEngine.Parameters {
        val clamped = sensitivity.coerceIn(0f, 1f).toDouble()
        val thresholdOffsetDb = 14.0 - clamped * 10.0
        val minAmplitude = 500.0 - clamped * 390.0
        return RuleBasedVadEngine.Parameters(
            thresholdOffsetDb = thresholdOffsetDb,
            strongSignalOffsetDb = thresholdOffsetDb + 7.0,
            minAmplitude = minAmplitude,
            strongSignalMinAmplitude = minAmplitude * 2.8,
            requiredSpeechFrames = if (clamped < 0.45) 5 else 4
        )
    }
}
