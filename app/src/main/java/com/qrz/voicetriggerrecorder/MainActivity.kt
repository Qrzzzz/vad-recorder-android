package com.qrz.voicetriggerrecorder

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import com.qrz.voicetriggerrecorder.record.RecordingRepository
import com.qrz.voicetriggerrecorder.ui.RecordingHistoryViewModel
import com.qrz.voicetriggerrecorder.ui.MainScreen
import com.qrz.voicetriggerrecorder.ui.RecordingTransferViewModel
import com.qrz.voicetriggerrecorder.ui.theme.VoiceRecorderTheme

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val transfers = ViewModelProvider(this)[RecordingTransferViewModel::class.java]
        val history = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = RecordingRepository(applicationContext)
                return RecordingHistoryViewModel(repository::scan, repository::delete,
                    { repository.recoveryResults }, repository::clearRemnants) as T
            }
        })[RecordingHistoryViewModel::class.java]
        setContent {
            VoiceRecorderTheme {
                MainScreen(transfers, history)
            }
        }
    }
}
