package com.qrz.voicetriggerrecorder.ui

import org.junit.Assert.*
import org.junit.Test

class PlaybackControllerTest {
    private val players = mutableListOf<FakePlayer>()
    private val interlock = PlaybackInterlock()
    private val controller = PlaybackController(interlock) { FakePlayer().also { players += it } }
    private val state get() = controller.state.value

    private fun play(path: String = "first.wav"): FakePlayer {
        controller.toggle(path)
        return players.last().also { it.onPrepared?.invoke() }
    }

    @Test fun listeningPausesPlaybackBeforeAcquisitionAndBlocksNewPlayback() {
        val player = play()
        player.positionMs = 4200
        interlock.block()
        assertFalse(state.isPlaying)
        assertEquals(4200, state.positionMs)
        controller.toggle("other.wav")
        controller.toggle("first.wav")
        assertEquals(1, players.size)
        assertEquals(1, player.starts)
        interlock.unblock()
        assertFalse(state.isPlaying)
        controller.toggle("first.wav")
        assertTrue(state.isPlaying)
        assertEquals(2, player.starts)
    }

    @Test fun listeningClearsAutoplayEvenWhenPreparedArrivesAfterListeningStops() {
        controller.toggle("first.wav")
        val player = players.single()
        interlock.block()
        player.onPrepared?.invoke()
        player.onSeekComplete?.invoke()
        assertEquals(0, player.starts)
        interlock.unblock()
        player.onPrepared?.invoke()
        assertEquals(0, player.starts)
        controller.toggle("first.wav")
        assertEquals(1, player.starts)
    }

    @Test fun pauseAndResumeKeepTheSamePlayerAndPosition() {
        val player = play()
        player.positionMs = 4200
        controller.toggle("first.wav")
        assertFalse(state.isPlaying)
        assertEquals(4200, state.positionMs)
        controller.toggle("first.wav")
        assertTrue(state.isPlaying)
        assertEquals(1, players.size)
        assertEquals(4200, state.positionMs)
        assertEquals(2, player.starts)
    }

    @Test fun leavingDuringPreparationDoesNotStartHiddenPlayback() {
        controller.toggle("first.wav")
        controller.pause()
        players.single().onPrepared?.invoke()
        assertFalse(state.isLoading)
        assertFalse(state.isPlaying)
        assertEquals(0, players.single().starts)
        controller.toggle("first.wav")
        assertTrue(state.isPlaying)
    }

    @Test fun switchingInvalidatesAllLateCallbacksFromOldPlayer() {
        controller.toggle("first.wav")
        val old = players.single()
        val current = play("second.wav")
        old.onPrepared?.invoke()
        old.onCompletion?.invoke()
        old.onSeekComplete?.invoke()
        old.onError?.invoke()
        assertEquals(1, old.releases)
        assertEquals(0, old.starts)
        assertEquals("second.wav", state.path)
        assertTrue(state.isPlaying)
        assertEquals(0, current.releases)
    }

    @Test fun seekWhilePausedKeepsPausedAndIgnoresOldPolledPosition() {
        val player = play()
        controller.pause()
        controller.seekTo("first.wav", 8000)
        controller.updatePosition()
        assertEquals(8000, state.positionMs)
        assertFalse(state.isPlaying)
        player.completeSeek()
        assertEquals(8000, state.positionMs)
        assertFalse(state.isPlaying)
    }

    @Test fun rapidSeeksApplyTheLatestTargetAndClampBounds() {
        val player = play()
        controller.seekTo("first.wav", -100)
        controller.seekTo("first.wav", 2000)
        controller.seekTo("first.wav", 90000)
        assertEquals(listOf(0), player.seeks)
        assertEquals(12000, state.positionMs)
        player.completeSeek()
        assertEquals(listOf(0, 12000), player.seeks)
        player.completeSeek()
        assertEquals(12000, state.positionMs)
        assertTrue(state.isPlaying)
    }

    @Test fun completionKeepsControlsAndReplaySeeksToBeginning() {
        val player = play()
        player.onCompletion?.invoke()
        assertTrue(state.isComplete)
        assertFalse(state.isPlaying)
        assertEquals(12000, state.positionMs)
        controller.toggle("first.wav")
        assertEquals(listOf(0), player.seeks)
        assertTrue(state.isPlaying)
        assertFalse(state.isComplete)
        player.completeSeek()
        assertEquals(0, state.positionMs)
    }

    @Test fun deletedOrRemovedPausedClipIsReleasedAndOldSeekIsIgnored() {
        val player = play()
        controller.pause()
        controller.retainFiles(listOf("second.wav"))
        controller.seekTo("first.wav", 5000)
        assertNull(state.path)
        assertEquals(1, player.releases)
        assertTrue(player.seeks.isEmpty())
        player.onError?.invoke()
        assertFalse(state.hasError)
    }

    @Test fun asynchronousFailureReleasesOnceAndNextClipCanPlay() {
        val player = play()
        player.onError?.invoke()
        player.onCompletion?.invoke()
        assertTrue(state.hasError)
        assertNull(state.path)
        assertEquals(1, player.releases)
        play("second.wav")
        assertFalse(state.hasError)
        assertTrue(state.isPlaying)
    }

    @Test fun startOrSeekFailureClearsTheActivePlayer() {
        controller.toggle("first.wav")
        players.last().throwOnStart = true
        players.last().onPrepared?.invoke()
        assertTrue(state.hasError)
        assertEquals(1, players.last().releases)
        val player = play("second.wav")
        player.throwOnSeek = true
        controller.seekTo("second.wav", 5000)
        assertTrue(state.hasError)
        assertFalse(state.isPlaying)
        assertEquals(1, player.releases)
    }

    @Test fun repeatedDisposalIsSafeEvenDuringPreparation() {
        controller.toggle("first.wav")
        val player = players.single()
        controller.clear()
        controller.clear()
        player.onPrepared?.invoke()
        assertEquals(1, player.releases)
        assertEquals(0, player.starts)
        assertEquals(PlaybackState(), state)
    }

    @Test fun playbackTimeHandlesZeroMinutesAndHours() {
        assertEquals("0:00", formatPlaybackTime(-1))
        assertEquals("1:05", formatPlaybackTime(65999))
        assertEquals("1:01:01", formatPlaybackTime(3661000))
    }

    private class FakePlayer : PlaybackPlayer {
        override var onPrepared: (() -> Unit)? = null
        override var onCompletion: (() -> Unit)? = null
        override var onSeekComplete: (() -> Unit)? = null
        override var onError: (() -> Unit)? = null
        override val durationMs = 12000
        override var positionMs = 0
        var starts = 0
        var releases = 0
        var throwOnStart = false
        var throwOnSeek = false
        val seeks = mutableListOf<Int>()
        override fun prepare(path: String) = Unit
        override fun start() { check(!throwOnStart); starts++ }
        override fun pause() = Unit
        override fun seekTo(positionMs: Int) { check(!throwOnSeek); seeks += positionMs }
        override fun release() { releases++ }
        fun completeSeek() { positionMs = seeks.last(); onSeekComplete?.invoke() }
    }
}
