package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class OverlayPolicyTest {
    @Test fun focusedOnlyHidesIdleButtonWithoutEditor() {
        assertFalse(OverlayPolicy.visible("focused", false, false))
        assertTrue(OverlayPolicy.visible("focused", false, true))
    }

    @Test fun activeDictationIsAlwaysStoppable() {
        assertTrue(OverlayPolicy.visible("focused", true, false))
        assertTrue(OverlayPolicy.visible("always", false, false))
    }

    @Test fun passwordFieldsHideIdleButtonEvenInAlwaysMode() {
        assertFalse(OverlayPolicy.visible("always", false, false, true))
        assertFalse(OverlayPolicy.visible("focused", false, true, true))
        assertTrue(OverlayPolicy.visible("focused", true, false, true))
    }

    @Test fun dockingPreservesVisibleHandleAtBothSides() {
        assertEquals(-28, OverlayPolicy.recessedX(false, 1080, 56, 28))
        assertEquals(1052, OverlayPolicy.recessedX(true, 1080, 56, 28))
        assertEquals(8, OverlayPolicy.snappedX(false, 1080, 56, 8))
        assertEquals(1016, OverlayPolicy.snappedX(true, 1080, 56, 8))
    }
}
