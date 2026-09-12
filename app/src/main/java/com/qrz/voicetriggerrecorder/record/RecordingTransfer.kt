package com.qrz.voicetriggerrecorder.record

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal interface RecordingDocumentWriter {
    fun open(uri: Uri): OutputStream?
    fun delete(uri: Uri): Boolean
}

internal class AndroidRecordingDocumentWriter(private val context: Context) : RecordingDocumentWriter {
    override fun open(uri: Uri) = context.contentResolver.openOutputStream(uri, "wt")
    override fun delete(uri: Uri) = DocumentsContract.deleteDocument(context.contentResolver, uri)
}

internal enum class ExportOutcome { SAVED, SOURCE_UNAVAILABLE, FAILED, INCOMPLETE_DOCUMENT }

/** Blocking I/O: callers must dispatch off the UI thread. Sources and sidecars are never written. */
internal class RecordingTransfer(
    private val context: Context,
    private val documents: RecordingDocumentWriter = AndroidRecordingDocumentWriter(context)
) {
    private val repository = RecordingRepository(context)
    private val shareDirectory get() = File(context.cacheDir, "recording-shares")

    fun prepareShare(fileName: String, checkActive: () -> Unit = {}): Intent {
        val snapshot = createShareSnapshot(fileName, checkActive)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.recording-shares", snapshot)
            return Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, snapshot.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (error: Exception) {
            snapshot.parentFile?.deleteRecursively()
            throw error
        }
    }

    internal fun createShareSnapshot(fileName: String, checkActive: () -> Unit = {}): File {
        val source = repository.fileForTransfer(fileName)
        pruneExpiredShares()
        val directory = File(shareDirectory, UUID.randomUUID().toString())
        check(directory.mkdirs()) { "Cannot create share cache" }
        val snapshot = File(directory, source.name)
        try {
            val expectedSize = source.length()
            source.inputStream().use { input ->
                snapshot.outputStream().use { output ->
                    copy(input, output, expectedSize, checkActive)
                }
            }
            check(RecordingMetadataStore.isReadyForTransfer(snapshot))
            return snapshot
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun export(fileName: String, uri: Uri, checkActive: () -> Unit = {}): ExportOutcome {
        // A picker result must never alias our private files or shared snapshots.
        if (uri.scheme != "content" || uri.authority == "${context.packageName}.recording-shares") {
            return ExportOutcome.FAILED
        }
        var outcome = ExportOutcome.SOURCE_UNAVAILABLE
        try {
            val source = repository.fileForTransfer(fileName)
            val expectedSize = source.length()
            source.inputStream().use { input ->
                outcome = ExportOutcome.FAILED
                (documents.open(uri) ?: throw IOException("Cannot open destination")).use { output ->
                    copy(input, output, expectedSize, checkActive)
                    output.flush()
                }
            }
            return ExportOutcome.SAVED
        } catch (_: Exception) {
            // ACTION_CREATE_DOCUMENT created this destination, including when the source vanished.
            return if (runCatching { documents.delete(uri) }.getOrDefault(false)) outcome
            else ExportOutcome.INCOMPLETE_DOCUMENT
        }
    }

    fun pruneExpiredShares(now: Long = System.currentTimeMillis()) {
        val root = shareDirectory.canonicalFile
        root.listFiles()?.forEach { directory ->
            if (directory.isDirectory && directory.canonicalFile.parentFile == root &&
                runCatching { UUID.fromString(directory.name) }.isSuccess &&
                now - directory.lastModified() > SHARE_RETENTION_MS
            ) directory.deleteRecursively()
        }
    }

    private fun copy(input: InputStream, output: OutputStream, expected: Long, checkActive: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        if (total != expected) throw IOException("Recording changed while copying")
    }

    companion object {
        const val MIME_TYPE = "audio/wav"
        const val SHARE_RETENTION_MS = 24 * 60 * 60 * 1000L
    }
}
