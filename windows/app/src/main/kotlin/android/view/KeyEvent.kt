package android.view

/**
 * DESKTOP STAND-IN, not Android: only the key code numbers (Android's own values, android.view.KeyEvent) that the
 * shared phone files (PadLayout, Controls, GameMenu) name, so they compile unchanged. No key events exist here.
 */
object KeyEvent {
    const val KEYCODE_BACK = 4
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25
    const val KEYCODE_ENTER = 66
    const val KEYCODE_BUTTON_A = 96
    const val KEYCODE_BUTTON_B = 97
    const val KEYCODE_BUTTON_C = 98
    const val KEYCODE_BUTTON_X = 99
    const val KEYCODE_BUTTON_Y = 100
    const val KEYCODE_BUTTON_Z = 101
    const val KEYCODE_BUTTON_L1 = 102
    const val KEYCODE_BUTTON_R1 = 103
    const val KEYCODE_BUTTON_L2 = 104
    const val KEYCODE_BUTTON_R2 = 105
    const val KEYCODE_BUTTON_THUMBL = 106
    const val KEYCODE_BUTTON_THUMBR = 107
    const val KEYCODE_BUTTON_START = 108
    const val KEYCODE_BUTTON_SELECT = 109
    const val KEYCODE_BUTTON_MODE = 110
    const val KEYCODE_ESCAPE = 111
    const val KEYCODE_NUMPAD_ENTER = 160
    const val KEYCODE_BUTTON_1 = 188
    const val KEYCODE_BUTTON_2 = 189
    const val KEYCODE_BUTTON_3 = 190
    const val KEYCODE_BUTTON_4 = 191
    const val KEYCODE_BUTTON_5 = 192
    const val KEYCODE_BUTTON_6 = 193
    const val KEYCODE_BUTTON_7 = 194
    const val KEYCODE_BUTTON_8 = 195
    const val KEYCODE_BUTTON_9 = 196
    const val KEYCODE_BUTTON_10 = 197
    const val KEYCODE_BUTTON_11 = 198
    const val KEYCODE_BUTTON_12 = 199
    const val KEYCODE_BUTTON_13 = 200
    const val KEYCODE_BUTTON_14 = 201
    const val KEYCODE_BUTTON_15 = 202
    const val KEYCODE_BUTTON_16 = 203

    /** Android's rule: BUTTON_A … BUTTON_MODE and BUTTON_1 … BUTTON_16. */
    @JvmStatic
    fun isGamepadButton(keyCode: Int): Boolean =
        keyCode in KEYCODE_BUTTON_A..KEYCODE_BUTTON_MODE || keyCode in KEYCODE_BUTTON_1..KEYCODE_BUTTON_16
}
