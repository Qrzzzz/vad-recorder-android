package com.qrz.voicetriggerrecorder.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePermissionTest {
    @Test fun firstLaunchStillRequestsPermission() {
        assertFalse(retainPermanentDenial(false, false, false))
    }

    @Test fun ordinaryDenialAllowsAnotherRequest() {
        assertFalse(retainPermanentDenial(false, false, true))
        assertFalse(retainPermanentDenial(true, false, true))
    }

    @Test fun completedPermanentDenialSurvivesResume() {
        assertTrue(retainPermanentDenial(true, false, false))
    }

    @Test fun grantInSettingsClearsDenial() {
        assertFalse(retainPermanentDenial(true, true, false))
    }
}
