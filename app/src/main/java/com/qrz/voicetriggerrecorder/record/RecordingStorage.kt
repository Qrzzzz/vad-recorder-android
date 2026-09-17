package com.qrz.voicetriggerrecorder.record

import android.content.Context
import android.os.Environment
import java.io.File

/** Source paths are identities, always checked against app-owned roots. Never relocate old audio. */
class RecordingStorage(
    private val internalRoot: File,
    private val externalRoots: () -> List<File>
) {
    constructor(context: Context) : this(
        File(context.filesDir, "music/voice-recordings"),
        { context.getExternalFilesDirs(Environment.DIRECTORY_MUSIC).filterNotNull()
            .map { File(it, "voice-recordings") } }
    )

    fun roots(): List<File> = (listOf(internalRoot) + externalRoots()).map { it.canonicalFile }.distinct()
    fun writeDirectory(): File = externalRoots().firstOrNull() ?: internalRoot

    fun resolve(identity: String): File {
        require(identity.isNotBlank())
        val requested = File(identity)
        val roots = roots()
        val file = if (requested.isAbsolute) requested.canonicalFile else {
            require('/' !in identity && '\\' !in identity)
            // Compatibility for callers holding an old name: ambiguity fails closed.
            val matches = roots.map { File(it, identity) }.filter { it.exists() }
            require(matches.size == 1) { "Recording source missing or ambiguous" }
            matches.single().canonicalFile
        }
        require(file.parentFile?.let { it in roots } == true && file.name.endsWith(".wav", ignoreCase = true))
        return file
    }
}

enum class DeleteOutcome { DELETED, ALREADY_ABSENT, AUDIO_FAILED, METADATA_REMAINS, SOURCE_UNAVAILABLE, PROTECTED }
