package com.qrz.voicetriggerrecorder.record

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchOrganizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://documents/document/night")
    private fun wav(internal: Boolean = false, name: String = "${UUID.randomUUID()}.wav"): File {
        val root = if (internal) File(context.filesDir, "music/voice-recordings")
            else File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "voice-recordings")
        return File(root, name).also {
            WavFileWriter(it, 16000).apply {
                assertTrue(writeSamples(ShortArray(160) { 42 }, 160))
                assertTrue(closeAndCommit())
            }
        }
    }

    @Test fun favoritePersistsPreservesUnknownMetadataAndProtectsDeletionAtRepositoryBoundary() = runBlocking {
        val file = wav()
        val json = File(file.parentFile, "${file.name}.json").apply { writeText("{\"custom\":42}") }
        val bytes = file.readBytes()
        val modified = file.lastModified()
        val repo = RecordingRepository(context)
        assertTrue(repo.setFavorite(file.path, true))
        assertEquals(42, JSONObject(json.readText()).getInt("custom"))
        assertTrue(RecordingRepository(context).scan().single { it.path == file.path }.isFavorite)
        assertEquals(DeleteOutcome.PROTECTED, repo.delete(file.path))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(modified, file.lastModified())
        assertTrue(repo.setFavorite(file.path, false))
        assertEquals(DeleteOutcome.DELETED, repo.delete(file.path))
        assertFalse(json.exists())
    }

    @Test fun unreadableMetadataCannotBeOverwrittenByFavoriteAndLegacyNeedsNoMigration() = runBlocking {
        val file = wav()
        val json = File(file.parentFile, "${file.name}.json")
        assertFalse(RecordingMetadataStore.loadOrCreate(file).isFavorite)
        assertFalse(json.exists())
        json.writeText("broken")
        assertFalse(RecordingRepository(context).setFavorite(file.path, true))
        assertEquals("broken", json.readText())
        assertTrue(file.exists())
    }

    @Test fun archiveKeepsBothSameNamedSourcesExactAndDeduplicatesSelection() {
        val external = wav()
        val internal = wav(true, external.name)
        val destination = Destination()
        assertEquals(ExportOutcome.SAVED, RecordingTransfer(context, destination)
            .exportArchive(listOf(external.path, internal.path, external.path), uri))
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(destination.bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(listOf("1/${external.name}", "2/${internal.name}"), entries.keys.toList())
        assertArrayEquals(external.readBytes(), entries.values.first())
        assertArrayEquals(internal.readBytes(), entries.values.last())
        assertFalse(destination.deleted)
    }

    @Test fun archiveMissingOrCorruptSourceCleansDestinationAndKeepsOtherRecordings() {
        val file = wav()
        val broken = wav().apply { writeBytes(byteArrayOf(1, 2)) }
        val destination = Destination()
        assertEquals(ExportOutcome.SOURCE_UNAVAILABLE, RecordingTransfer(context, destination)
            .exportArchive(listOf(file.path, broken.path), uri))
        assertTrue(destination.deleted)
        assertEquals(0, destination.bytes.size())
        assertTrue(file.exists())
        assertTrue(broken.exists())
    }

    @Test fun archiveFailureAndCancellationReportUncleanDestinationAndKeepSources() {
        val file = wav()
        val destination = Destination(failDelete = true)
        assertEquals(ExportOutcome.INCOMPLETE_DOCUMENT, RecordingTransfer(context, destination)
            .exportArchive(listOf(file.path), uri) { throw IOException("cancelled") })
        assertTrue(file.exists())
        val failing = Destination(failWrite = true)
        assertEquals(ExportOutcome.FAILED, RecordingTransfer(context, failing)
            .exportArchive(listOf(file.path), uri))
        assertTrue(failing.deleted)
        assertTrue(file.exists())
    }

    private class Destination(val failWrite: Boolean = false, val failDelete: Boolean = false) : RecordingDocumentWriter {
        val bytes = ByteArrayOutputStream()
        var deleted = false
        override fun open(uri: Uri): OutputStream = if (!failWrite) bytes else object : OutputStream() {
            override fun write(b: Int) { throw IOException("full") }
        }
        override fun delete(uri: Uri): Boolean { deleted = true; return !failDelete }
    }
}
