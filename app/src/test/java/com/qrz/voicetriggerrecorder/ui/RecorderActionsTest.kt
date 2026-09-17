package com.qrz.voicetriggerrecorder.ui

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.qrz.voicetriggerrecorder.record.RecordForegroundService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecorderActionsTest {
    @Test fun commonStartEntryPausesBeforeDispatchAndClearsPendingAutoplay() {
        val base: Context = ApplicationProvider.getApplicationContext()
        val interlock = PlaybackInterlock.shared
        interlock.unblock()
        val player = FakePlayer()
        val controller = PlaybackController { player }
        var calls = 0
        val context = object : ContextWrapper(base) {
            override fun startForegroundService(service: Intent): ComponentName {
                calls++
                assertTrue(interlock.blocked.value)
                assertFalse(controller.state.value.isPlaying)
                assertEquals(RecordForegroundService.ACTION_START, service.action)
                return ComponentName(base, RecordForegroundService::class.java)
            }
        }
        try {
            controller.toggle("clip.wav")
            // This same entry is called by the button and by the permission-granted callback.
            startService(context)
            player.onPrepared?.invoke()
            assertEquals(0, player.starts)
            interlock.unblock()
            controller.toggle("clip.wav")
            assertEquals(1, player.starts)
            startService(context)
            assertEquals(1, player.pauses)
            assertEquals(2, calls)
        } finally {
            controller.clear()
            interlock.unblock()
        }
    }

    private class FakePlayer : PlaybackPlayer {
        override var onPrepared: (() -> Unit)? = null
        override var onCompletion: (() -> Unit)? = null
        override var onSeekComplete: (() -> Unit)? = null
        override var onError: (() -> Unit)? = null
        override val durationMs = 1000
        override val positionMs = 0
        var starts = 0
        var pauses = 0
        override fun prepare(path: String) = Unit
        override fun start() { starts++ }
        override fun pause() { pauses++ }
        override fun seekTo(positionMs: Int) = Unit
        override fun release() = Unit
    }
}
