package com.etmw.foldpc.keyboard

enum class KeyType {
    CHARACTER,
    BACKSPACE,
    FORWARD_DELETE,
    ENTER,
    TAB,
    ESCAPE,
    SPACE,
    ARROW_LEFT,
    ARROW_RIGHT,
    ARROW_UP,
    ARROW_DOWN,
    FUNCTION,
    SHIFT,
    CAPS_LOCK,
    OPTION,
    CONTROL,
    COMMAND,
    FN
}

enum class ArrowDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN
}

data class Modifiers(
    val shift: Boolean = false,
    val option: Boolean = false,
    val control: Boolean = false,
    val command: Boolean = false
)

data class KeySpec(
    val id: String,
    val type: KeyType,
    val units: Float,
    val label: String,
    val text: String? = null,
    val shifted: String? = null,
    val shiftLabel: String? = null,
    val secondary: String? = null,
    val functionNumber: Int = 0,
    val compact: Boolean = false
)
