package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class DictationGesturePolicyTest {
    @Test fun shortTouchIsTapAndLongTouchIsHold() {
        assertFalse(DictationGesturePolicy.isHold(349, 350))
        assertTrue(DictationGesturePolicy.isHold(350, 350))
        assertTrue(DictationGesturePolicy.isHold(1200, 350))
    }

    @Test fun dragRequiresMovementBeforeHoldRecordingStarts() {
        assertFalse(DictationGesturePolicy.shouldDrag(10f, 10f, false))
        assertTrue(DictationGesturePolicy.shouldDrag(11f, 10f, false))
        assertFalse(DictationGesturePolicy.shouldDrag(50f, 10f, true))
    }
}
