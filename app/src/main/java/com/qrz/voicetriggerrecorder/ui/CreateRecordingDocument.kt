package com.qrz.voicetriggerrecorder.ui

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

class CreateRecordingDocument : ActivityResultContracts.CreateDocument("audio/wav") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}
