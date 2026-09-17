package com.qrz.voicetriggerrecorder.record

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Properties

/** Formal WAVs, recoverable partials, and unknown remnants are separate states. */
enum class RecoveryOutcome { RECOVERED, PENDING, UNKNOWN, EMPTY, CONFLICT }
data class RecoveryResult(val path: String, val outcome: RecoveryOutcome)

object RecordingRecovery {
    // Coordinates writers, scans and user deletion in this process. No age-based deletion.
    internal val lock = Any()
    internal val active = mutableSetOf<String>()
    internal fun manifest(file: File) = File(file.parentFile, "${file.name}.recovery")
    internal fun partial(file: File) = File(file.parentFile, "${file.name}.part")

    internal fun prepare(file: File, rate: Int, channels: Int, bits: Int) {
        val values = Properties().apply {
            setProperty("version", "1")
            setProperty("source", file.name)
            setProperty("sampleRate", rate.toString())
            setProperty("channels", channels.toString())
            setProperty("bitsPerSample", bits.toString())
            setProperty("createdAt", System.currentTimeMillis().toString())
        }
        FileOutputStream(manifest(file)).use { values.store(it, null); it.fd.sync() }
    }

    fun recover(directory: File): List<RecoveryResult> = synchronized(lock) {
        val root = directory.canonicalFile
        val entries = root.listFiles() ?: return@synchronized emptyList()
        val names = entries.mapNotNull {
            when {
                it.name.endsWith(".wav.part") -> it.name.removeSuffix(".part")
                it.name.endsWith(".wav.recovery") -> it.name.removeSuffix(".recovery")
                else -> null
            }
        }.distinct()
        names.mapNotNull { name ->
            val target = File(root, name)
            val part = partial(target)
            val info = manifest(target)
            // Do not follow symlinks out of the trusted root or touch a live writer.
            if (listOf(target, part, info).any { it.canonicalFile.parentFile != root } ||
                target.canonicalPath in active) return@mapNotNull null
            RecordingMetadataStore.withRecording(target) {
                val result = runCatching {
                    val values = Properties().apply { info.inputStream().use { load(it) } }
                    require(values.getProperty("version") == "1" && values.getProperty("source") == name)
                    val rate = values.getProperty("sampleRate").toInt()
                    val channels = values.getProperty("channels").toInt()
                    val bits = values.getProperty("bitsPerSample").toInt()
                    val created = values.getProperty("createdAt").toLong()
                    require(rate in 1..384000 && channels in 1..2 && bits == 16 && created > 0)
                    Triple(rate, channels, created)
                }.getOrNull()
                val outcome = when {
                    result == null -> RecoveryOutcome.UNKNOWN
                    part.exists() && target.exists() -> RecoveryOutcome.CONFLICT
                    else -> try {
                        if (part.exists()) {
                            val endedAt = part.lastModified()
                            val align = result.second * 2
                            val bytes = ((part.length() - 44).coerceAtLeast(0) / align) * align
                            if (bytes == 0L) return@withRecording RecoveryResult(part.absolutePath, RecoveryOutcome.EMPTY)
                            require(bytes <= 0xffffffffL - 36)
                            RandomAccessFile(part, "rw").use {
                                it.setLength(44 + bytes)
                                WavFileWriter.writeHeader(it, result.first, result.second, 16, bytes)
                                it.fd.sync()
                            }
                            Files.move(part.toPath(), target.toPath()) // Never replace or invent a second name.
                            target.setLastModified(endedAt)
                        }
                        if (!target.isFile || !RecordingMetadataStore.isReadyForTransfer(target)) {
                            RecoveryOutcome.PENDING
                        } else {
                            val existing = RecordingMetadataStore.loadOrCreate(target)
                            val saved = existing.closeReason != null || RecordingMetadataStore.writeFinalized(
                                target, result.third, target.lastModified(), result.first, null,
                                RecordingCloseReason.Recovered, "Unknown")
                            if (saved && info.delete()) RecoveryOutcome.RECOVERED else RecoveryOutcome.PENDING
                        }
                    } catch (_: Exception) { RecoveryOutcome.PENDING }
                }
                RecoveryResult(part.absolutePath, outcome)
            }
        }
    }

    /** Remove the recovery source BEFORE audio deletion. A failed cleanup leaves audio intact. */
    internal fun discard(file: File, delete: (File) -> Boolean = { it.delete() }): Boolean = synchronized(lock) {
        if (file.canonicalPath in active) return@synchronized false
        val part = partial(file)
        if (part.exists() && !delete(part)) return@synchronized false
        val info = manifest(file)
        !info.exists() || delete(info)
    }
}
