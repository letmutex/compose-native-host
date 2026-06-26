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
internal fun dispatchWindowsInputEvents(
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

private fun mapComposeKey(vkCode: Int): Key =
    when (vkCode) {
        0x08 -> Key.Backspace
        0x09 -> Key.Tab
        0x0D -> Key.Enter
        0x10 -> Key.ShiftLeft
        0x11 -> Key.CtrlLeft
        0x12 -> Key.AltLeft
        0x13 -> Key.Unknown
        0x14 -> Key.CapsLock
        0x1B -> Key.Escape
        0x20 -> Key.Spacebar
        0x21 -> Key.PageUp
        0x22 -> Key.PageDown
        0x23 -> Key.MoveEnd
        0x24 -> Key.Home
        0x25 -> Key.DirectionLeft
        0x26 -> Key.DirectionUp
        0x27 -> Key.DirectionRight
        0x28 -> Key.DirectionDown
        0x2C -> Key.PrintScreen
        0x2D -> Key.Insert
        0x2E -> Key.Delete
        0x30 -> Key.Zero
        0x31 -> Key.One
        0x32 -> Key.Two
        0x33 -> Key.Three
        0x34 -> Key.Four
        0x35 -> Key.Five
        0x36 -> Key.Six
        0x37 -> Key.Seven
        0x38 -> Key.Eight
        0x39 -> Key.Nine
        0x41 -> Key.A
        0x42 -> Key.B
        0x43 -> Key.C
        0x44 -> Key.D
        0x45 -> Key.E
        0x46 -> Key.F
        0x47 -> Key.G
        0x48 -> Key.H
        0x49 -> Key.I
        0x4A -> Key.J
        0x4B -> Key.K
        0x4C -> Key.L
        0x4D -> Key.M
        0x4E -> Key.N
        0x4F -> Key.O
        0x50 -> Key.P
        0x51 -> Key.Q
        0x52 -> Key.R
        0x53 -> Key.S
        0x54 -> Key.T
        0x55 -> Key.U
        0x56 -> Key.V
        0x57 -> Key.W
        0x58 -> Key.X
        0x59 -> Key.Y
        0x5A -> Key.Z
        0x5B -> Key.MetaLeft
        0x5C -> Key.MetaRight
        0x60 -> Key.NumPad0
        0x61 -> Key.NumPad1
        0x62 -> Key.NumPad2
        0x63 -> Key.NumPad3
        0x64 -> Key.NumPad4
        0x65 -> Key.NumPad5
        0x66 -> Key.NumPad6
        0x67 -> Key.NumPad7
        0x68 -> Key.NumPad8
        0x69 -> Key.NumPad9
        0x6A -> Key.Multiply
        0x6B -> Key.Plus
        0x6D -> Key.Minus
        0x6E -> Key.NumPadDot
        0x6F -> Key.NumPadDivide
        0x70 -> Key.F1
        0x71 -> Key.F2
        0x72 -> Key.F3
        0x73 -> Key.F4
        0x74 -> Key.F5
        0x75 -> Key.F6
        0x76 -> Key.F7
        0x77 -> Key.F8
        0x78 -> Key.F9
        0x79 -> Key.F10
        0x7A -> Key.F11
        0x7B -> Key.F12
        0x90 -> Key.NumLock
        0x91 -> Key.ScrollLock
        0xA0 -> Key.ShiftLeft
        0xA1 -> Key.ShiftRight
        0xA2 -> Key.CtrlLeft
        0xA3 -> Key.CtrlRight
        0xA4 -> Key.AltLeft
        0xA5 -> Key.AltRight
        0xBA -> Key.Semicolon
        0xBB -> Key.Equals
        0xBC -> Key.Comma
        0xBD -> Key.Minus
        0xBE -> Key.Period
        0xBF -> Key.Slash
        0xC0 -> Key.Grave
        0xDB -> Key.LeftBracket
        0xDC -> Key.Backslash
        0xDD -> Key.RightBracket
        0xDE -> Key.Apostrophe
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
