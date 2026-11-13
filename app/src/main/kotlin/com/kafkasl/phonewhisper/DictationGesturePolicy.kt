package com.kafkasl.phonewhisper

/** Distinguishes tap, hold and drag without depending on the STT transport. */
internal object DictationGesturePolicy {
    fun isHold(elapsedMs: Long, thresholdMs: Long): Boolean = elapsedMs >= thresholdMs
    fun shouldDrag(distancePx: Float, thresholdPx: Float, holdRecordingStarted: Boolean): Boolean =
        !holdRecordingStarted && distancePx > thresholdPx
}
