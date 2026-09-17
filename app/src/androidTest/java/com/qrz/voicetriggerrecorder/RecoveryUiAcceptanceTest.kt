package com.qrz.voicetriggerrecorder

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.qrz.voicetriggerrecorder.app.AppLanguage
import com.qrz.voicetriggerrecorder.app.AppNightMode
import com.qrz.voicetriggerrecorder.record.*
import com.qrz.voicetriggerrecorder.ui.RecordingHistoryViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecoveryUiAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun showsInterruptedAudioAndExplainsUnknownRemnantBeforeConfirmedCleanup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".acceptance"))
        val directory = RecordingStorage(context).writeDirectory().apply { mkdirs() }
        val target = File(directory, "acceptance-ui-v25.wav")
        val unknown = File(directory, "acceptance-ui-unknown-v25.wav.part")
        check(!target.exists() && !unknown.exists())
        WavFileWriter(target, 44100).apply {
            writeSamples(ShortArray(4410), 4410)
            preserveForRecovery()
        }
        unknown.writeBytes(ByteArray(128))
        RecorderPreferences(context).apply {
            saveAppLanguage(AppLanguage.ENGLISH)
            saveAppNightMode(AppNightMode.LIGHT)
        }
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am start -W -n ${context.packageName}/${MainActivity::class.java.name}")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var history: RecordingHistoryViewModel
        try {
            scenario.onActivity {
                AppLanguage.ENGLISH.apply()
                history = ViewModelProvider(it)[RecordingHistoryViewModel::class.java]
            }
            compose.waitUntil(15_000) { history.state.value.files.any { it.name == target.name } }
            compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("recovery-notice"))
            compose.onNodeWithText("Pending: 0 · Unknown format: 1 · No complete samples: 0 · Name conflict: 0. Fragments are kept separately. Refresh to retry.").assertIsDisplayed()
            assertTrue(device.takeScreenshot(File(context.filesDir, "v25-remnant-en.png")))
            compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("recording:${target.name}"))
            compose.onNodeWithText("Interrupted recording · saved audio").assertIsDisplayed()
            assertTrue(device.takeScreenshot(File(context.filesDir, "v25-recovery-en.png")))
            compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("recovery-notice"))
            compose.onNodeWithText("Clear remaining fragments").performClick()
            compose.onNodeWithText("Permanently delete these fragments? Their audio cannot be recovered afterward. Completed recordings will be kept.").assertIsDisplayed()
            compose.onAllNodesWithText("Clear remaining fragments").onLast().performClick()
            compose.waitUntil(10_000) { !unknown.exists() && !history.state.value.deleting }
            assertTrue(target.isFile)
            assertEquals("Recovered", RecordingMetadataStore.loadOrCreate(target).closeReason)
        } finally {
            scenario.close()
            listOf("", ".part", ".recovery", ".json").forEach { File(directory, target.name + it).delete() }
            unknown.delete()
        }
    }
}
