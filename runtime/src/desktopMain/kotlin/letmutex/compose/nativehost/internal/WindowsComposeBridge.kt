package letmutex.compose.nativehost.internal

import letmutex.compose.nativehost.TextInputGeometry
import letmutex.compose.nativehost.WindowInfo

internal class WindowsComposeBridge(
    private val runtimeId: Long,
) : NativeHostBridge {
    override fun isAvailable(): Boolean = Companion.isAvailable()

    override fun isSharedLibraryRuntime(): Boolean = Companion.isSharedLibraryRuntime()

    override fun getAdapterPtr(): Long = d3dAdapterPtr()

    override fun getDevicePtr(): Long = d3dDevicePtr()

    override fun getQueuePtr(): Long = d3dQueuePtr()

    init {
        check(isAvailable()) {
            "WindowsComposeBridge requires the native host bridge to be loaded."
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
            val result = WindowsNativeBridgeLoader.isAvailable() &&
                WindowsComposeBridgeBindings.nativeHostBridgeAvailable()
            bridgeAvailableResult = result
            bridgeAvailableChecked = true
            return result
        }

        fun isRunning(): Boolean =
            isAvailable() && WindowsComposeBridgeBindings.nativeHostIsRunning()

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
                    WindowsComposeBridgeBindings.nativeHostUsesSharedLibraryRuntime()
                sharedLibraryRuntimeChecked = true
                return sharedLibraryRuntimeResult
            }
        }

        fun waitForShutdown() {
            if (isAvailable()) {
                WindowsComposeBridgeBindings.nativeHostWaitForShutdown()
            }
        }
    }

    private val frameStateBuffer = NativeFrameState(INPUT_BATCH_MAX_EVENTS)

    fun isWindowAttached(): Boolean =
        WindowsComposeBridgeBindings.nativeHostIsWindowAttached(runtimeId)

    fun waitForWindowAttached(): Boolean =
        WindowsComposeBridgeBindings.nativeHostWaitForWindowAttached(runtimeId)

    override fun currentWindowInfo(): WindowInfo? =
        WindowInfo.fromPacked(
            WindowsComposeBridgeBindings.nativeHostGetWindowInfo(runtimeId)
        )

    fun isProfileRenderingEnabled(): Boolean =
        WindowsComposeBridgeBindings.nativeHostProfileRenderingEnabled(runtimeId)

    override fun pollFrameState(): NativeFrameState? {
        val hasFrameState =
            WindowsComposeBridgeBindings.nativeHostPollFrameState(
                runtimeId = runtimeId,
                maxCount = INPUT_BATCH_MAX_EVENTS,
                frameState = frameStateBuffer,
            )
        return if (hasFrameState) frameStateBuffer else null
    }

    override fun requestRender() {
        WindowsComposeBridgeBindings.nativeHostRequestRenderTick(runtimeId)
    }

    override fun setPointerIcon(cursorType: Int) {
        WindowsComposeBridgeBindings.nativeHostSetPointerIcon(runtimeId, cursorType)
    }

    override fun updateTextInputGeometry(geometry: TextInputGeometry?) {
        if (geometry == null) {
            WindowsComposeBridgeBindings.nativeHostClearTextInputGeometry(runtimeId)
            return
        }
        WindowsComposeBridgeBindings.nativeHostUpdateTextInputGeometry(
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

    fun d3dAdapterPtr(): Long =
        WindowsComposeBridgeBindings.nativeHostD3DAdapterPtr(runtimeId)

    fun d3dDevicePtr(): Long =
        WindowsComposeBridgeBindings.nativeHostD3DDevicePtr(runtimeId)

    fun d3dQueuePtr(): Long =
        WindowsComposeBridgeBindings.nativeHostD3DQueuePtr(runtimeId)

    override fun acquireDrawableTexturePtr(): Long =
        WindowsComposeBridgeBindings.nativeHostAcquireDrawableTexturePtr(runtimeId)

    override fun presentDrawable() {
        WindowsComposeBridgeBindings.nativeHostPresentDrawable(runtimeId)
    }

    override fun performWindowDrag() {
        WindowsComposeBridgeBindings.nativeHostWindowDrag(runtimeId)
    }

    override fun minimizeWindow() {
        WindowsComposeBridgeBindings.nativeHostWindowMinimize(runtimeId)
    }

    override fun maximizeWindow() {
        WindowsComposeBridgeBindings.nativeHostWindowMaximize(runtimeId)
    }

    override fun closeWindow() {
        WindowsComposeBridgeBindings.nativeHostWindowClose(runtimeId)
    }

    override fun emitAppEvent(
        name: String,
        payload: String?,
    ) {
        WindowsComposeBridgeBindings.nativeHostEmitAppEvent(runtimeId, name, payload)
    }

    fun logPhaseTiming(name: String) {
        WindowsComposeBridgeBindings.nativeHostLogPhaseTiming(name)
    }

    override fun emitProfileFrameSample(
        refreshRate: Int,
        rendered: Boolean,
        dispatchDelayMicros: Int,
        inputDrainMicros: Int,
        acquireDrawableMicros: Int,
        sceneRenderMicros: Int,
        submitMicros: Int,
    ) {
        WindowsComposeBridgeBindings.nativeHostEmitProfileFrameSample(
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

internal object WindowsNativeBridgeLoader {
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
                    WindowsComposeBridgeBindings.nativeHostBridgeAvailable()
                } else {
                    WindowsComposeBridgeBindings.nativeHostBridgeAvailable()
                }
            }.getOrDefault(false)
            loadAttempted = true
            return available
        }
    }

    private const val BRIDGE_PATH_PROPERTY = "compose.native.host.bridge.path"
    private const val BRIDGE_PATH_ENV = "COMPOSE_NATIVE_HOST_BRIDGE_PATH"
}
