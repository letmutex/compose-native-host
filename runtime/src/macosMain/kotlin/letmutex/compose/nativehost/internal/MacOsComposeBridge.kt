package letmutex.compose.nativehost.internal

import letmutex.compose.nativehost.TextInputGeometry
import letmutex.compose.nativehost.WindowInfo

internal class MacOsComposeBridge(
    private val runtimeId: Long,
) {
    init {
        check(isAvailable()) {
            "MacOsComposeBridge requires the native host bridge to be loaded."
        }
    }

    companion object {
        private const val INPUT_BATCH_MAX_EVENTS = 64

        @Volatile
        private var bridgeAvailableChecked = false

        @Volatile
        private var bridgeAvailableResult = false

        @Volatile
        private var sharedLibraryRuntimeChecked = false

        @Volatile
        private var sharedLibraryRuntimeResult = false

        fun isAvailable(): Boolean {
            if (bridgeAvailableChecked) {
                return bridgeAvailableResult
            }
            val result = MacOsNativeBridgeLoader.isAvailable() &&
                MacOsComposeBridgeBindings.nativeHostBridgeAvailable()
            bridgeAvailableResult = result
            bridgeAvailableChecked = true
            return result
        }

        fun isRunning(): Boolean =
            isAvailable() && MacOsComposeBridgeBindings.nativeHostIsRunning()

        fun isSharedLibraryRuntime(): Boolean {
            if (sharedLibraryRuntimeChecked) {
                return sharedLibraryRuntimeResult
            }
            if (!isAvailable()) {
                return false
            }
            synchronized(this) {
                if (sharedLibraryRuntimeChecked) {
                    return sharedLibraryRuntimeResult
                }
                sharedLibraryRuntimeResult =
                    MacOsComposeBridgeBindings.nativeHostUsesSharedLibraryRuntime()
                sharedLibraryRuntimeChecked = true
                return sharedLibraryRuntimeResult
            }
        }

        fun waitForShutdown() {
            if (isAvailable()) {
                MacOsComposeBridgeBindings.nativeHostWaitForShutdown()
            }
        }
    }

    private val frameStateBuffer = NativeFrameState(INPUT_BATCH_MAX_EVENTS)

    fun isWindowAttached(): Boolean =
        MacOsComposeBridgeBindings.nativeHostIsWindowAttached(runtimeId)

    fun waitForWindowAttached(): Boolean =
        MacOsComposeBridgeBindings.nativeHostWaitForWindowAttached(runtimeId)

    fun currentWindowInfo(): WindowInfo? =
        WindowInfo.fromPacked(
            MacOsComposeBridgeBindings.nativeHostGetWindowInfo(runtimeId)
        )

    fun isProfileRenderingEnabled(): Boolean =
        MacOsComposeBridgeBindings.nativeHostProfileRenderingEnabled(runtimeId)

    fun pollFrameState(): NativeFrameState? {
        val hasFrameState =
            MacOsComposeBridgeBindings.nativeHostPollFrameState(
                runtimeId = runtimeId,
                maxCount = INPUT_BATCH_MAX_EVENTS,
                frameState = frameStateBuffer,
            )
        return if (hasFrameState) frameStateBuffer else null
    }

    fun requestRender() {
        MacOsComposeBridgeBindings.nativeHostRequestRenderTick(runtimeId)
    }

    fun setPointerIcon(cursorType: Int) {
        MacOsComposeBridgeBindings.nativeHostSetPointerIcon(runtimeId, cursorType)
    }

    fun updateTextInputGeometry(geometry: TextInputGeometry?) {
        if (geometry == null) {
            MacOsComposeBridgeBindings.nativeHostClearTextInputGeometry(runtimeId)
            return
        }
        MacOsComposeBridgeBindings.nativeHostUpdateTextInputGeometry(
            runtimeId = runtimeId,
            focusedRectLeft = geometry.focusedRectLeft,
            focusedRectTop = geometry.focusedRectTop,
            focusedRectRight = geometry.focusedRectRight,
            focusedRectBottom = geometry.focusedRectBottom,
            selectionStart = geometry.selectionStart,
            selectionEnd = geometry.selectionEnd,
            compositionStart = geometry.compositionStart,
            compositionEnd = geometry.compositionEnd,
        )
    }

    fun metalDevicePtr(): Long =
        MacOsComposeBridgeBindings.nativeHostMetalDevicePtr(runtimeId)

    fun metalQueuePtr(): Long =
        MacOsComposeBridgeBindings.nativeHostMetalQueuePtr(runtimeId)

    fun acquireDrawableTexturePtr(): Long =
        MacOsComposeBridgeBindings.nativeHostAcquireDrawableTexturePtr(runtimeId)

    fun presentDrawable() {
        MacOsComposeBridgeBindings.nativeHostPresentDrawable(runtimeId)
    }

    fun emitAppEvent(
        name: String,
        payload: String? = null,
    ) {
        MacOsComposeBridgeBindings.nativeHostEmitAppEvent(runtimeId, name, payload)
    }

    fun logPhaseTiming(name: String) {
        MacOsComposeBridgeBindings.nativeHostLogPhaseTiming(name)
    }

    fun emitProfileFrameSample(
        refreshRate: Int,
        rendered: Boolean,
        dispatchDelayMicros: Int,
        inputDrainMicros: Int,
        acquireDrawableMicros: Int,
        sceneRenderMicros: Int,
        submitMicros: Int,
    ) {
        MacOsComposeBridgeBindings.nativeHostEmitProfileFrameSample(
            runtimeId = runtimeId,
            refreshRate = refreshRate,
            rendered = rendered,
            dispatchDelayMicros = dispatchDelayMicros,
            inputDrainMicros = inputDrainMicros,
            acquireDrawableMicros = acquireDrawableMicros,
            sceneRenderMicros = sceneRenderMicros,
            submitMicros = submitMicros,
        )
    }
}

internal object MacOsNativeBridgeLoader {
    @Volatile
    private var loadAttempted = false

    @Volatile
    private var available = false

    fun isAvailable(): Boolean {
        if (loadAttempted) {
            return available
        }
        synchronized(this) {
            if (loadAttempted) {
                return available
            }
            available = runCatching {
                val explicitPath =
                    System.getProperty(BRIDGE_PATH_PROPERTY)
                        ?.takeIf { it.isNotBlank() }
                        ?: System.getenv(BRIDGE_PATH_ENV)
                            ?.takeIf { it.isNotBlank() }
                if (explicitPath != null) {
                    System.load(explicitPath)
                    MacOsComposeBridgeBindings.nativeHostBridgeAvailable()
                } else {
                    MacOsComposeBridgeBindings.nativeHostBridgeAvailable()
                }
            }.getOrDefault(false)
            loadAttempted = true
            return available
        }
    }

    private const val BRIDGE_PATH_PROPERTY = "compose.native.host.bridge.path"
    private const val BRIDGE_PATH_ENV = "COMPOSE_NATIVE_HOST_BRIDGE_PATH"
}

internal object MacOsComposeBridgeBindings {
    external fun nativeHostBridgeAvailable(): Boolean

    external fun nativeHostGetWindowInfo(runtimeId: Long): Long

    external fun nativeHostIsRunning(): Boolean

    external fun nativeHostUsesSharedLibraryRuntime(): Boolean

    external fun nativeHostWaitForShutdown()

    external fun nativeHostIsWindowAttached(runtimeId: Long): Boolean

    external fun nativeHostWaitForWindowAttached(runtimeId: Long): Boolean

    external fun nativeHostMetalDevicePtr(runtimeId: Long): Long

    external fun nativeHostMetalQueuePtr(runtimeId: Long): Long

    external fun nativeHostAcquireDrawableTexturePtr(runtimeId: Long): Long

    external fun nativeHostPresentDrawable(runtimeId: Long)

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

    external fun nativeHostPollFrameState(
        runtimeId: Long,
        maxCount: Int,
        frameState: NativeFrameState,
    ): Boolean
}
