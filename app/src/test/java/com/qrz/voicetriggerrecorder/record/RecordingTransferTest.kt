package com.qrz.voicetriggerrecorder.record

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordingTransferTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://documents/document/new-recording")
    private lateinit var source: File
    private lateinit var original: ByteArray
    private lateinit var sidecar: File
    private val metadata = "{\"isExported\":false,\"custom\":\"preserve\"}"

    @Before fun setUp() {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "voice-recordings")
        source = File(directory, "transfer_${UUID.randomUUID()}.wav")
        val writer = WavFileWriter(source, 16000)
        assertTrue(writer.writeSamples(ShortArray(16000) { (it % 100).toShort() }, 16000))
        assertTrue(writer.closeAndCommit())
        original = source.readBytes()
        sidecar = File(directory, "${source.name}.json").apply { writeText(metadata) }
    }

    private fun assertOriginalUnchanged() {
        assertArrayEquals(original, source.readBytes())
        assertEquals(metadata, sidecar.readText())
    }

    @Test fun exportCopiesExactWavAndPreservesSidecar() {
        val destination = Destination()
        assertEquals(ExportOutcome.SAVED, RecordingTransfer(context, destination).export(source.name, uri))
        assertArrayEquals(original, destination.bytes.toByteArray())
        assertTrue(destination.closed)
        assertFalse(destination.deleted)
        assertOriginalUnchanged()
    }

    @Test fun legacyWavExportsWithoutCreatingSidecar() {
        sidecar.delete()
        val destination = Destination()
        assertEquals(ExportOutcome.SAVED, RecordingTransfer(context, destination).export(source.name, uri))
        assertFalse(sidecar.exists())
        assertArrayEquals(original, destination.bytes.toByteArray())
    }

    @Test fun shareCreatesIndependentReadOnlyContentUriAndKeepsOriginalName() {
        // AndroidX FileProvider uses literal '/' for canonical containment; Robolectric on
        // Windows has backslash paths. This integration runs on Linux CI and a real Android UID.
        org.junit.Assume.assumeTrue("FileProvider requires Android/Unix path semantics", File.separatorChar == '/')
        val intent = RecordingTransfer(context).prepareShare(source.name)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("audio/wav", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        val shared = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", shared.scheme)
        assertEquals("${context.packageName}.recording-shares", shared.authority)
        assertEquals(source.name, shared.lastPathSegment)
        assertEquals(shared, intent.clipData!!.getItemAt(0).uri)
        assertOriginalUnchanged()
        source.delete()
        context.contentResolver.openInputStream(shared)!!.use { assertArrayEquals(original, it.readBytes()) }
        assertThrows(SecurityException::class.java) { context.contentResolver.openOutputStream(shared, "w") }
        assertThrows(SecurityException::class.java) { context.contentResolver.delete(shared, null, null) }
    }

    @Test fun repeatedSharesUseDifferentSnapshots() {
        val transfer = RecordingTransfer(context)
        val first = transfer.createShareSnapshot(source.name)
        val second = transfer.createShareSnapshot(source.name)
        assertNotEquals(first, second)
        assertEquals(source.name, first.name)
        assertArrayEquals(original, first.readBytes())
        assertArrayEquals(original, second.readBytes())
        assertOriginalUnchanged()
        source.delete()
        assertArrayEquals(original, first.readBytes())
    }

    @Test fun shareRejectsTraversalPartialJsonAndMissingFiles() {
        val transfer = RecordingTransfer(context)
        listOf("../${source.name}", "..\\${source.name}", source.absolutePath,
            "${source.name}.part", sidecar.name, "missing.wav").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { transfer.prepareShare(name) }
        }
        assertOriginalUnchanged()
    }

    @Test fun truncatedWavCannotBeSharedEvenWithFinalizedSidecar() {
        source.writeBytes(original.copyOf(original.size - 100))
        sidecar.writeText("{\"isFinalized\":true,\"isCorrupted\":false}")
        assertThrows(IllegalArgumentException::class.java) { RecordingTransfer(context).prepareShare(source.name) }
        assertEquals(original.size - 100L, source.length())
    }

    @Test fun emptyAndGarbageWavAreRejectedWithoutOpeningDestination() {
        listOf(ByteArray(0), ByteArray(80) { 42 }).forEach { content ->
            source.writeBytes(content)
            val destination = Destination()
            assertEquals(ExportOutcome.SOURCE_UNAVAILABLE, RecordingTransfer(context, destination).export(source.name, uri))
            assertFalse(destination.opened)
            assertTrue(destination.deleted)
            assertArrayEquals(content, source.readBytes())
        }
    }

    @Test fun missingSourceCleansNewDocumentWithoutOpeningIt() {
        source.delete()
        val destination = Destination()
        assertEquals(ExportOutcome.SOURCE_UNAVAILABLE, RecordingTransfer(context, destination).export(source.name, uri))
        assertFalse(destination.opened)
        assertTrue(destination.deleted)
        assertEquals(metadata, sidecar.readText())
    }

    @Test fun nullDestinationStreamIsFailureAndCleansDocument() {
        val destination = Destination(nullStream = true)
        assertEquals(ExportOutcome.FAILED, RecordingTransfer(context, destination).export(source.name, uri))
        assertTrue(destination.deleted)
        assertOriginalUnchanged()
    }

    @Test fun writeFailureClosesStreamAndCleansDocument() {
        val destination = Destination(failWrite = true)
        assertEquals(ExportOutcome.FAILED, RecordingTransfer(context, destination).export(source.name, uri))
        assertTrue(destination.closed)
        assertTrue(destination.deleted)
        assertOriginalUnchanged()
    }

    @Test fun closeFailureNeverReportsSuccess() {
        val destination = Destination(failClose = true)
        assertEquals(ExportOutcome.FAILED, RecordingTransfer(context, destination).export(source.name, uri))
        assertTrue(destination.deleted)
        assertOriginalUnchanged()
    }

    @Test fun cleanupFailureIsReportedWithPartialDocument() {
        val destination = Destination(failWrite = true, canDelete = false)
        assertEquals(ExportOutcome.INCOMPLETE_DOCUMENT, RecordingTransfer(context, destination).export(source.name, uri))
        assertOriginalUnchanged()
    }

    @Test fun cancellationCleansDestinationAndKeepsOriginal() {
        val destination = Destination()
        assertEquals(ExportOutcome.FAILED, RecordingTransfer(context, destination).export(source.name, uri) {
            throw java.util.concurrent.CancellationException()
        })
        assertTrue(destination.deleted)
        assertTrue(destination.closed)
        assertOriginalUnchanged()
    }

    @Test fun ownOrNonContentDestinationNeverOpensOrDeletesAnything() {
        listOf(Uri.fromFile(source), Uri.parse("content://${context.packageName}.recording-shares/anything")).forEach {
            val destination = Destination()
            assertEquals(ExportOutcome.FAILED, RecordingTransfer(context, destination).export(source.name, it))
            assertFalse(destination.opened)
            assertFalse(destination.deleted)
        }
        assertOriginalUnchanged()
    }

    @Test fun pruningRemovesOnlyExpiredShareDirectories() {
        val transfer = RecordingTransfer(context)
        transfer.createShareSnapshot(source.name)
        val root = File(context.cacheDir, "recording-shares")
        val now = System.currentTimeMillis()
        val old = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        File(old, "old.wav").writeBytes(original)
        old.setLastModified(now - RecordingTransfer.SHARE_RETENTION_MS - 1000)
        val unrelated = File(root, "keep").apply { mkdirs(); setLastModified(1) }
        transfer.pruneExpiredShares(now)
        assertFalse(old.exists())
        assertTrue(unrelated.exists())
        assertTrue(root.walkTopDown().any { it.name == source.name })
        assertOriginalUnchanged()
    }

    private class Destination(
        val nullStream: Boolean = false,
        val failWrite: Boolean = false,
        val failClose: Boolean = false,
        val canDelete: Boolean = true
    ) : RecordingDocumentWriter {
        val bytes = ByteArrayOutputStream()
        var opened = false
        var closed = false
        var deleted = false
        override fun open(uri: Uri): OutputStream? {
            opened = true
            if (nullStream) return null
            return object : OutputStream() {
                override fun write(value: Int) {
                    if (failWrite) throw IOException("Disk full")
                    bytes.write(value)
                }
                override fun close() {
                    closed = true
                    if (failClose) throw IOException("Provider close failed")
                }
            }
        }
        override fun delete(uri: Uri): Boolean { deleted = true; return canDelete }
    }
}
