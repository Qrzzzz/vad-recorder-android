package com.qrz.voicetriggerrecorder

import android.content.res.Configuration
import android.os.Environment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.qrz.voicetriggerrecorder.app.AppLanguage
import com.qrz.voicetriggerrecorder.app.AppNightMode
import com.qrz.voicetriggerrecorder.record.RecorderPreferences
import com.qrz.voicetriggerrecorder.record.RecordingRepository
import com.qrz.voicetriggerrecorder.record.RecordingMetadataStore
import com.qrz.voicetriggerrecorder.record.RecordingCloseReason
import com.qrz.voicetriggerrecorder.ui.RecordingHistoryViewModel
import androidx.lifecycle.ViewModelProvider
import com.qrz.voicetriggerrecorder.record.WavFileWriter
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class TransferAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var source: File
    private lateinit var original: ByteArray
    private lateinit var metadata: String

    @Before fun seedRecording() {
        check(context.packageName.endsWith(".acceptance"))
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "voice-recordings")
        source = File(directory, "transfer_${UUID.randomUUID()}.wav")
        val writer = WavFileWriter(source, 16000)
        repeat(3) { assertTrue(writer.writeSamples(ShortArray(16000), 16000)) }
        assertTrue(writer.closeAndCommit())
        // Browsing legacy WAVs is read-only; seed finalized metadata explicitly.
        assertTrue(RecordingMetadataStore.writeFinalized(
            source, source.lastModified() - 3000, source.lastModified(), 16000, 0,
            RecordingCloseReason.ManualStop, "acceptance"
        ))
        original = source.readBytes()
    }

    @After fun cleanFixtures() {
        device.pressBack()
        scenario?.close()
        RecordingRepository(context).deleteRecording(source.name)
        // Only this test's UUID-named destination, in the Download directory.
        device.executeShellCommand("rm -f /sdcard/Download/${source.name}")
    }

    private fun launch(language: AppLanguage, mode: AppNightMode) {
        RecorderPreferences(context).apply { saveAppLanguage(language); saveAppNightMode(mode) }
        device.executeShellCommand("am start -W -n ${context.packageName}/${MainActivity::class.java.name}")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { language.apply(); mode.apply() }
        compose.waitForIdle()
        lateinit var history: RecordingHistoryViewModel
        scenario!!.onActivity { history = ViewModelProvider(it)[RecordingHistoryViewModel::class.java] }
        compose.waitUntil(15000) { history.state.value.files.any { it.path == source.path } }
        scenario!!.onActivity {
            assertEquals(if (language == AppLanguage.ENGLISH) "en" else "zh", it.resources.configuration.locales[0].language)
            assertEquals(
                if (mode == AppNightMode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            )
        }
        metadata = File(source.parentFile, "${source.name}.json").readText()
    }

    private fun label(id: Int): String {
        var value = ""
        scenario!!.onActivity { value = it.getString(id) }
        return value
    }

    private fun openMenu() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(source.name))
        compose.onNode(hasText(label(R.string.action_more)) and hasAnyAncestor(hasTestTag("recording:${source.name}")))
            .performScrollTo().performClick()
    }

    private fun action(id: Int) {
        openMenu()
        compose.onNodeWithText(label(id)).assertIsEnabled()
        device.waitForIdle()
        device.wait(Until.findObject(By.text(label(id))), 5000)!!.click()
        compose.waitForIdle()
    }

    private fun waitForPicker() {
        assertTrue(device.wait(Until.hasObject(By.pkg(Pattern.compile(".*documentsui.*"))), 10000))
    }

    private fun clickSystem(selector: BySelector) {
        // DocumentsUI refreshes its roots asynchronously. Re-resolve nodes invalidated before tapping.
        var clicked = false
        compose.waitUntil(5000) {
            try {
                device.findObject(selector)?.let { it.click(); clicked = true }
            } catch (_: StaleObjectException) { }
            clicked
        }
    }

    private fun assertOriginalUnchanged() {
        assertArrayEquals(original, source.readBytes())
        assertEquals(metadata, File(source.parentFile, "${source.name}.json").readText())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(context.filesDir, name)))
    }

    private fun receiver() = run {
        // Drive Compose's test clock while the asynchronous copy publishes its chooser intent.
        // Android 11 can show the test application's package instead of the Activity label.
        val selector = By.text(Pattern.compile("WAV test receiver|" + Pattern.quote(context.packageName + ".test")))
        runCatching { compose.waitUntil(15000) { device.hasObject(selector) } }
        device.findObject(selector)
    }.also {
        if (it == null) {
            device.dumpWindowHierarchy(File(context.filesDir, "v24-share-failure.xml"))
            device.takeScreenshot(File(context.filesDir, "v24-share-failure.png"))
        }
    }

    @Test fun englishLightExportsThroughSystemPicker() {
        launch(AppLanguage.ENGLISH, AppNightMode.LIGHT)
        openMenu()
        compose.onNodeWithText(label(R.string.action_share)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.action_export)).assertIsDisplayed()
        capture("v24-menu-en-light.png")
        compose.onNodeWithText(label(R.string.action_export)).performClick()
        waitForPicker()
        // Select Downloads explicitly; the system picker may remember another provider.
        device.findObject(By.desc(Pattern.compile("Show roots|显示根目录|显示根文件夹|显示位置")))?.click()
        device.waitForIdle()
        clickSystem(By.res("android:id/title").text(Pattern.compile("Downloads|下载")))
        assertTrue(device.wait(Until.gone(By.text(Pattern.compile("Save to|保存到"))), 5000))
        device.waitForIdle()
        device.dumpWindowHierarchy(File(context.filesDir, "v24-picker-before-save.xml"))
        clickSystem(By.res("android:id/button1"))
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        // The Snackbar can finish while Compose's test clock advances. Verify the actual file.
        try {
            compose.waitUntil(15000) {
                device.executeShellCommand("sha256sum /sdcard/Download/${source.name} 2>/dev/null").startsWith(expectedHash)
            }
        } catch (error: Exception) {
            device.dumpWindowHierarchy(File(context.filesDir, "v24-export-failure.xml"))
            throw error
        }
        val exportedHash = device.executeShellCommand("sha256sum /sdcard/Download/${source.name}").trim().substringBefore(' ')
        assertEquals(expectedHash, exportedHash)
        assertOriginalUnchanged()
    }

    @Test fun sharesReadOnlyWavAcrossUid() {
        launch(AppLanguage.ENGLISH, AppNightMode.LIGHT)
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        action(R.string.action_share)
        val receiver = receiver()
        assertNotNull("Test receiver in the real Android Sharesheet", receiver)
        receiver!!.click()
        val received = device.wait(Until.findObject(By.textStartsWith("SHARE_")), 10000)?.text
        if (received != "SHARE_OK:$expectedHash") {
            device.dumpWindowHierarchy(File(context.filesDir, "v24-receiver-failure.xml"))
            device.takeScreenshot(File(context.filesDir, "v24-receiver-failure.png"))
        }
        assertEquals("SHARE_OK:$expectedHash", received)
        assertOriginalUnchanged()
        device.pressBack()
    }

    @Test fun chineseDarkCancelExportAcrossRecreationAndCancelSharePreserveOriginal() {
        launch(AppLanguage.CHINESE_SIMPLIFIED, AppNightMode.DARK)
        openMenu()
        compose.onNodeWithText("分享录音").assertIsDisplayed()
        compose.onNodeWithText("另存为文件").assertIsDisplayed()
        capture("v24-menu-zh-dark.png")
        var activity: MainActivity? = null
        scenario!!.onActivity { activity = it }
        compose.onNodeWithText("另存为文件").performClick()
        waitForPicker()
        // ActivityScenario.recreate() first forces RESUMED, which cannot happen behind a picker.
        instrumentation.runOnMainSync { activity!!.recreate() }
        device.waitForIdle()
        device.pressBack()
        compose.waitForIdle()
        assertOriginalUnchanged()
        // A cancelled request must unlock the row and allow another operation.
        action(R.string.action_share)
        assertNotNull(receiver())
        device.pressBack()
        compose.waitForIdle()
        assertOriginalUnchanged()
        openMenu()
        compose.onNodeWithText(label(R.string.action_export)).assertIsEnabled()
    }
}
