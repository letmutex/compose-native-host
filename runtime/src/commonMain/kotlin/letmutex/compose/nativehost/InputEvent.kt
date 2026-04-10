package letmutex.compose.nativehost

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType

internal sealed interface InputEvent {
    data class Pointer(
        val eventType: PointerEventType,
        val position: Offset,
        val scrollDelta: Offset = Offset.Zero,
        val timeMillis: Long,
        val pointerType: PointerType = PointerType.Mouse,
        val buttons: PointerButtons = PointerButtons(),
        val keyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
        val button: PointerButton? = null,
    ) : InputEvent

    data class KeyInput(
        val eventType: KeyEventType,
        val key: Key,
        val codePoint: Int = 0,
        val keyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
    ) : InputEvent

    data class TextInput(
        val eventType: TextInputEventType,
        val text: String = "",
        val timeMillis: Long,
    ) : InputEvent
}

internal enum class TextInputEventType {
    Commit,
    SetComposing,
    FinishComposing,
}
