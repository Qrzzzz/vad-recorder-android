package com.qrz.voicetriggerrecorder

import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.qrz.voicetriggerrecorder.app.AppLanguage
import com.qrz.voicetriggerrecorder.app.AppNightMode
import com.qrz.voicetriggerrecorder.record.*
import com.qrz.voicetriggerrecorder.ui.RecordingHistoryViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HistoryAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixtures = mutableListOf<File>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var history: RecordingHistoryViewModel
    private val baseTime = LocalDate.now().atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun seed(index: Int, daysAgo: Int = 0): File {
        check(context.packageName.endsWith(".acceptance"))
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "voice-recordings")
        val file = File(directory, "history_${index}_${UUID.randomUUID()}.wav")
        fixtures += file
        val writer = WavFileWriter(file, 16000)
        assertTrue(writer.writeSamples(ShortArray(16000), 16000))
        assertTrue(writer.closeAndCommit())
        assertTrue(file.setLastModified(baseTime - daysAgo * 86_400_000L - index * 1000L))
        return file
    }

    private fun launch() {
        RecorderPreferences(context).apply {
            saveAppLanguage(AppLanguage.ENGLISH)
            saveAppNightMode(AppNightMode.LIGHT)
        }
        UiDevice.getInstance(instrumentation).executeShellCommand(
            "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {
            AppLanguage.ENGLISH.apply()
            history = ViewModelProvider(it)[RecordingHistoryViewModel::class.java]
        }
        awaitCount(fixtures.size)
    }

    private fun awaitCount(count: Int) {
        compose.waitUntil(30_000) { !history.state.value.loading && history.state.value.files.size == count }
    }

    private fun refresh(count: Int) {
        val start = SystemClock.elapsedRealtime()
        scenario!!.onActivity { history.refresh() }
        awaitCount(count)
        Log.i("HistoryAcceptance", "refresh files=$count elapsedMs=${SystemClock.elapsedRealtime() - start}")
    }

    private fun scroll(file: File) {
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("recording:${file.name}"))
    }

    private fun action(file: File, id: Int) = compose.onNode(
        hasText(context.getString(id)) and hasAnyAncestor(hasTestTag("recording:${file.name}"))
    )

    private fun composedCount() = compose.onAllNodes(
        SemanticsMatcher("recording rows") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("recording:") == true
        }, useUnmergedTree = true
    ).fetchSemanticsNodes().size

    @After fun cleanup() {
        scenario?.close()
        fixtures.forEach {
            RecordingMetadataStore.setFavorite(it, false)
            RecordingRepository(context).deleteRecording(it.path)
        }
    }

    @Test fun batchSelectionSurvivesRecreationAndConfirmationProtectsFavorites() {
        val favorite = seed(0)
        val ordinary = seed(1)
        assertTrue(RecordingMetadataStore.setFavorite(favorite, true))
        launch()
        fun clickText(id: Int) {
            compose.onNodeWithTag("history-list").performScrollToNode(hasText(context.getString(id)))
            compose.onNodeWithText(context.getString(id)).performScrollTo().performClick()
        }
        clickText(R.string.batch_organize)
        clickText(R.string.select_all)
        assertEquals(2, history.state.value.selected.size)
        compose.onNodeWithText(context.getString(R.string.storage_overview)).performScrollTo().assertIsDisplayed()
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(
            File(context.filesDir, "v26-organize.png")))
        scenario!!.recreate()
        scenario!!.onActivity { history = ViewModelProvider(it)[RecordingHistoryViewModel::class.java] }
        awaitCount(2)
        assertEquals(2, history.state.value.selected.size)
        clickText(R.string.export_selected)
        val device = UiDevice.getInstance(instrumentation)
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.pkg(java.util.regex.Pattern.compile(".*documentsui.*"))), 10000))
        device.pressBack()
        compose.waitForIdle()
        assertTrue(ordinary.exists())
        assertTrue(favorite.exists())
        clickText(R.string.delete_selected)
        compose.onNodeWithText(context.getString(R.string.batch_delete_body, 2)).assertIsDisplayed()
        compose.waitForIdle()
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(context.filesDir, "v26-confirm.png")))
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertTrue(ordinary.exists())
        assertTrue(favorite.exists())
        clickText(R.string.delete_selected)
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        awaitCount(1)
        assertFalse(ordinary.exists())
        assertTrue(favorite.exists())
        assertEquals(1, history.state.value.batchResult?.protected)
        scroll(favorite)
        action(favorite, R.string.action_more).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.action_delete)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.unfavorite)).performClick()
        compose.waitUntil(8000) { !history.state.value.deleting && !history.state.value.files.single().isFavorite }
        assertFalse(RecordingMetadataStore.loadOrCreate(favorite).isFavorite)
    }

    @Test fun fiveHundredSameNightRowsAreLazyAndKeepPlaybackAndMenuIdentity() {
        repeat(500) { seed(it) }
        launch()
        val selected = fixtures[250]
        scroll(selected)
        val composed = composedCount()
        Log.i("HistoryAcceptance", "sameNight total=500 composed=$composed")
        assertTrue("Must not compose an entire night", composed in 1 until 500)
        action(selected, R.string.action_play).performScrollTo().performClick()
        compose.waitUntil(8000) {
            compose.onAllNodesWithContentDescription(context.getString(R.string.playback_seek)).fetchSemanticsNodes().isNotEmpty()
        }
        // Playback completion still retains its identity; use the slider's ancestor after mutations.
        action(selected, R.string.action_more).performScrollTo().performClick()
        val inserted = seed(-1)
        refresh(501)
        compose.onNode(hasContentDescription(context.getString(R.string.playback_seek)) and
            hasAnyAncestor(hasTestTag("recording:${selected.name}"))).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_delete)).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        compose.onAllNodesWithText(context.getString(R.string.action_delete)).onLast().performClick()
        awaitCount(500)
        assertFalse(selected.exists())
        assertTrue(inserted.exists())
        assertTrue(fixtures[249].exists())
        compose.onAllNodesWithContentDescription(context.getString(R.string.playback_seek)).assertCountEquals(0)
        val survivor = fixtures[251]
        scroll(survivor)
        action(survivor, R.string.action_play).performScrollTo().performClick()
        compose.waitUntil(8000) {
            compose.onAllNodesWithContentDescription(context.getString(R.string.playback_seek)).fetchSemanticsNodes().isNotEmpty()
        }
        scenario!!.onActivity { history.delete(inserted.path) }
        awaitCount(499)
        compose.onNode(hasContentDescription(context.getString(R.string.playback_seek)) and
            hasAnyAncestor(hasTestTag("recording:${survivor.name}"))).assertExists()
        scroll(fixtures[499])
        action(fixtures[499], R.string.action_more).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.action_share)).assertExists()
        UiDevice.getInstance(instrumentation).pressBack()
        repeat(5) { scenario!!.onActivity { history.refresh() } }
        awaitCount(499)
        assertFalse(history.state.value.files.any { it.path == selected.path || it.path == inserted.path })
    }

    @Test fun multipleNightsRemainIndividuallyScrollableAndReloadAfterRecreation() {
        repeat(40) { seed(it, it / 2) }
        launch()
        scroll(fixtures.last())
        action(fixtures.last(), R.string.action_more).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.action_export)).assertExists()
        UiDevice.getInstance(instrumentation).pressBack()
        assertTrue(composedCount() in 1 until 40)
        scenario!!.recreate()
        scenario!!.onActivity { history = ViewModelProvider(it)[RecordingHistoryViewModel::class.java] }
        awaitCount(40)
        scroll(fixtures.first())
        action(fixtures.first(), R.string.action_play).performScrollTo().performClick()
        compose.waitUntil(8000) {
            compose.onAllNodesWithContentDescription(context.getString(R.string.playback_seek)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
