package androidx.compose.ui.input.pointer

import java.awt.Cursor
import letmutex.compose.nativehost.internal.MacOsComposeBridge

internal data class HostedPointerIcon(
    val cursorType: Int,
) : PointerIcon

internal class AwtCursor(
    val cursor: Cursor,
) : PointerIcon {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AwtCursor
        return cursor.type == other.cursor.type
    }

    override fun hashCode(): Int = cursor.type

    override fun toString(): String = "AwtCursor(cursor=$cursor)"
}

fun PointerIcon(cursor: Cursor): PointerIcon =
    if (MacOsComposeBridge.isSharedLibraryRuntime()) {
        HostedPointerIcon(cursor.type)
    } else {
        AwtCursor(cursor)
    }

internal val pointerIconDefault: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = defaultCursorType)

internal val pointerIconCrosshair: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = crosshairCursorType)

internal val pointerIconText: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = textCursorType)

internal val pointerIconHand: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = handCursorType)

private fun PointerIcon.cursorTypeOrNull(): Int? = when (this) {
    is AwtCursor -> cursor.type
    is HostedPointerIcon -> cursorType
    else -> null
}

internal fun PointerIcon.cursorTypeOrDefault(): Int =
    cursorTypeOrNull()
        ?.takeIf { it in supportedPointerCursorTypes }
        ?: defaultCursorType

private val supportedPointerCursorTypes = setOf(
    Cursor.DEFAULT_CURSOR,
    Cursor.CROSSHAIR_CURSOR,
    Cursor.TEXT_CURSOR,
    Cursor.WAIT_CURSOR,
    Cursor.SW_RESIZE_CURSOR,
    Cursor.SE_RESIZE_CURSOR,
    Cursor.NW_RESIZE_CURSOR,
    Cursor.NE_RESIZE_CURSOR,
    Cursor.N_RESIZE_CURSOR,
    Cursor.S_RESIZE_CURSOR,
    Cursor.W_RESIZE_CURSOR,
    Cursor.E_RESIZE_CURSOR,
    Cursor.HAND_CURSOR,
    Cursor.MOVE_CURSOR,
)

private fun hostedOrAwtPointerIcon(cursorType: Int): PointerIcon =
    if (MacOsComposeBridge.isSharedLibraryRuntime()) {
        HostedPointerIcon(cursorType)
    } else {
        AwtCursor(Cursor(cursorType))
    }

private const val defaultCursorType = 0
private const val crosshairCursorType = 1
private const val textCursorType = 2
private const val handCursorType = 12
