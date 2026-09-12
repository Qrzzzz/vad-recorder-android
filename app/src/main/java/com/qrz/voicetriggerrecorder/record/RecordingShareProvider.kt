package com.qrz.voicetriggerrecorder.record

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.qrz.voicetriggerrecorder.R

/** Even an accidentally over-granted URI cannot modify or delete a shared snapshot. */
class RecordingShareProvider : FileProvider(R.xml.recording_share_paths) {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Recordings are shared read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw SecurityException("Recordings are shared read-only")
    }
}
