package letmutex.compose.nativehost

import kotlin.math.roundToInt

fun WindowInfo(
    width: Int,
    height: Int,
    scale: Float,
    refreshRate: Int,
    isFocused: Boolean,
): WindowInfo {
    // Packed layout, low -> high bits:
    // heightPx:15 (max 32767), widthPx:15 (max 32767), scaleX1000:14 (0.001 precision, max 16.383),
    // refreshRateHz:11 (max 2047), isFocused:1.
    val packedHeight =
        height.coerceIn(0, 0x7FFF).toLong()
    val packedWidth =
        width.coerceIn(0, 0x7FFF).toLong() shl 15
    val packedScale =
        ((scale.coerceAtLeast(0f) * 1000).roundToInt()
            .coerceIn(0, 0x3FFF)
            .toLong()) shl 30
    val packedRefreshRate =
        refreshRate.coerceIn(0, 0x7FF).toLong() shl 44
    val packedFocus = (if (isFocused) 1L else 0L) shl 55

    return WindowInfo(
        packed = packedHeight or packedWidth or packedScale or packedRefreshRate or packedFocus,
    )
}

@JvmInline
value class WindowInfo internal constructor(private val packed: Long) {
    // bits 15..29: width in px, max 32767
    val width: Int
        get() = ((packed ushr 15) and 0x7FFF).toInt()

    // bits 0..14: height in px, max 32767
    val height: Int
        get() = (packed and 0x7FFF).toInt()

    // bits 30..43: scale * 1000, precision 0.001, max 16.383
    val scale: Float
        get() = (((packed ushr 30) and 0x3FFF).toInt()) / 1000f

    // bits 44..54: refresh rate in Hz, max 2047
    val refreshRate: Int
        get() = ((packed ushr 44) and 0x7FF).toInt()

    // bit 55: focused flag
    val isFocused: Boolean
        get() = ((packed ushr 55) and 1L) != 0L

    internal val packedValue: Long
        get() = packed

    companion object {
        internal fun fromPacked(packed: Long): WindowInfo? {
            val windowInfo = WindowInfo(packed = packed)
            if (windowInfo.width <= 0 || windowInfo.height <= 0) {
                return null
            }
            return windowInfo
        }
    }
}
