package letmutex.compose.nativehost

private const val RENDERED_BIT_INDEX = 63
private const val FRAME_STAT_BITS = 21 // 21 bits per timing field.
private const val FRAME_STAT_MAX_MICROS = 0x1FFFFFL // Max stored value: 2,097,151 us.

internal fun RenderFrameStats(
    rendered: Boolean,
    acquireDrawableNanos: Long = 0L,
    sceneRenderNanos: Long = 0L,
    submitNanos: Long = 0L,
): RenderFrameStats {
    if (!rendered) return RenderFrameStats.NotRendered

    // 1. ns -> us
    val acqMicros = (acquireDrawableNanos / 1000).coerceIn(0L, FRAME_STAT_MAX_MICROS)
    val renMicros = (sceneRenderNanos / 1000).coerceIn(0L, FRAME_STAT_MAX_MICROS)
    val subMicros = (submitNanos / 1000).coerceIn(0L, FRAME_STAT_MAX_MICROS)

    // 2. pack
    val isRenderedBit = 1L shl RENDERED_BIT_INDEX
    val packedAcquire = acqMicros shl (FRAME_STAT_BITS * 2)
    val packedRender = renMicros shl FRAME_STAT_BITS
    val packedSubmit = subMicros

    return RenderFrameStats(isRenderedBit or packedAcquire or packedRender or packedSubmit)
}

@JvmInline
internal value class RenderFrameStats(private val packed: Long) {

    val rendered: Boolean
        get() = (packed ushr RENDERED_BIT_INDEX) == 1L

    val acquireDrawableMicros: Int
        get() = ((packed ushr (FRAME_STAT_BITS * 2)) and FRAME_STAT_MAX_MICROS).toInt()

    val sceneRenderMicros: Int
        get() = ((packed ushr FRAME_STAT_BITS) and FRAME_STAT_MAX_MICROS).toInt()

    val submitMicros: Int
        get() = (packed and FRAME_STAT_MAX_MICROS).toInt()

    val acquireDrawableNanos: Long get() = acquireDrawableMicros * 1000L
    val sceneRenderNanos: Long get() = sceneRenderMicros * 1000L
    val submitNanos: Long get() = submitMicros * 1000L

    companion object {
        val NotRendered = RenderFrameStats(0L) // rendered = 0 / false
    }
}
