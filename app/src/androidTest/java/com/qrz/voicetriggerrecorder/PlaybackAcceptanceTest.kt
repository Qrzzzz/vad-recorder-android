package com.qrz.voicetriggerrecorder

import android.os.Environment
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.qrz.voicetriggerrecorder.app.AppLanguage
import com.qrz.voicetriggerrecorder.app.AppNightMode
import com.qrz.voicetriggerrecorder.record.RecorderPreferences
import com.qrz.voicetriggerrecorder.record.RecordingRepository
import com.qrz.voicetriggerrecorder.record.WavFileWriter
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaybackAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val fixtures = mutableListOf<File>()
    private var seekLabel = ""

    @Before fun seedLegacyWavClips() {
        check(context.packageName.endsWith(".acceptance")) { "Use -PdeviceAcceptance to protect user data" }
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "voice-recordings")
        repeat(2) { index ->
            val file = File(directory, "playback_${index}_${UUID.randomUUID()}.wav")
            fixtures += file
            val writer = WavFileWriter(file, 16000)
            // Real WAV decoding, with silence to avoid disturbing the connected device's owner.
            repeat(15) { assertTrue(writer.writeSamples(ShortArray(16000), 16000)) }
            assertTrue(writer.closeAndCommit())
        }
    }

    @After fun cleanFixtures() {
        scenario?.close()
        fixtures.forEach { RecordingRepository(context).deleteRecording(it.name) }
    }

    private fun launch(language: AppLanguage, mode: AppNightMode) {
        val preferences = RecorderPreferences(context)
        preferences.saveAppLanguage(language)
        preferences.saveAppNightMode(mode)
        // Bring the independent package forward before ActivityScenario launches its activity.
        // Some ROMs restrict starts from an instrumentation process without a foreground activity.
        UiDevice.getInstance(instrumentation).executeShellCommand(
            "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // API 33+ locale changes need an active AppCompat delegate/context.
        scenario!!.onActivity { language.apply(); mode.apply() }
        compose.waitForIdle()
        scenario!!.onActivity {
            val configuration = it.resources.configuration
            assertEquals(if (language == AppLanguage.ENGLISH) "en" else "zh", configuration.locales[0].language)
            assertEquals(
                if (mode == AppNightMode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            )
            seekLabel = it.getString(R.string.playback_seek)
        }
    }

    private fun label(id: Int): String {
        var text = ""
        scenario!!.onActivity { text = it.getString(id) }
        return text
    }

    private fun clipAction(index: Int, id: Int): SemanticsNodeInteraction = compose.onNode(
        hasText(label(id)) and hasAnyAncestor(hasTestTag("recording:${fixtures[index].name}"))
    )

    private fun clickClip(index: Int, id: Int) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(fixtures[index].name))
        if (id == R.string.action_delete) {
            clipAction(index, R.string.action_more).performScrollTo().performClick()
            compose.onNodeWithText(label(id)).performClick()
            return
        }
        clipAction(index, id).performScrollTo().performClick()
    }

    private fun slider() = compose.onNodeWithContentDescription(seekLabel)
    private fun position(): Float = slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    private fun waitForPosition(minimum: Float, maximum: Float = 15000f) {
        compose.waitUntil(8000) { runCatching { position() in minimum..maximum }.getOrDefault(false) }
    }

    private fun seek(position: Float) {
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(position) }
    }

    private fun screenshot(name: String) {
        slider().performScrollTo()
        clipAction(0, R.string.action_resume_playback).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), name)))
    }

    @Test fun chineseDarkPauseTouchSeekResumeAndReplay() {
        launch(AppLanguage.CHINESE_SIMPLIFIED, AppNightMode.DARK)
        clickClip(0, R.string.action_play)
        waitForPosition(800f)
        clipAction(0, R.string.action_pause_playback).performScrollTo().performClick()
        val pausedAt = position()
        Thread.sleep(400)
        assertEquals(pausedAt, position(), 150f)
        slider().performScrollTo().performTouchInput {
            swipe(center.copy(x = width * 0.25f), center.copy(x = width * 0.7f), 350)
        }
        waitForPosition(8000f, 13000f)
        clipAction(0, R.string.action_resume_playback).assertExists()
        seek(4000f)
        waitForPosition(3900f, 4200f)
        screenshot("v23-playback-zh-dark.png")
        clipAction(0, R.string.action_resume_playback).performScrollTo().performClick()
        waitForPosition(4800f)
        seek(14700f)
        val replay = label(R.string.action_replay)
        compose.waitUntil(8000) { compose.onAllNodesWithText(replay).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(15000f, position(), 100f)
        clipAction(0, R.string.action_replay).performScrollTo().performClick()
        waitForPosition(200f, 2500f)
        clipAction(0, R.string.action_pause_playback).assertExists()
    }

    @Test fun englishLightBackgroundSwitchDeleteAndRecreate() {
        launch(AppLanguage.ENGLISH, AppNightMode.LIGHT)
        clickClip(0, R.string.action_play)
        waitForPosition(500f)
        seek(3000f)
        waitForPosition(3000f)
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        clipAction(0, R.string.action_resume_playback).assertExists()
        val pausedAt = position()
        Thread.sleep(400)
        assertEquals(pausedAt, position(), 150f)
        screenshot("v23-playback-en-light.png")
        // Switching clips collapses the old player and starts only the selected clip.
        clickClip(1, R.string.action_play)
        waitForPosition(500f, 2500f)
        compose.onAllNodesWithContentDescription(seekLabel).assertCountEquals(1)
        clipAction(0, R.string.action_play).assertExists()
        clickClip(1, R.string.action_delete)
        compose.onAllNodesWithText(label(R.string.action_delete)).onLast().performClick()
        compose.waitForIdle()
        assertFalse(fixtures[1].exists())
        assertFalse(File(fixtures[1].parentFile, "${fixtures[1].name}.json").exists())
        compose.onAllNodesWithContentDescription(seekLabel).assertCountEquals(0)
        clickClip(0, R.string.action_play)
        waitForPosition(500f)
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(seekLabel).assertCountEquals(0)
        clickClip(0, R.string.action_play)
        waitForPosition(200f, 2500f)
    }
}
