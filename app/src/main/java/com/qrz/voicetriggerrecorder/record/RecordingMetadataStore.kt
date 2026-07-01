package com.qrz.voicetriggerrecorder.record

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

object RecordingMetadataStore {
    private const val DEFAULT_SAMPLE_RATE = 16000
    private const val DEFAULT_CHANNELS = 1
    private const val DEFAULT_BITS_PER_SAMPLE = 16

    fun loadOrCreate(wavFile: File): RecordingMetadata {
        val inferred = inferFromWav(wavFile)
        val stored = read(metadataFileFor(wavFile))
        val merged = stored?.mergeWith(inferred) ?: inferred
        if (stored != merged) {
            write(metadataFileFor(wavFile), merged)
        }
        return merged
    }

    fun deleteFor(wavFile: File): Boolean {
        val metadataFile = metadataFileFor(wavFile)
        return !metadataFile.exists() || metadataFile.delete()
    }

    fun writeFinalized(
        wavFile: File,
        createdAt: Long,
        endedAt: Long,
        sampleRate: Int,
        speechDurationMs: Long,
        closeReason: RecordingCloseReason,
        vadEngineName: String
    ) {
        val inferred = inferFromWav(wavFile)
        val metadata = RecordingMetadata(
            id = wavFile.name.substringBeforeLast('.'),
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
            isCorrupted = inferred.isCorrupted,
            isFinalized = inferred.isFinalized
        )
        write(metadataFileFor(wavFile), metadata)
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

    private fun write(file: File, metadata: RecordingMetadata) {
        try {
            file.writeText(metadata.toJson().toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
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
            isCorrupted = wavInfo.isCorrupted,
            isFinalized = wavInfo.isFinalized
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
            fileName = inferred.fileName,
            path = inferred.path,
            createdAt = createdAt.takeIf { it > 0L } ?: inferred.createdAt,
            sizeBytes = inferred.sizeBytes,
            sampleRate = sampleRate ?: inferred.sampleRate,
            durationMs = inferred.durationMs ?: durationMs,
            endedAt = inferred.endedAt,
            isCorrupted = inferred.isCorrupted,
            isFinalized = inferred.isFinalized
        )
    }

    private fun JSONObject.toRecordingMetadata(): RecordingMetadata {
        return RecordingMetadata(
            id = optNullableString("id") ?: optNullableString("fileName")?.substringBeforeLast('.') ?: "",
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
            isCorrupted = optBoolean("isCorrupted", false),
            isFinalized = optBoolean("isFinalized", false)
        )
    }

    private fun RecordingMetadata.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
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
            .put("isCorrupted", isCorrupted)
            .put("isFinalized", isFinalized)
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
                raf.readIntLe()
                val wave = raf.readAscii(4)
                if (riff != "RIFF" || wave != "WAVE") {
                    return corrupted()
                }

                var sampleRate: Int? = null
                var channels: Int? = null
                var bitsPerSample: Int? = null
                var byteRate: Int? = null
                var dataBytes: Long? = null
                var audioFormat: Int? = null

                while (raf.filePointer + 8L <= raf.length()) {
                    val chunkId = raf.readAscii(4)
                    val chunkSize = raf.readUnsignedIntLe()
                    val chunkStart = raf.filePointer
                    val chunkEnd = (chunkStart + chunkSize).coerceAtMost(raf.length())

                    when (chunkId) {
                        "fmt " -> {
                            if (chunkSize >= 16L && chunkStart + 16L <= raf.length()) {
                                audioFormat = raf.readUnsignedShortLe()
                                channels = raf.readUnsignedShortLe()
                                sampleRate = raf.readIntLe()
                                byteRate = raf.readIntLe()
                                raf.readUnsignedShortLe()
                                bitsPerSample = raf.readUnsignedShortLe()
                            }
                        }

                        "data" -> {
                            dataBytes = chunkSize.coerceAtMost((raf.length() - raf.filePointer).coerceAtLeast(0L))
                        }
                    }

                    raf.seek(chunkEnd + (chunkSize and 1L))
                }

                val isPcm = audioFormat == 1
                val hasRequiredChunks = sampleRate != null && channels != null &&
                    bitsPerSample != null && dataBytes != null
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
