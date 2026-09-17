package com.qrz.voicetriggerrecorder.record

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption


object RecordingMetadataStore {
    private const val DEFAULT_SAMPLE_RATE = 16000
    private const val DEFAULT_CHANNELS = 1
    private const val DEFAULT_BITS_PER_SAMPLE = 16

    private val locks = Array(64) { Any() }
    internal fun <T> withRecording(file: File, action: () -> T): T =
        synchronized(locks[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % locks.size], action)

    // A read-only, stricter check at the transfer boundary. Never trusts sidecar flags.
    fun isReadyForTransfer(wavFile: File): Boolean =
        WavHeaderReader.read(wavFile).isFinalized

    // Compatibility inference is read-only: browsing never rewrites historical sidecars.
    fun loadOrCreate(wavFile: File): RecordingMetadata = withRecording(wavFile) {
        val inferred = inferFromWav(wavFile)
        read(metadataFileFor(wavFile))?.mergeWith(inferred) ?: inferred
    }

    fun deleteFor(wavFile: File, delete: (File) -> Boolean = { it.delete() }): Boolean =
        withRecording(wavFile) {
            val metadataFile = metadataFileFor(wavFile)
            !metadataFile.exists() || runCatching { delete(metadataFile) }.getOrDefault(false)
        }

    fun writeFinalized(
        wavFile: File,
        createdAt: Long,
        endedAt: Long,
        sampleRate: Int,
        speechDurationMs: Long,
        closeReason: RecordingCloseReason,
        vadEngineName: String,
        beforeCommit: (File) -> Unit = {}
    ): Boolean = withRecording(wavFile) {
        if (!wavFile.isFile) return@withRecording false
        val inferred = inferFromWav(wavFile)
        val metadata = RecordingMetadata(
            id = wavFile.name.substringBeforeLast('.'),
            sessionId = wavFile.name.substringBeforeLast('.'),
            fileName = wavFile.name,
            path = wavFile.absolutePath,
            createdAt = createdAt,
            endedAt = endedAt,
            durationMs = inferred.durationMs,
            sizeBytes = wavFile.length(),
            sampleRate = inferred.sampleRate ?: sampleRate,
            speechDurationMs = speechDurationMs,
            closeReason = closeReason.name,
            vadEngineName = vadEngineName,
            vadConfidence = null,
            isCorrupted = inferred.isCorrupted,
            isFinalized = inferred.isFinalized,
            isExported = false
        )
        write(metadataFileFor(wavFile), metadata, beforeCommit)
    }

    private fun metadataFileFor(wavFile: File): File {
        return File(wavFile.parentFile, "${wavFile.name}.json")
    }

    private fun read(file: File): RecordingMetadata? {
        if (!file.exists() || !file.isFile) return null
        return try {
            JSONObject(file.readText(Charsets.UTF_8)).toRecordingMetadata()
        } catch (_: Exception) {
            null
        }
    }

    private fun write(file: File, metadata: RecordingMetadata, beforeCommit: (File) -> Unit): Boolean {
        var temporary: File? = null
        return try {
            val bytes = metadata.toJson().toString(2).toByteArray(Charsets.UTF_8)
            temporary = File.createTempFile(".${file.name}.", ".tmp", file.parentFile)
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            beforeCommit(temporary)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            false
        } finally {
            temporary?.delete()
        }
    }

    private fun inferFromWav(wavFile: File): RecordingMetadata {
        val wavInfo = WavHeaderReader.read(wavFile)
        val sampleRate = wavInfo.sampleRate ?: DEFAULT_SAMPLE_RATE
        val durationMs = wavInfo.durationMs ?: estimateDuration(
            fileSize = wavFile.length(),
            sampleRate = sampleRate,
            channels = wavInfo.channels ?: DEFAULT_CHANNELS,
            bitsPerSample = wavInfo.bitsPerSample ?: DEFAULT_BITS_PER_SAMPLE
        )
        val endedAt = wavFile.lastModified()
        val createdAt = if (durationMs != null) {
            (endedAt - durationMs).coerceAtLeast(0L)
        } else {
            endedAt
        }

        return RecordingMetadata(
            id = wavFile.name.substringBeforeLast('.'),
            sessionId = wavFile.name.substringBeforeLast('.'),
            fileName = wavFile.name,
            path = wavFile.absolutePath,
            createdAt = createdAt,
            endedAt = endedAt,
            durationMs = durationMs,
            sizeBytes = wavFile.length(),
            sampleRate = sampleRate,
            speechDurationMs = null,
            closeReason = null,
            vadEngineName = null,
            vadConfidence = null,
            isCorrupted = wavInfo.isCorrupted,
            isFinalized = wavInfo.isFinalized,
            isExported = false
        )
    }

    private fun estimateDuration(
        fileSize: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): Long? {
        if (fileSize <= 44L || sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
        val bytesPerSecond = sampleRate.toLong() * channels * bitsPerSample / 8L
        if (bytesPerSecond <= 0L) return null
        return (fileSize - 44L) * 1000L / bytesPerSecond
    }

    private fun RecordingMetadata.mergeWith(inferred: RecordingMetadata): RecordingMetadata {
        return copy(
            id = id.ifBlank { inferred.id },
            sessionId = sessionId.ifBlank { inferred.sessionId },
            fileName = inferred.fileName,
            path = inferred.path,
            createdAt = createdAt.takeIf { it > 0L } ?: inferred.createdAt,
            sizeBytes = inferred.sizeBytes,
            sampleRate = sampleRate ?: inferred.sampleRate,
            durationMs = inferred.durationMs ?: durationMs,
            endedAt = endedAt?.takeIf { it > 0L } ?: inferred.endedAt,
            isCorrupted = inferred.isCorrupted,
            isFinalized = inferred.isFinalized
        )
    }

    private fun JSONObject.toRecordingMetadata(): RecordingMetadata {
        return RecordingMetadata(
            id = optNullableString("id") ?: optNullableString("fileName")?.substringBeforeLast('.') ?: "",
            sessionId = optNullableString("sessionId")
                ?: optNullableString("id")
                ?: optNullableString("fileName")?.substringBeforeLast('.')
                ?: "",
            fileName = optNullableString("fileName") ?: "",
            path = optNullableString("path") ?: "",
            createdAt = optLong("createdAt", 0L),
            endedAt = optNullableLong("endedAt"),
            durationMs = optNullableLong("durationMs"),
            sizeBytes = optLong("sizeBytes", 0L),
            sampleRate = optNullableInt("sampleRate"),
            speechDurationMs = optNullableLong("speechDurationMs"),
            closeReason = optNullableString("closeReason"),
            vadEngineName = optNullableString("vadEngineName"),
            vadConfidence = optNullableFloat("vadConfidence"),
            isCorrupted = optBoolean("isCorrupted", false),
            isFinalized = optBoolean("isFinalized", false),
            isExported = optBoolean("isExported", false)
        )
    }

    private fun RecordingMetadata.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("sessionId", sessionId)
            .put("fileName", fileName)
            .put("path", path)
            .put("createdAt", createdAt)
            .putNullable("endedAt", endedAt)
            .putNullable("durationMs", durationMs)
            .put("sizeBytes", sizeBytes)
            .putNullable("sampleRate", sampleRate)
            .putNullable("speechDurationMs", speechDurationMs)
            .putNullable("closeReason", closeReason)
            .putNullable("vadEngineName", vadEngineName)
            .putNullable("vadConfidence", vadConfidence)
            .put("isCorrupted", isCorrupted)
            .put("isFinalized", isFinalized)
            .put("isExported", isExported)
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject {
        put(name, value ?: JSONObject.NULL)
        return this
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name) else null
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        return if (has(name) && !isNull(name)) optLong(name) else null
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun JSONObject.optNullableFloat(name: String): Float? {
        return if (has(name) && !isNull(name)) optDouble(name).toFloat() else null
    }
}

private data class WavInfo(
    val sampleRate: Int?,
    val channels: Int?,
    val bitsPerSample: Int?,
    val dataBytes: Long?,
    val byteRate: Int?,
    val isCorrupted: Boolean,
    val isFinalized: Boolean
) {
    val durationMs: Long?
        get() {
            val bytesPerSecond = byteRate?.takeIf { it > 0 }
                ?: run {
                    val rate = sampleRate ?: return null
                    val channelCount = channels ?: return null
                    val bits = bitsPerSample ?: return null
                    rate * channelCount * bits / 8
                }
            val dataLength = dataBytes ?: return null
            return if (bytesPerSecond > 0) dataLength * 1000L / bytesPerSecond else null
        }
}

private object WavHeaderReader {
    fun read(file: File): WavInfo {
        if (!file.exists() || file.length() < 44L) {
            return corrupted()
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val riff = raf.readAscii(4)
                val riffSize = raf.readUnsignedIntLe()
                val wave = raf.readAscii(4)
                if (riff != "RIFF" || wave != "WAVE") {
                    return corrupted()
                }
                if (riffSize + 8L != raf.length()) return corrupted()

                var sampleRate: Int? = null
                var channels: Int? = null
                var bitsPerSample: Int? = null
                var byteRate: Int? = null
                var dataBytes: Long? = null
                var audioFormat: Int? = null
                var blockAlign: Int? = null

                while (raf.filePointer + 8L <= raf.length()) {
                    val chunkId = raf.readAscii(4)
                    val chunkSize = raf.readUnsignedIntLe()
                    val chunkStart = raf.filePointer
                    if (chunkStart + chunkSize > raf.length()) return corrupted()
                    val chunkEnd = (chunkStart + chunkSize).coerceAtMost(raf.length())

                    when (chunkId) {
                        "fmt " -> {
                            if (chunkSize >= 16L && chunkStart + 16L <= raf.length()) {
                                audioFormat = raf.readUnsignedShortLe()
                                channels = raf.readUnsignedShortLe()
                                sampleRate = raf.readIntLe()
                                byteRate = raf.readIntLe()
                                blockAlign = raf.readUnsignedShortLe()
                                bitsPerSample = raf.readUnsignedShortLe()
                            }
                        }

                        "data" -> {
                            dataBytes = chunkSize.coerceAtMost((raf.length() - raf.filePointer).coerceAtLeast(0L))
                        }
                    }

                    raf.seek(chunkEnd + (chunkSize and 1L))
                }

                if (raf.filePointer != raf.length()) return corrupted()
                val expectedAlign = (channels ?: 0).toLong() * (bitsPerSample ?: 0) / 8
                if (expectedAlign <= 0 || blockAlign?.toLong() != expectedAlign ||
                    (bitsPerSample ?: 0) % 8 != 0 ||
                    byteRate?.toLong() != (sampleRate ?: 0).toLong() * expectedAlign ||
                    (dataBytes ?: 0) % expectedAlign != 0L) return corrupted()
                val isPcm = audioFormat == 1
                val hasRequiredChunks = sampleRate != null && channels != null &&
                    bitsPerSample != null && dataBytes != null
                if ((
                    (sampleRate ?: 0) <= 0 || (channels ?: 0) <= 0 ||
                    (bitsPerSample ?: 0) <= 0 || (byteRate ?: 0) <= 0 || (dataBytes ?: 0) <= 0
                )) return corrupted()
                val isFinalized = isPcm && hasRequiredChunks
                WavInfo(
                    sampleRate = sampleRate,
                    channels = channels,
                    bitsPerSample = bitsPerSample,
                    dataBytes = dataBytes,
                    byteRate = byteRate,
                    isCorrupted = !isFinalized,
                    isFinalized = isFinalized
                )
            }
        } catch (_: Exception) {
            corrupted()
        }
    }

    private fun corrupted(): WavInfo {
        return WavInfo(
            sampleRate = null,
            channels = null,
            bitsPerSample = null,
            dataBytes = null,
            byteRate = null,
            isCorrupted = true,
            isFinalized = false
        )
    }

    private fun RandomAccessFile.readAscii(length: Int): String {
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readUnsignedIntLe(): Long {
        return readIntLe().toLong() and 0xffffffffL
    }

    private fun RandomAccessFile.readIntLe(): Int {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            throw IllegalStateException("Unexpected end of WAV header")
        }
        return (b0 and 0xff) or
            ((b1 and 0xff) shl 8) or
            ((b2 and 0xff) shl 16) or
            ((b3 and 0xff) shl 24)
    }

    private fun RandomAccessFile.readUnsignedShortLe(): Int {
        val b0 = read()
        val b1 = read()
        if (b0 < 0 || b1 < 0) {
            throw IllegalStateException("Unexpected end of WAV header")
        }
        return (b0 and 0xff) or ((b1 and 0xff) shl 8)
    }
}
