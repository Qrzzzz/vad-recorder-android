package com.qrz.voicetriggerrecorder.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread gate shared by every listening entry point and player callback. */
internal class PlaybackInterlock {
    private val mutableBlocked = MutableStateFlow(false)
    val blocked = mutableBlocked.asStateFlow()
    private val players = mutableSetOf<PlaybackController>()
    fun register(controller: PlaybackController) { players += controller }
    fun unregister(controller: PlaybackController) { players -= controller }
    fun block() {
        mutableBlocked.value = true
        players.toList().forEach { it.pause() }
    }
    fun unblock() { mutableBlocked.value = false }

    companion object { val shared = PlaybackInterlock() }
}

internal data class PlaybackState(
    val path: String? = null,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val isComplete: Boolean = false,
    val hasError: Boolean = false
)

internal interface PlaybackPlayer {
    var onPrepared: (() -> Unit)?
    var onCompletion: (() -> Unit)?
    var onSeekComplete: (() -> Unit)?
    var onError: (() -> Unit)?
    val durationMs: Int
    val positionMs: Int
    fun prepare(path: String)
    fun start()
    fun pause()
    fun seekTo(positionMs: Int)
    fun release()
}

/** Owned by the screen; all commands and player callbacks run on the main thread. */
internal class PlaybackController(
    private val interlock: PlaybackInterlock = PlaybackInterlock.shared,
    private val createPlayer: () -> PlaybackPlayer = { AndroidPlaybackPlayer() }
) {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state = mutableState.asStateFlow()
    private var player: PlaybackPlayer? = null
    private var playWhenReady = false
    private var seekInFlight: Int? = null
    private var requestedSeek: Int? = null

    fun toggle(path: String) {
        if (interlock.blocked.value) return
        if (state.value.path == path) {
            if (state.value.isLoading) return
            if (state.value.isPlaying) pause() else resume()
            return
        }
        clear()
        interlock.register(this)
        mutableState.value = PlaybackState(path = path, isLoading = true)
        playWhenReady = true
        try {
            val candidate = createPlayer()
            player = candidate
            candidate.onPrepared = {
                if (player === candidate) safely {
                    val duration = candidate.durationMs.coerceAtLeast(0)
                    check(duration > 0) { "Empty recording" }
                    mutableState.value = state.value.copy(isLoading = false, durationMs = duration)
                    if (playWhenReady) resume()
                }
            }
            candidate.onCompletion = {
                if (player === candidate && seekInFlight == null) {
                    playWhenReady = false
                    mutableState.value = state.value.copy(
                        isPlaying = false, positionMs = state.value.durationMs, isComplete = true
                    )
                }
            }
            candidate.onSeekComplete = {
                if (player === candidate) safely {
                    val latest = requestedSeek
                    if (latest != null && latest != seekInFlight) {
                        seekInFlight = latest
                        candidate.seekTo(latest)
                    } else {
                        seekInFlight = null
                        requestedSeek = null
                        updatePosition()
                    }
                }
            }
            candidate.onError = { if (player === candidate) fail() }
            candidate.prepare(path)
        } catch (_: Exception) {
            fail()
        }
    }

    fun pause() {
        playWhenReady = false
        if (!state.value.isPlaying) return
        safely {
            player?.pause()
            updatePosition()
            mutableState.value = state.value.copy(isPlaying = false)
        }
    }

    private fun resume() {
        if (interlock.blocked.value) return
        val current = player ?: return
        safely {
            if (state.value.isComplete) seekTo(state.value.path ?: return@safely, 0)
            if (player !== current) return@safely
            current.start()
            playWhenReady = true
            mutableState.value = state.value.copy(isPlaying = true, isComplete = false)
        }
    }

    fun seekTo(path: String, positionMs: Int) {
        val current = player ?: return
        if (state.value.path != path || state.value.isLoading) return
        val target = positionMs.coerceIn(0, state.value.durationMs)
        requestedSeek = target
        mutableState.value = state.value.copy(positionMs = target, isComplete = false)
        if (seekInFlight == null) safely {
            seekInFlight = target
            current.seekTo(target)
        }
    }

    fun updatePosition() {
        val current = player ?: return
        if (state.value.isLoading || state.value.isComplete || seekInFlight != null) return
        safely {
            mutableState.value = state.value.copy(
                positionMs = current.positionMs.coerceIn(0, state.value.durationMs)
            )
        }
    }

    fun retainFiles(paths: Collection<String>) {
        if (state.value.path?.let { it !in paths } == true) clear()
    }

    fun clear() {
        interlock.unregister(this)
        // Invalidate before release so late callbacks cannot change another clip's state.
        val previous = player
        player = null
        playWhenReady = false
        seekInFlight = null
        requestedSeek = null
        mutableState.value = PlaybackState()
        runCatching { previous?.release() }
    }

    private fun fail() {
        clear()
        mutableState.value = PlaybackState(hasError = true)
    }

    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            fail()
        }
    }
}
