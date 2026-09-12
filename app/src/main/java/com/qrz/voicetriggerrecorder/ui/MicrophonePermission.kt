package com.qrz.voicetriggerrecorder.ui

internal fun retainPermanentDenial(
    deniedAfterRequest: Boolean,
    granted: Boolean,
    shouldShowRationale: Boolean
): Boolean = deniedAfterRequest && !granted && !shouldShowRationale
