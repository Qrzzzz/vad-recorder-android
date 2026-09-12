package com.qrz.voicetriggerrecorder.ui

import android.media.AudioAttributes
import android.media.MediaPlayer

internal class AndroidPlaybackPlayer : PlaybackPlayer {
    private val player = MediaPlayer()
    override var onPrepared: (() -> Unit)? = null
    override var onCompletion: (() -> Unit)? = null
    override var onSeekComplete: (() -> Unit)? = null
    override var onError: (() -> Unit)? = null
    override val durationMs get() = player.duration
    override val positionMs get() = player.currentPosition

    init {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setOnPreparedListener { onPrepared?.invoke() }
        player.setOnCompletionListener { onCompletion?.invoke() }
        player.setOnSeekCompleteListener { onSeekComplete?.invoke() }
        player.setOnErrorListener { _, _, _ ->
            onError?.invoke()
            true
        }
    }

    override fun prepare(path: String) {
        player.setDataSource(path)
        player.prepareAsync()
    }

    override fun start() = player.start()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Int) = player.seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
    override fun release() = player.release()
}
