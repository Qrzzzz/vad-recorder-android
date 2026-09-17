package com.qrz.voicetriggerrecorder.ui

import com.qrz.voicetriggerrecorder.record.RecordingFile
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class NightGroupingTest {
    @Test fun midnightAndMorningBelongToPreviousNightButNoonStartsNextNight() {
        val day = LocalDate.of(2026, 9, 18)
        fun clip(hour: Int, minute: Int = 0): RecordingFile {
            val time = day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            return RecordingFile("$hour.wav", "/$hour.wav", 50, time, 100)
        }
        val groups = buildNightGroups(listOf(clip(0), clip(11, 59), clip(12), clip(23)))
        assertEquals(listOf(day, day.minusDays(1)), groups.map { it.nightDate })
        assertEquals(listOf("/23.wav", "/12.wav"), groups[0].recordings.map { it.path })
        assertEquals(listOf("/11.wav", "/0.wav"), groups[1].recordings.map { it.path })
        assertEquals(200L, groups[0].totalDurationMs)
    }
}
