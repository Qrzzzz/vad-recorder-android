package com.qrz.voicetriggerrecorder.record

interface VadEngine {
    val sampleRate: Int

    fun analyze(samples: ShortArray, length: Int): VadResult

    fun reset()
}
