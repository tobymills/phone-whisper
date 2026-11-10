package com.kafkasl.phonewhisper

/** Focus and actual editor capabilities; clickable or paste-only nodes do not qualify. */
internal object EditorFocusPolicy {
    fun isEditor(focused: Boolean, editable: Boolean, supportsSetText: Boolean, className: String): Boolean =
        focused && (editable || supportsSetText || className.endsWith("EditText"))
}
