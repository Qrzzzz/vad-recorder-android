package com.qrz.voicetriggerrecorder.record

import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/** The old-API branch never constructs the API 29 implementation. */
internal object InputSilenceMonitor {
    fun attach(record: AudioRecord, listener: (Boolean?) -> Unit): () -> Unit =
        if (Build.VERSION.SDK_INT >= 29) Api29.attach(record, listener)
        else { listener(null); {} }

    @RequiresApi(29)
    private object Api29 {
        fun attach(record: AudioRecord, listener: (Boolean?) -> Unit): () -> Unit {
            val active = java.util.concurrent.atomic.AtomicBoolean(true)
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if (active.get()) listener(configs.firstOrNull {
                        it.clientAudioSessionId == record.audioSessionId
                    }?.isClientSilenced)
                }
            }
            val handler = Handler(Looper.getMainLooper())
            record.registerAudioRecordingCallback(Executor { handler.post(it) }, callback)
            // Query again after start via refresh(); callbacks alone may miss the initial state.
            return {
                active.set(false)
                record.unregisterAudioRecordingCallback(callback)
            }
        }
    }

    fun refresh(record: AudioRecord, listener: (Boolean?) -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) listener(record.activeRecordingConfiguration?.isClientSilenced)
        else listener(null)
    }
}
