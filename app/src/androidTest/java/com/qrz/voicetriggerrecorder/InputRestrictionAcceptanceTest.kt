package com.qrz.voicetriggerrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.qrz.voicetriggerrecorder.record.RecordForegroundService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a second isolated build installed as .inputprobe, to provide a different UID. */
@RunWith(AndroidJUnit4::class)
class InputRestrictionAcceptanceTest {
    @Test fun competingForegroundRecorderSilencesAndReleasesInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".acceptance"))
        val probe = "com.qrz.voicetriggerrecorder.inputprobe"
        val device = UiDevice.getInstance(instrumentation)
        fun shell(command: String) = device.executeShellCommand(command)
        assumeTrue("Install the isolated inputprobe APK before this test", shell("pm path $probe").contains("package:"))
        val launch = Intent().setClassName(probe, MainActivity::class.java.name)
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("pm grant $probe ${Manifest.permission.RECORD_AUDIO}")
        fun awaitState(expected: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (RecordForegroundService.uiState.value.inputSilenced != expected &&
                SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertEquals(expected, RecordForegroundService.uiState.value.inputSilenced)
            assertTrue(RecordForegroundService.uiState.value.serviceRunning)
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            context.startForegroundService(Intent(context, RecordForegroundService::class.java).apply {
                action = RecordForegroundService.ACTION_START
            })
            awaitState(false)
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("Probe UI must be ready before scrolling",
                device.wait(Until.hasObject(By.pkg(probe).scrollable(true)), 10_000))
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Start listening")
            device.findObject(By.text("Start listening")).click()
            awaitState(true)
            val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue(notifications.any {
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ==
                    context.getString(R.string.input_silenced)
            })
            device.findObject(By.text("Stop listening")).click()
            awaitState(false)
        } finally {
            context.startService(Intent(context, RecordForegroundService::class.java).apply {
                action = RecordForegroundService.ACTION_STOP
            })
            shell("am force-stop $probe")
            scenario.close()
        }
    }
}
