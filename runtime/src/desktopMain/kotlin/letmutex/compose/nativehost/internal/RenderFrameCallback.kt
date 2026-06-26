package letmutex.compose.nativehost.internal

import letmutex.compose.nativehost.RenderFrameStats

internal fun interface RenderFrameCallback {
    fun onFrameRendered(
        refreshRate: Int,
        dispatchDelayNanos: Long,
        inputDrainNanos: Long,
        renderStats: RenderFrameStats,
    )
}
