package letmutex.compose.nativehost.internal

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.scene.ComposeScene
import letmutex.compose.nativehost.TextInputEventType

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal fun dispatchMacOsInputEvents(
    records: LongArray,
    eventCount: Int,
    texts: Array<String?>,
    activeScene: ComposeScene,
    platformContext: ComposePlatformContext,
) {
    if (eventCount <= 0) {
        return
    }
    repeat(eventCount) { index ->
        val offset = index * INPUT_EVENT_RECORD_STRIDE
        val kind = records[offset + INPUT_EVENT_FIELD_KIND].toInt()
        val timestampMillis = records[offset + INPUT_EVENT_FIELD_TIMESTAMP]
        val rawType = records[offset + INPUT_EVENT_FIELD_TYPE].toInt()
        when (kind) {
            INPUT_EVENT_KIND_POINTER ->
                activeScene.sendPointerEvent(
                    eventType = mapPointerEventType(rawType),
                    position = Offset(
                        x = Float.fromBits(records[offset + INPUT_EVENT_FIELD_A].toInt()),
                        y = Float.fromBits(records[offset + INPUT_EVENT_FIELD_B].toInt()),
                    ),
                    scrollDelta = Offset(
                        x = Float.fromBits(records[offset + INPUT_EVENT_FIELD_C].toInt()),
                        y = Float.fromBits(records[offset + INPUT_EVENT_FIELD_D].toInt()),
                    ),
                    timeMillis = timestampMillis,
                    type = PointerType.Mouse,
                    buttons = mapPointerButtons(records[offset + INPUT_EVENT_FIELD_E].toInt()),
                    keyboardModifiers = mapKeyboardModifiers(records[offset + INPUT_EVENT_FIELD_F].toInt()),
                    button = mapPointerButton(records[offset + INPUT_EVENT_FIELD_G].toInt()),
                )

            INPUT_EVENT_KIND_KEY -> {
                val eventType = mapKeyEventType(rawType)
                val key = mapComposeKey(records[offset + INPUT_EVENT_FIELD_A].toInt())
                val codePoint = records[offset + INPUT_EVENT_FIELD_B].toInt()
                val keyboardModifiers = mapKeyboardModifiers(records[offset + INPUT_EVENT_FIELD_C].toInt())
                val keyEvent = KeyEvent(
                    key = key,
                    type = eventType,
                    codePoint = codePoint,
                    isCtrlPressed = keyboardModifiers.isCtrlPressed,
                    isMetaPressed = keyboardModifiers.isMetaPressed,
                    isAltPressed = keyboardModifiers.isAltPressed,
                    isShiftPressed = keyboardModifiers.isShiftPressed,
                )
                if (activeScene.sendKeyEvent(keyEvent)) {
                    return@repeat
                }
                if (platformContext.handleKeyInput(eventType, key, codePoint, keyboardModifiers)) {
                    return@repeat
                }
            }

            INPUT_EVENT_KIND_TEXT ->
                platformContext.handleTextInput(
                    eventType = mapTextInputEventType(rawType),
                    text = texts[index].orEmpty(),
                )
        }
    }
}

private fun mapPointerEventType(rawValue: Int): PointerEventType =
    when (rawValue) {
        POINTER_EVENT_TYPE_PRESS -> PointerEventType.Press
        POINTER_EVENT_TYPE_RELEASE -> PointerEventType.Release
        POINTER_EVENT_TYPE_MOVE -> PointerEventType.Move
        POINTER_EVENT_TYPE_ENTER -> PointerEventType.Enter
        POINTER_EVENT_TYPE_EXIT -> PointerEventType.Exit
        POINTER_EVENT_TYPE_SCROLL -> PointerEventType.Scroll
        else -> PointerEventType.Unknown
    }

private fun mapKeyEventType(rawValue: Int): KeyEventType =
    when (rawValue) {
        KEY_EVENT_TYPE_DOWN -> KeyEventType.KeyDown
        KEY_EVENT_TYPE_UP -> KeyEventType.KeyUp
        else -> KeyEventType.Unknown
    }

private fun mapTextInputEventType(rawValue: Int): TextInputEventType =
    when (rawValue) {
        TEXT_INPUT_EVENT_TYPE_COMMIT -> TextInputEventType.Commit
        TEXT_INPUT_EVENT_TYPE_SET_COMPOSING -> TextInputEventType.SetComposing
        TEXT_INPUT_EVENT_TYPE_FINISH_COMPOSING -> TextInputEventType.FinishComposing
        else -> TextInputEventType.Commit
    }

private fun mapPointerButtons(rawMask: Int): PointerButtons =
    PointerButtons(
        isPrimaryPressed = rawMask and POINTER_BUTTON_PRIMARY != 0,
        isSecondaryPressed = rawMask and POINTER_BUTTON_SECONDARY != 0,
        isTertiaryPressed = rawMask and POINTER_BUTTON_TERTIARY != 0,
        isBackPressed = rawMask and POINTER_BUTTON_BACK != 0,
        isForwardPressed = rawMask and POINTER_BUTTON_FORWARD != 0,
    )

private fun mapKeyboardModifiers(rawMask: Int): PointerKeyboardModifiers =
    PointerKeyboardModifiers(
        isCtrlPressed = rawMask and KEYBOARD_MODIFIER_CTRL != 0,
        isMetaPressed = rawMask and KEYBOARD_MODIFIER_META != 0,
        isAltPressed = rawMask and KEYBOARD_MODIFIER_ALT != 0,
        isShiftPressed = rawMask and KEYBOARD_MODIFIER_SHIFT != 0,
    )

private fun mapPointerButton(rawIndex: Int): PointerButton? =
    when (rawIndex) {
        0 -> PointerButton.Primary
        1 -> PointerButton.Secondary
        2 -> PointerButton.Tertiary
        3 -> PointerButton.Back
        4 -> PointerButton.Forward
        else -> null
    }

private fun mapComposeKey(rawKeyCode: Int): Key =
    when (rawKeyCode) {
        0 -> Key.A
        1 -> Key.S
        2 -> Key.D
        3 -> Key.F
        4 -> Key.H
        5 -> Key.G
        6 -> Key.Z
        7 -> Key.X
        8 -> Key.C
        9 -> Key.V
        11 -> Key.B
        12 -> Key.Q
        13 -> Key.W
        14 -> Key.E
        15 -> Key.R
        16 -> Key.Y
        17 -> Key.T
        18 -> Key.One
        19 -> Key.Two
        20 -> Key.Three
        21 -> Key.Four
        22 -> Key.Six
        23 -> Key.Five
        24 -> Key.Equals
        25 -> Key.Nine
        26 -> Key.Seven
        27 -> Key.Minus
        28 -> Key.Eight
        29 -> Key.Zero
        30 -> Key.RightBracket
        31 -> Key.O
        32 -> Key.U
        33 -> Key.LeftBracket
        34 -> Key.I
        35 -> Key.P
        36 -> Key.Enter
        37 -> Key.L
        38 -> Key.J
        39 -> Key.Apostrophe
        40 -> Key.K
        41 -> Key.Semicolon
        42 -> Key.Backslash
        43 -> Key.Comma
        44 -> Key.Slash
        45 -> Key.N
        46 -> Key.M
        47 -> Key.Period
        48 -> Key.Tab
        49 -> Key.Spacebar
        50 -> Key.Grave
        51 -> Key.Backspace
        53 -> Key.Escape
        55 -> Key.MetaLeft
        54 -> Key.MetaRight
        56 -> Key.ShiftLeft
        57 -> Key.CapsLock
        58 -> Key.AltLeft
        59 -> Key.CtrlLeft
        60 -> Key.ShiftRight
        61 -> Key.AltRight
        62 -> Key.CtrlRight
        63 -> Key.Function
        65 -> Key.NumPadDot
        67 -> Key.Multiply
        69 -> Key.Plus
        71 -> Key.Clear
        75 -> Key.NumPadDivide
        76 -> Key.NumPadEnter
        78 -> Key.Minus
        81 -> Key.Equals
        82 -> Key.NumPad0
        83 -> Key.NumPad1
        84 -> Key.NumPad2
        85 -> Key.NumPad3
        86 -> Key.NumPad4
        87 -> Key.NumPad5
        88 -> Key.NumPad6
        89 -> Key.NumPad7
        91 -> Key.NumPad8
        92 -> Key.NumPad9
        96 -> Key.F5
        97 -> Key.F6
        98 -> Key.F7
        99 -> Key.F3
        100 -> Key.F8
        101 -> Key.F9
        103 -> Key.F11
        105 -> Key.Unknown
        106 -> Key.Unknown
        107 -> Key.Unknown
        109 -> Key.F10
        111 -> Key.F12
        113 -> Key.Unknown
        114 -> Key.Insert
        115 -> Key.Home
        116 -> Key.PageUp
        117 -> Key.Delete
        118 -> Key.F4
        119 -> Key.MoveEnd
        120 -> Key.F2
        121 -> Key.PageDown
        122 -> Key.F1
        123 -> Key.DirectionLeft
        124 -> Key.DirectionRight
        125 -> Key.DirectionDown
        126 -> Key.DirectionUp
        else -> Key.Unknown
    }

private const val INPUT_EVENT_KIND_NONE = 0
private const val INPUT_EVENT_KIND_POINTER = 1
private const val INPUT_EVENT_KIND_KEY = 2
private const val INPUT_EVENT_KIND_TEXT = 3

private const val INPUT_EVENT_RECORD_STRIDE = 10
private const val INPUT_EVENT_FIELD_KIND = 0
private const val INPUT_EVENT_FIELD_TIMESTAMP = 1
private const val INPUT_EVENT_FIELD_TYPE = 2
private const val INPUT_EVENT_FIELD_A = 3
private const val INPUT_EVENT_FIELD_B = 4
private const val INPUT_EVENT_FIELD_C = 5
private const val INPUT_EVENT_FIELD_D = 6
private const val INPUT_EVENT_FIELD_E = 7
private const val INPUT_EVENT_FIELD_F = 8
private const val INPUT_EVENT_FIELD_G = 9

private const val POINTER_EVENT_TYPE_PRESS = 1
private const val POINTER_EVENT_TYPE_RELEASE = 2
private const val POINTER_EVENT_TYPE_MOVE = 3
private const val POINTER_EVENT_TYPE_ENTER = 4
private const val POINTER_EVENT_TYPE_EXIT = 5
private const val POINTER_EVENT_TYPE_SCROLL = 6

private const val KEY_EVENT_TYPE_DOWN = 1
private const val KEY_EVENT_TYPE_UP = 2

private const val TEXT_INPUT_EVENT_TYPE_COMMIT = 1
private const val TEXT_INPUT_EVENT_TYPE_SET_COMPOSING = 2
private const val TEXT_INPUT_EVENT_TYPE_FINISH_COMPOSING = 3

private const val POINTER_BUTTON_PRIMARY = 1 shl 0
private const val POINTER_BUTTON_SECONDARY = 1 shl 1
private const val POINTER_BUTTON_TERTIARY = 1 shl 2
private const val POINTER_BUTTON_BACK = 1 shl 3
private const val POINTER_BUTTON_FORWARD = 1 shl 4

private const val KEYBOARD_MODIFIER_CTRL = 1 shl 0
private const val KEYBOARD_MODIFIER_META = 1 shl 1
private const val KEYBOARD_MODIFIER_ALT = 1 shl 2
private const val KEYBOARD_MODIFIER_SHIFT = 1 shl 3
