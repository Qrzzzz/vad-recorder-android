package com.qrz.voicetriggerrecorder

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.SystemClock
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.qrz.voicetriggerrecorder.record.RecorderConfig
import com.qrz.voicetriggerrecorder.record.RecordForegroundService
import com.qrz.voicetriggerrecorder.record.RecordingRepository
import com.qrz.voicetriggerrecorder.record.RecordingCloseReason
import com.qrz.voicetriggerrecorder.record.RecordingStateMachine
import com.qrz.voicetriggerrecorder.record.WavFileWriter
import com.qrz.voicetriggerrecorder.ui.RecorderPhase
import com.qrz.voicetriggerrecorder.ui.RecorderUiState
import org.junit.Assert.*
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun freshInstallRequestsMicrophoneAndOrdinaryDenialCanRetry() {
        check(context.packageName.endsWith(".acceptance")) { "Use -PdeviceAcceptance to protect user data" }
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        device.executeShellCommand("cmd statusbar collapse")
        device.pressHome()
        launchApp()
        clickStart()
        val deny = device.wait(Until.findObject(By.res("com.lbe.security.miui", "permission_deny_button")), 1_000)
            ?: device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_deny_button")), 2_000)
            ?: device.wait(Until.findObject(textMatches("(?i).*(不允许|拒绝|Don.t allow|Deny).*")), 3_000)
        assertNotNull("The first tap must show the system microphone permission dialog", deny)
        device.takeScreenshot(File(context.getExternalFilesDir(null), "permission-first-request.png"))
        device.findObject(UiSelector().textMatches("(?i)^(Deny|Don.t allow|不允许|拒绝)$")).click()
        device.waitForIdle()
        clickStart()
        val allow = device.wait(Until.findObject(textMatches("(?i).*(仅在使用.*允许|使用该应用时|使用应用时|While using the app).*")), 5_000)
        assertNotNull("Ordinary denial must allow another system request", allow)
        device.takeScreenshot(File(context.getExternalFilesDir(null), "permission-retry.png"))
        device.findObject(UiSelector().textMatches("(?i).*(仅在使用.*允许|使用该应用时|使用应用时|While using the app).*")).click()
        device.waitForIdle()
        // Notification permission is optional and distinct from microphone permission.
        device.wait(Until.findObject(textMatches("(?i)^(允许|Allow)$")), 2_000)?.click()
        val stop = findAction("(?i)^(停止监听|Stop listening)$")
        assertNotNull(stop)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "listening.png"))
        stop!!.click()
    }

    @Test
    fun realFilesystemWriteAndCommitFailuresRemainVisible() {
        val scratch = File(context.cacheDir, "storage-acceptance").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File = scratch
        }
        var state = RecorderUiState()
        val machine = RecordingStateMachine(isolated, RecorderConfig()) { state = it(state) }
        repeat(45) { machine.onFrame(ShortArray(320) { 1000 }, true) }
        val writer = RecordingStateMachine::class.java.getDeclaredField("writer")
            .apply { isAccessible = true }.get(machine) as WavFileWriter
        val handle = WavFileWriter::class.java.getDeclaredField("raf")
            .apply { isAccessible = true }.get(writer) as RandomAccessFile
        handle.close()
        machine.onFrame(ShortArray(320) { 1000 }, true)
        machine.closeCurrentFileIfNeeded(RecordingCloseReason.ManualStop)
        assertTrue(machine.hasStorageFailure)
        assertFalse(state.serviceRunning)
        assertEquals(RecorderPhase.RECORDER_FAILED, state.recorderPhase)
        assertEquals(context.getString(R.string.error_recording_storage_failed), state.errorMessage)

        val target = File(scratch, "collision.wav")
        val existing = byteArrayOf(1, 2, 3)
        val collisionWriter = WavFileWriter(target, 16_000)
        collisionWriter.writeSamples(shortArrayOf(4, 5, 6), 3)
        target.writeBytes(existing)
        assertFalse(collisionWriter.closeAndCommit())
        assertFalse(collisionWriter.closeAndCommit())
        assertArrayEquals(existing, target.readBytes())
        target.delete()
    }

    @Test
    fun microphoneCaptureSavesAfterThirtySecondsInBackground() {
        check(context.packageName.endsWith(".acceptance"))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        awaitState(5_000) { !RecordForegroundService.uiState.value.serviceRunning }
        device.executeShellCommand("cmd statusbar collapse")
        device.pressHome()
        launchApp()
        clickStart()
        awaitState(5_000) { RecordForegroundService.uiState.value.serviceRunning }
        val manager = context.getSystemService(AudioManager::class.java)
        val originalVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val sampleRate = 16_000
        // A generated acoustic stimulus passes through the real speaker, microphone,
        // AudioRecord, VAD, foreground service and WAV writer (not injected PCM).
        val samples = ShortArray(sampleRate * 3) { index ->
            val t = index.toDouble() / sampleRate
            val envelope = 0.5 + 0.5 * kotlin.math.sin(2 * Math.PI * 4 * t)
            (envelope * (9_000 * kotlin.math.sin(2 * Math.PI * 220 * t) +
                6_000 * kotlin.math.sin(2 * Math.PI * 660 * t))).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MODIFY_AUDIO_SETTINGS)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC,
                manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0)
            SystemClock.sleep(1_500) // Let the noise floor calibrate before the stimulus.
            track.write(samples, 0, samples.size)
            track.play()
            awaitState(5_000) { RecordForegroundService.uiState.value.currentFileName != null }
            device.takeScreenshot(File(context.getExternalFilesDir(null), "recording.png"))
            SystemClock.sleep(3_000)
            track.stop()
            device.pressHome()
            awaitState(45_000) { RecordForegroundService.uiState.value.savedCount > 0 }
            val saved = RecordingRepository(context).listRecordings().first()
            assertTrue(saved.isFinalized)
            assertFalse(saved.isCorrupted)
            assertEquals("EndSilence", saved.closeReason)
            assertTrue(saved.durationMs!! > 0)
            launchApp()
            device.waitForIdle()
            device.takeScreenshot(File(context.getExternalFilesDir(null), "saved-after-background.png"))
            // Verify the actual WAV is decodable by the platform playback engine.
            android.media.MediaPlayer().apply {
                try { setDataSource(saved.path); prepare(); assertTrue(duration > 0) }
                finally { release() }
            }
        } finally {
            track.release()
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            context.startService(Intent(context, RecordForegroundService::class.java).apply {
                action = RecordForegroundService.ACTION_STOP
            })
        }
    }

    private fun awaitState(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for recorder state: ${RecordForegroundService.uiState.value}", predicate())
    }

    private fun textMatches(pattern: String) = By.text(java.util.regex.Pattern.compile(pattern))

    private fun launchApp() {
        device.executeShellCommand("am start -W -n ${context.packageName}/com.qrz.voicetriggerrecorder.MainActivity")
    }

    private fun clickStart() {
        val start = findAction("(?i)^(开始监听|授权并开始|Grant and start|Start listening)$")
        assertNotNull("Start listening button", start)
        start!!.click()
    }

    private fun findAction(pattern: String): androidx.test.uiautomator.UiObject2? {
        assertTrue("App must be foreground", device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
        device.waitForIdle()
        device.wait(Until.findObject(textMatches(pattern)), 1_000)?.let { return it }
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "action-window.xml"))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "action-window.png"))
        if (!device.wait(Until.hasObject(By.scrollable(true)), 10_000)) return null
        // Respect device font/display size: the primary action may be below the fold.
        UiScrollable(UiSelector().packageName(context.packageName).scrollable(true)).setMaxSearchSwipes(10)
            .scrollIntoView(UiSelector().textMatches(pattern))
        return device.wait(Until.findObject(textMatches(pattern)), 3_000)
    }
}
