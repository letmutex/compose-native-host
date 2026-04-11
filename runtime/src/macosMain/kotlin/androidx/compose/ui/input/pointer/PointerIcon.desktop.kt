package androidx.compose.ui.input.pointer

import java.awt.Cursor
import letmutex.compose.nativehost.internal.MacOsComposeBridge

private data class HostedPointerIcon(
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

internal val pointerIconWait: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = waitCursorType)

internal val pointerIconHand: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = handCursorType)

internal val pointerIconMove: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = moveCursorType)

internal val pointerIconWestResize: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = westResizeCursorType)

internal val pointerIconEastResize: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = eastResizeCursorType)

internal val pointerIconSouthResize: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = southResizeCursorType)

internal val pointerIconNorthResize: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = northResizeCursorType)

internal val pointerIconSouthwestResize: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = southwestResizeCursorType)

internal val pointerIconSoutheastResize: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = southeastResizeCursorType)

internal val pointerIconNorthwestResize: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = northwestResizeCursorType)

internal val pointerIconNortheastResize: PointerIcon =
    hostedOrAwtPointerIcon(cursorType = northeastResizeCursorType)

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
private const val waitCursorType = 3
private const val southwestResizeCursorType = 4
private const val southeastResizeCursorType = 5
private const val northwestResizeCursorType = 6
private const val northeastResizeCursorType = 7
private const val northResizeCursorType = 8
private const val southResizeCursorType = 9
private const val westResizeCursorType = 10
private const val eastResizeCursorType = 11
private const val handCursorType = 12
private const val moveCursorType = 13
