package letmutex.compose.nativehost.internal

import letmutex.compose.nativehost.TextInputGeometry
import letmutex.compose.nativehost.WindowInfo

interface NativeHostBridge {
    fun isAvailable(): Boolean
    fun isSharedLibraryRuntime(): Boolean
    fun emitAppEvent(name: String, payload: String? = null)
    fun performWindowDrag()
    fun minimizeWindow()
    fun maximizeWindow()
    fun closeWindow()
    fun currentWindowInfo(): WindowInfo?
    fun pollFrameState(): NativeFrameState?
    fun requestRender()
    fun setPointerIcon(cursorType: Int)
    fun updateTextInputGeometry(geometry: TextInputGeometry?)
    fun getAdapterPtr(): Long
    fun getDevicePtr(): Long
    fun getQueuePtr(): Long
    fun acquireDrawableTexturePtr(): Long
    fun presentDrawable()
    fun emitProfileFrameSample(
        refreshRate: Int,
        rendered: Boolean,
        dispatchDelayMicros: Int,
        inputDrainMicros: Int,
        acquireDrawableMicros: Int,
        sceneRenderMicros: Int,
        submitMicros: Int,
    )
}
