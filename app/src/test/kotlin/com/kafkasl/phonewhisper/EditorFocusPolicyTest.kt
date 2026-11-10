package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class EditorFocusPolicyTest {
    @Test fun recognisesCustomEditors() {
        assertTrue(EditorFocusPolicy.isEditor(true, false, true, "CustomComposer"))
        assertTrue(EditorFocusPolicy.isEditor(true, true, false, "ComposeNode"))
        assertTrue(EditorFocusPolicy.isEditor(true, false, false, "android.widget.EditText"))
    }
    @Test fun excludesClickableAndUnfocusedNodes() {
        assertFalse(EditorFocusPolicy.isEditor(true, false, false, "android.widget.Button"))
        assertFalse(EditorFocusPolicy.isEditor(true, false, false, "ClickableView"))
        assertFalse(EditorFocusPolicy.isEditor(false, true, true, "CustomComposer"))
    }
}
