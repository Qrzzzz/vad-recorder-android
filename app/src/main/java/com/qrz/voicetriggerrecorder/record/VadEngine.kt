package com.qrz.voicetriggerrecorder.record

interface VadEngine {
    fun reset()

    fun isSpeech(frame: ShortArray, sampleRate: Int): VadResult
}
