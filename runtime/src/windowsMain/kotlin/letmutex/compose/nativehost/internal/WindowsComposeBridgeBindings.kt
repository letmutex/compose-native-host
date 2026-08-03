package letmutex.compose.nativehost.internal

import letmutex.compose.nativehost.TextInputGeometry
import letmutex.compose.nativehost.WindowInfo

internal object WindowsComposeBridgeBindings {
    external fun nativeHostBridgeAvailable(): Boolean

    external fun nativeHostGetWindowInfo(runtimeId: Long): Long

    external fun nativeHostIsRunning(): Boolean

    external fun nativeHostUsesSharedLibraryRuntime(): Boolean

    external fun nativeHostWaitForShutdown()

    external fun nativeHostIsWindowAttached(runtimeId: Long): Boolean

    external fun nativeHostWaitForWindowAttached(runtimeId: Long): Boolean

    external fun nativeHostD3DAdapterPtr(runtimeId: Long): Long

    external fun nativeHostD3DDevicePtr(runtimeId: Long): Long

    external fun nativeHostD3DQueuePtr(runtimeId: Long): Long

    external fun nativeHostAcquireDrawableTexturePtr(runtimeId: Long): Long

    external fun nativeHostPresentDrawable(runtimeId: Long)

    external fun nativeHostWindowDrag(runtimeId: Long)

    external fun nativeHostWindowMinimize(runtimeId: Long)

    external fun nativeHostWindowMaximize(runtimeId: Long)

    external fun nativeHostWindowClose(runtimeId: Long)

    external fun nativeHostRequestRenderTick(runtimeId: Long)

    external fun nativeHostSetPointerIcon(runtimeId: Long, cursorType: Int)

    external fun nativeHostEmitAppEvent(
        runtimeId: Long,
        name: String,
        payload: String?,
    )

    external fun nativeHostLogPhaseTiming(name: String)

    external fun nativeHostEmitProfileFrameSample(
        runtimeId: Long,
        refreshRate: Int,
        rendered: Boolean,
        dispatchDelayMicros: Int,
        inputDrainMicros: Int,
        acquireDrawableMicros: Int,
        sceneRenderMicros: Int,
        submitMicros: Int,
    )

    external fun nativeHostProfileRenderingEnabled(runtimeId: Long): Boolean

    external fun nativeHostUpdateTextInputGeometry(
        runtimeId: Long,
        focusedRectLeft: Float,
        focusedRectTop: Float,
        focusedRectRight: Float,
        focusedRectBottom: Float,
        selectionStart: Int,
        selectionEnd: Int,
        compositionStart: Int,
        compositionEnd: Int,
    )

    external fun nativeHostClearTextInputGeometry(runtimeId: Long)

    external fun nativeHostReadClipboardText(): String?

    external fun nativeHostWriteClipboardText(text: String): Boolean

    external fun nativeHostPollFrameState(
        runtimeId: Long,
        maxCount: Int,
        frameState: NativeFrameState,
    ): Boolean

    external fun nativeHostGetThemeButtonPixels(
        partId: Int,
        stateId: Int,
        width: Int,
        height: Int
    ): IntArray?
}
