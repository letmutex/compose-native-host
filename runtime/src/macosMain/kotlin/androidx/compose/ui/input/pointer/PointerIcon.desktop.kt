package androidx.compose.ui.input.pointer

import java.awt.Cursor
import letmutex.compose.nativehost.internal.MacOsComposeBridge

private data class HostedPointerIcon(
    val kind: String,
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
    if (MacOsComposeBridge.isAvailable()) {
        HostedPointerIcon(kind = "custom")
    } else {
        AwtCursor(cursor)
    }

internal val pointerIconDefault: PointerIcon =
    if (MacOsComposeBridge.isAvailable()) {
        HostedPointerIcon(kind = "default")
    } else {
        AwtCursor(Cursor(Cursor.DEFAULT_CURSOR))
    }

internal val pointerIconCrosshair: PointerIcon =
    if (MacOsComposeBridge.isAvailable()) {
        HostedPointerIcon(kind = "crosshair")
    } else {
        AwtCursor(Cursor(Cursor.CROSSHAIR_CURSOR))
    }

internal val pointerIconText: PointerIcon =
    if (MacOsComposeBridge.isAvailable()) {
        HostedPointerIcon(kind = "text")
    } else {
        AwtCursor(Cursor(Cursor.TEXT_CURSOR))
    }

internal val pointerIconHand: PointerIcon =
    if (MacOsComposeBridge.isAvailable()) {
        HostedPointerIcon(kind = "hand")
    } else {
        AwtCursor(Cursor(Cursor.HAND_CURSOR))
    }
