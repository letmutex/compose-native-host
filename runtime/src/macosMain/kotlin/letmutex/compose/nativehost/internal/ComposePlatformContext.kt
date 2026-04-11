package letmutex.compose.nativehost.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.cursorTypeOrDefault
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.WindowInfo as ComposePlatformWindowInfo
import androidx.compose.ui.text.InternalTextApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import letmutex.compose.nativehost.TextInputEventType
import letmutex.compose.nativehost.TextInputGeometry
import letmutex.compose.nativehost.WindowInfo
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.text.isISOControl

@OptIn(InternalComposeUiApi::class)
internal class ComposePlatformContext(
    private val hostBridge: MacOsComposeBridge,
) : PlatformContext by PlatformContext.Empty() {
    private val activeTextInputRequest = AtomicReference<PlatformTextInputMethodRequest?>(null)
    private val composeWindowInfo = ComposeWindowInfo()
    private var textInputGeometryListener: ((TextInputGeometry?) -> Unit)? = null

    override val windowInfo: ComposePlatformWindowInfo
        get() = composeWindowInfo

    override fun setPointerIcon(pointerIcon: PointerIcon) {
        hostBridge.setPointerIcon(pointerIcon.cursorTypeOrDefault())
    }

    fun updateWindowInfo(windowInfo: WindowInfo) {
        composeWindowInfo.updateWindowInfo(windowInfo)
    }

    fun setTextInputGeometryListener(listener: (TextInputGeometry?) -> Unit) {
        textInputGeometryListener = listener
    }

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        val scope = CoroutineScope(currentCoroutineContext())
        activeTextInputRequest.set(request)
        pushTextInputGeometry(request)
        scope.launch {
            snapshotFlow {
                request.toGeometry()
            }.collectLatest { geometry ->
                textInputGeometryListener?.invoke(geometry)
            }
        }
        try {
            awaitCancellation()
        } finally {
            activeTextInputRequest.compareAndSet(request, null)
            textInputGeometryListener?.invoke(null)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, InternalTextApi::class)
    fun handleKeyInput(
        eventType: KeyEventType,
        key: Key,
        codePoint: Int,
        keyboardModifiers: PointerKeyboardModifiers,
    ): Boolean {
        if (eventType != KeyEventType.KeyDown) {
            return false
        }
        if (keyboardModifiers.isCtrlPressed || keyboardModifiers.isMetaPressed) {
            return false
        }
        val request = activeTextInputRequest.get() ?: return false
        val selection = request.value().selection
        return when (key) {
            Key.Backspace -> {
                request.editText {
                    if (selection.collapsed) {
                        deleteSurroundingTextInCodePoints(1, 0)
                    } else {
                        commitText("", 1)
                    }
                }
                pushTextInputGeometry(request)
                true
            }

            Key.Delete -> {
                request.editText {
                    if (selection.collapsed) {
                        deleteSurroundingTextInCodePoints(0, 1)
                    } else {
                        commitText("", 1)
                    }
                }
                pushTextInputGeometry(request)
                true
            }

            Key.Enter,
            Key.NumPadEnter,
            -> {
                if (request.imeOptions.singleLine) {
                    request.onImeAction?.invoke(request.imeOptions.imeAction)
                } else {
                    request.editText {
                        commitText("\n", 1)
                    }
                    pushTextInputGeometry(request)
                }
                true
            }

            else if codePoint > 0 &&
                !codePoint.toChar().isISOControl() &&
                codePoint !in 0xE000..0xF8FF -> true

            else -> false
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, InternalTextApi::class)
    fun handleTextInput(
        eventType: TextInputEventType,
        text: String,
    ): Boolean {
        val request = activeTextInputRequest.get() ?: return false
        when (eventType) {
            TextInputEventType.Commit -> {
                if (text.isEmpty()) {
                    return false
                }
                request.editText {
                    commitText(text, 1)
                }
                pushTextInputGeometry(request)
            }

            TextInputEventType.SetComposing -> {
                request.editText {
                    setComposingText(text, 1)
                }
                pushTextInputGeometry(request)
            }

            TextInputEventType.FinishComposing -> {
                request.editText {
                    finishComposingText()
                }
                pushTextInputGeometry(request)
            }
        }
        return true
    }

    private fun pushTextInputGeometry(request: PlatformTextInputMethodRequest) {
        textInputGeometryListener?.invoke(request.toGeometry())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun PlatformTextInputMethodRequest.toGeometry(): TextInputGeometry? {
    val focusedRect = focusedRectInRoot() ?: return null
    val value = value()
    val selection = value.selection
    val composition = value.composition
    return TextInputGeometry(
        focusedRectLeft = focusedRect.left,
        focusedRectTop = focusedRect.top,
        focusedRectRight = focusedRect.right,
        focusedRectBottom = focusedRect.bottom,
        selectionStart = selection.start,
        selectionEnd = selection.end,
        compositionStart = composition?.start ?: -1,
        compositionEnd = composition?.end ?: -1,
    )
}

private class ComposeWindowInfo : ComposePlatformWindowInfo {
    private var focused by mutableStateOf(true)
    private var size by mutableStateOf(IntSize.Zero)
    private var sizeDp by mutableStateOf(DpSize.Zero)

    override val isWindowFocused: Boolean
        get() = focused

    override val containerSize: IntSize
        get() = size

    override val containerDpSize: DpSize
        get() = sizeDp

    fun updateWindowInfo(windowInfo: WindowInfo) {
        focused = windowInfo.isFocused
        size = IntSize(windowInfo.width, windowInfo.height)
        val density = windowInfo.scale.takeIf { it > 0f } ?: 1f
        sizeDp = DpSize((windowInfo.width / density).dp, (windowInfo.height / density).dp)
    }
}
