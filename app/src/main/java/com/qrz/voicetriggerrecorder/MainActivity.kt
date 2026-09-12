package com.qrz.voicetriggerrecorder

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.qrz.voicetriggerrecorder.ui.MainScreen
import com.qrz.voicetriggerrecorder.ui.RecordingTransferViewModel
import com.qrz.voicetriggerrecorder.ui.theme.VoiceRecorderTheme

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val transfers = ViewModelProvider(this)[RecordingTransferViewModel::class.java]
        setContent {
            VoiceRecorderTheme {
                MainScreen(transfers)
            }
        }
    }
}
