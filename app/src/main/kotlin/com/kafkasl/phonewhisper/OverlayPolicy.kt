package com.kafkasl.phonewhisper

/** Pure overlay visibility and docking policy; no Android lifecycle dependencies. */
internal object OverlayPolicy {
    fun visible(
        selection: String, activeDictation: Boolean, editableFocused: Boolean,
        passwordFocused: Boolean = false
    ): Boolean = activeDictation || (!passwordFocused && (selection == "always" || editableFocused))

    /** Keep a 28dp handle visible at either edge when the button is idle. */
    fun recessedX(rightSide: Boolean, screenWidth: Int, ringSize: Int, handleSize: Int): Int =
        if (rightSide) screenWidth - handleSize else -(ringSize - handleSize)

    fun snappedX(rightSide: Boolean, screenWidth: Int, ringSize: Int, margin: Int): Int =
        if (rightSide) screenWidth - ringSize - margin else margin
}
