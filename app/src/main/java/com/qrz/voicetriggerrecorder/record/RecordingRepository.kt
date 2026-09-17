package com.qrz.voicetriggerrecorder.record

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecordingRepository(
    context: Context,
    private val storage: RecordingStorage = RecordingStorage(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val deleteFile: (File) -> Boolean = { it.delete() }
) {
    suspend fun scan(): List<RecordingFile> = withContext(ioDispatcher) { listRecordings() }

    suspend fun delete(identity: String): DeleteOutcome = withContext(ioDispatcher) { deleteRecording(identity) }

    suspend fun playbackSource(identity: String): File = withContext(ioDispatcher) { fileForTransfer(identity) }

    internal fun listRecordings(): List<RecordingFile> {
        return storage.roots().flatMap {
            if (!it.exists()) emptyList() else it.listFiles()?.toList()
                ?: throw IOException("Cannot scan recording directory")
        }
            .filter { it.isFile && runCatching { storage.resolve(it.absolutePath) }.isSuccess }
            .map { file ->
                val metadata = RecordingMetadataStore.loadOrCreate(file)
                RecordingFile(
                    name = file.name,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    durationMs = metadata.durationMs,
                    id = metadata.id,
                    sessionId = metadata.sessionId,
                    fileName = metadata.fileName,
                    createdAt = metadata.createdAt,
                    endedAt = metadata.endedAt,
                    sampleRate = metadata.sampleRate,
                    speechDurationMs = metadata.speechDurationMs,
                    closeReason = metadata.closeReason,
                    vadEngineName = metadata.vadEngineName,
                    vadConfidence = metadata.vadConfidence,
                    isCorrupted = metadata.isCorrupted,
                    isFinalized = metadata.isFinalized,
                    isExported = metadata.isExported
                )
            }
            .sortedByDescending { it.lastModified }

    }

    internal fun deleteRecording(identity: String): DeleteOutcome {
        val file = runCatching { storage.resolve(identity) }.getOrNull()
            ?: return DeleteOutcome.SOURCE_UNAVAILABLE
        return RecordingMetadataStore.withRecording(file) {
            val existed = file.exists()
            if (existed && (!file.isFile || !runCatching { deleteFile(file) }.getOrDefault(false))) {
                return@withRecording DeleteOutcome.AUDIO_FAILED
            }
            if (!RecordingMetadataStore.deleteFor(file, deleteFile)) DeleteOutcome.METADATA_REMAINS
            else if (existed) DeleteOutcome.DELETED else DeleteOutcome.ALREADY_ABSENT
        }
    }

    internal fun fileForTransfer(identity: String): File {
        val file = storage.resolve(identity)
        require(file.isFile && file.canRead())
        require(RecordingMetadataStore.isReadyForTransfer(file))
        return file
    }
}
