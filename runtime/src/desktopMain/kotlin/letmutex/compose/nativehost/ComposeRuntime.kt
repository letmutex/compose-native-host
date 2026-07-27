package letmutex.compose.nativehost

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import letmutex.compose.nativehost.diagnostics.FrameStallWatchdog
import letmutex.compose.nativehost.diagnostics.FrameTimingLogger
import letmutex.compose.nativehost.diagnostics.VsyncDelayAnalyzer
import letmutex.compose.nativehost.internal.NativeHostBridge
import letmutex.compose.nativehost.internal.NativeHostRenderer
import letmutex.compose.nativehost.internal.NativeHostUriHandler
import letmutex.compose.nativehost.internal.RenderFrameCallback
import java.lang.ThreadLocal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-window host runtime backed by the shared native host environment.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ComposeRuntime(
    private val runtimeId: Long,
    private val profileRenderingEnabled: Boolean,
) {
    companion object {
        private val currentRuntime = ThreadLocal<ComposeRuntime>()

        private val frameTimingLoggingEnabled =
            System.getenv("COMPOSE_NATIVE_HOST_FRAME_TIMINGS") == "1"
        private val vsyncDelayAnalysisEnabled =
            System.getenv("COMPOSE_NATIVE_HOST_VSYNC_DELAY") == "1"

        @JvmStatic
        @JvmName("initialize")
        fun initialize() {
            NativeHostUiThread.shared.ensureStarted()
        }

        @JvmStatic
        @JvmName("enterCurrentRuntime")
        fun enterCurrentRuntime(runtime: ComposeRuntime) {
            currentRuntime.set(runtime)
        }

        @JvmStatic
        @JvmName("exitCurrentRuntime")
        fun exitCurrentRuntime() {
            currentRuntime.remove()
        }

        internal fun bindCurrentRuntime(content: @Composable ComposeNativeHostScope.() -> Unit) {
            val runtime = currentRuntime.get()
                ?: error("ComposeNativeHost must be called from the current hosted runtime entry point.")
            runtime.bindContent(content)
        }
    }

    private val bridge: NativeHostBridge = createPlatformBridge(runtimeId)
    private val framePending = AtomicBoolean(false)
    private val frameLoopScheduled = AtomicBoolean(false)
    private val renderInFlight = AtomicBoolean(false)
    private val submittedRenderCount = AtomicInteger(0)
    private val pendingFrameVsyncNanos = AtomicLong(0L)
    private val lastFrameRequestNanos = AtomicLong(System.nanoTime())
    private val lastFrameStartNanos = AtomicLong(0L)
    private val lastFrameCompleteNanos = AtomicLong(System.nanoTime())
    private val frameTimingLogger = if (frameTimingLoggingEnabled) FrameTimingLogger() else null
    private val vsyncDelayAnalyzer = if (vsyncDelayAnalysisEnabled) VsyncDelayAnalyzer() else null
    private val frameStallWatchdog = FrameStallWatchdog(
        framePending = framePending,
        frameLoopScheduled = frameLoopScheduled,
        renderInFlight = renderInFlight,
        lastFrameRequestNanos = lastFrameRequestNanos,
        lastFrameStartNanos = lastFrameStartNanos,
        lastFrameCompleteNanos = lastFrameCompleteNanos,
        dumpState = { NativeHostUiThread.shared.dumpState() },
    )
    private val scope = ComposeNativeHostScopeImpl(object : ComposeNativeHostEventDispatcher {
        override fun sendEvent(name: String, payload: String?) {
            bridge.emitAppEvent(name, payload)
        }
        override fun performWindowDrag() = bridge.performWindowDrag()
        override fun minimizeWindow() = bridge.minimizeWindow()
        override fun maximizeWindow() = bridge.maximizeWindow()
        override fun closeWindow() = bridge.closeWindow()
    })
    private var boundContent: (@Composable ComposeNativeHostScope.() -> Unit)? = null
    private var initialized = false
    private var renderer: NativeHostRenderer? = null
    private val renderFrameCallback =
        RenderFrameCallback { refreshRate, dispatchDelayNanos, inputDrainNanos, renderStats ->
            handleCompletedRenderFrame(
                refreshRate = refreshRate,
                dispatchDelayNanos = dispatchDelayNanos,
                inputDrainNanos = inputDrainNanos,
                renderStats = renderStats,
            )
        }

    @JvmName("startRuntime")
    fun startRuntime() {
        check(boundContent != null) {
            "Hosted runtime entry point must call ComposeNativeHost { ... }."
        }
        if (ensureInitialized()) {
            bridge.requestRender()
        }
    }

    @JvmName("isContentBound")
    fun isContentBound(): Boolean = boundContent != null

    @JvmName("requestFrame")
    fun requestFrame(vsyncNanos: Long) {
        if (!ensureInitialized()) return
        val renderSignalNanos = System.nanoTime()
        pendingFrameVsyncNanos.set(vsyncNanos)
        lastFrameRequestNanos.set(renderSignalNanos)
        vsyncDelayAnalyzer?.record(
            vsyncNanos = vsyncNanos,
            renderSignalNanos = renderSignalNanos,
        )
        val wasFramePending = framePending.getAndSet(true)
        if (!wasFramePending) {
            frameStallWatchdog.onFrameRequested()
        }
        schedulePendingFrameLoopIfReady()
    }

    @JvmName("handleExternalDragEntered")
    fun handleExternalDragEntered(
        x: Int,
        y: Int,
        action: Int,
        payloadKind: Int,
        timestampMillis: Long,
        files: Array<String>,
        text: String?,
        imageBytes: ByteArray?,
        imageFormat: String?,
    ): Boolean {
        if (!ensureInitialized()) return false
        val renderer = renderer ?: return false
        val windowInfo = bridge.currentWindowInfo() ?: return false
        return NativeHostUiThread.shared.callOnUiThread {
            scope.windowInfo.value = windowInfo
            renderer.handleExternalDragEntered(
                windowInfo = windowInfo,
                positionInRootX = x.toFloat(),
                positionInRootY = y.toFloat(),
                actionRaw = action,
                timestampMillis = timestampMillis,
                payload = externalDragPayload(payloadKind, files, text, imageBytes, imageFormat),
            )
        }
    }

    @JvmName("handleExternalDragMoved")
    fun handleExternalDragMoved(
        x: Int,
        y: Int,
        action: Int,
        payloadKind: Int,
        timestampMillis: Long,
        files: Array<String>,
        text: String?,
        imageBytes: ByteArray?,
        imageFormat: String?,
    ): Boolean {
        if (!ensureInitialized()) return false
        val renderer = renderer ?: return false
        val windowInfo = bridge.currentWindowInfo() ?: return false
        return NativeHostUiThread.shared.callOnUiThread {
            scope.windowInfo.value = windowInfo
            renderer.handleExternalDragMoved(
                windowInfo = windowInfo,
                positionInRootX = x.toFloat(),
                positionInRootY = y.toFloat(),
                actionRaw = action,
                timestampMillis = timestampMillis,
                payload = externalDragPayload(payloadKind, files, text, imageBytes, imageFormat),
            )
        }
    }

    @JvmName("handleExternalDragExited")
    fun handleExternalDragExited() {
        if (!ensureInitialized()) return
        val renderer = renderer ?: return
        val windowInfo = bridge.currentWindowInfo() ?: return
        NativeHostUiThread.shared.callOnUiThread {
            scope.windowInfo.value = windowInfo
            renderer.handleExternalDragExited(windowInfo)
        }
    }

    @JvmName("handleExternalDragEnded")
    fun handleExternalDragEnded() {
        if (!ensureInitialized()) return
        val renderer = renderer ?: return
        val windowInfo = bridge.currentWindowInfo() ?: return
        NativeHostUiThread.shared.callOnUiThread {
            scope.windowInfo.value = windowInfo
            renderer.handleExternalDragEnded(windowInfo)
        }
    }

    @JvmName("handleExternalDrop")
    fun handleExternalDrop(
        x: Int,
        y: Int,
        action: Int,
        payloadKind: Int,
        timestampMillis: Long,
        files: Array<String>,
        text: String?,
        imageBytes: ByteArray?,
        imageFormat: String?,
    ): Boolean {
        if (!ensureInitialized()) return false
        val renderer = renderer ?: return false
        val windowInfo = bridge.currentWindowInfo() ?: return false
        return NativeHostUiThread.shared.callOnUiThread {
            scope.windowInfo.value = windowInfo
            renderer.handleExternalDrop(
                windowInfo = windowInfo,
                positionInRootX = x.toFloat(),
                positionInRootY = y.toFloat(),
                actionRaw = action,
                timestampMillis = timestampMillis,
                payload = externalDragPayload(payloadKind, files, text, imageBytes, imageFormat),
            )
        }
    }

    @JvmName("closeRuntime")
    fun closeRuntime() {
        val rendererToClose: NativeHostRenderer?
        val shouldCloseRenderer = synchronized(this) {
            if (!initialized) {
                rendererToClose = null
                false
            } else {
                initialized = false
                rendererToClose = renderer
                renderer = null
                true
            }
        }
        if (shouldCloseRenderer) {
            NativeHostUiThread.shared.callOnUiThread {
                submittedRenderCount.set(0)
                renderInFlight.set(false)
                logFrameTimingSummary()
                frameTimingLogger?.close()
                frameStallWatchdog.close()
                vsyncDelayAnalyzer?.close()
                rendererToClose?.close()
            }
        }
    }

    private fun bindContent(content: @Composable ComposeNativeHostScope.() -> Unit) {
        synchronized(this) {
            check(!initialized) {
                "ComposeNativeHost content is already bound for this runtime."
            }
            boundContent = content
        }
    }

    private fun ensureInitialized(): Boolean {
        if (initialized) {
            return true
        }
        synchronized(this) {
            if (initialized) {
                return true
            }
            val boundContent = boundContent ?: return false
            renderer = createPlatformRenderer(
                hostBridge = bridge,
                renderFrameCallback = renderFrameCallback,
                coroutineContext = NativeHostUiThread.shared.dispatcher,
                content = {
                    RenderContent(scope, boundContent)
                }
            )
            NativeHostUiThread.shared.ensureStarted()
            initialized = true
            return true
        }
    }

    private fun schedulePendingFrameLoopIfReady() {
        if (
            framePending.get() &&
            frameLoopScheduled.compareAndSet(false, true)
        ) {
            NativeHostUiThread.shared.post(::processPendingFrames)
        }
    }

    private fun processPendingFrames() {
        try {
            framePending.getAndSet(false)
            doRenderFrame()
            frameLoopScheduled.set(false)
            schedulePendingFrameLoopIfReady()
        } catch (t: Throwable) {
            submittedRenderCount.set(0)
            renderInFlight.set(false)
            frameLoopScheduled.set(false)
            frameStallWatchdog.onFrameStateChanged()
            System.err.println("ComposeNativeHost frame loop failed")
            t.printStackTrace(System.err)
        }
    }

    private fun doRenderFrame() {
        val renderStartNanos = System.nanoTime()
        val dispatchDelayNanos =
            (renderStartNanos - pendingFrameVsyncNanos.get()).coerceAtLeast(0L)
        lastFrameStartNanos.set(renderStartNanos)

        val frameState = bridge.pollFrameState() ?: return
        val windowInfo = WindowInfo.fromPacked(frameState.windowInfo) ?: return
        val renderer = renderer ?: return

        scope.windowInfo.value = windowInfo
        if (frameState.eventCount > 0) {
            var pendingFrameState = frameState
            do {
                renderer.dispatchInputBatch(
                    windowInfo = windowInfo,
                    records = pendingFrameState.records,
                    eventCount = pendingFrameState.eventCount,
                    texts = pendingFrameState.texts,
                )
                pendingFrameState = bridge.pollFrameState() ?: break
            } while (pendingFrameState.eventCount > 0)
        }

        val nowNanos = System.nanoTime()
        val inputDrainDurationNanos = nowNanos - renderStartNanos

        beginRenderSubmission()
        val rendered = renderer.render(
            windowInfo = windowInfo,
            nanoTime = nowNanos,
            dispatchDelayNanos = dispatchDelayNanos,
            inputDrainNanos = inputDrainDurationNanos,
        )
        if (!rendered) {
            completeRenderSubmission()
        }
    }

    private fun handleCompletedRenderFrame(
        refreshRate: Int,
        dispatchDelayNanos: Long,
        inputDrainNanos: Long,
        renderStats: RenderFrameStats,
    ) {
        completeRenderSubmission()
        lastFrameCompleteNanos.set(System.nanoTime())
        schedulePendingFrameLoopIfReady()
        frameStallWatchdog.onFrameStateChanged()
        recordFrameTiming(
            dispatchDelayNanos = dispatchDelayNanos,
            inputDrainNanos = inputDrainNanos,
            renderStats = renderStats,
        )
        emitProfileFrameSample(
            refreshRate = refreshRate,
            dispatchDelayNanos = dispatchDelayNanos,
            inputDrainNanos = inputDrainNanos,
            renderStats = renderStats,
        )
    }

    /**
     * The render thread may keep one queued picture while another is being submitted to the GPU.
     * Count accepted submissions so a coalesced picture can complete independently of the one ahead
     * of it.
     */
    private fun beginRenderSubmission() {
        submittedRenderCount.incrementAndGet()
        renderInFlight.set(true)
    }

    private fun completeRenderSubmission() {
        val remaining = submittedRenderCount.updateAndGet { count ->
            if (count > 0) count - 1 else 0
        }
        renderInFlight.set(remaining > 0)
    }

    private fun emitProfileFrameSample(
        refreshRate: Int,
        dispatchDelayNanos: Long,
        inputDrainNanos: Long,
        renderStats: RenderFrameStats,
    ) {
        if (!profileRenderingEnabled || !renderStats.rendered) {
            return
        }
        bridge.emitProfileFrameSample(
            refreshRate = refreshRate,
            rendered = renderStats.rendered,
            dispatchDelayMicros = dispatchDelayNanos.toMicrosInt(),
            inputDrainMicros = inputDrainNanos.toMicrosInt(),
            acquireDrawableMicros = renderStats.acquireDrawableMicros,
            sceneRenderMicros = renderStats.sceneRenderMicros,
            submitMicros = renderStats.submitMicros,
        )
    }

    private fun recordFrameTiming(
        dispatchDelayNanos: Long,
        inputDrainNanos: Long,
        renderStats: RenderFrameStats,
    ) {
        if (!frameTimingLoggingEnabled || !renderStats.rendered) {
            return
        }
        val frameTimingLogger = frameTimingLogger ?: return
        frameTimingLogger.record(
            dispatchDelayNanos = dispatchDelayNanos,
            inputDrainNanos = inputDrainNanos,
            acquireDrawableNanos = renderStats.acquireDrawableNanos,
            sceneRenderNanos = renderStats.sceneRenderNanos,
            submitNanos = renderStats.submitNanos,
        )
        if (frameTimingLogger.shouldLogIntervalSummary()) {
            logFrameTimingSummary()
        }
    }

    private fun logFrameTimingSummary() {
        if (!frameTimingLoggingEnabled) {
            return
        }
        val frameTimingLogger = frameTimingLogger ?: return
        if (!frameTimingLogger.hasSamples()) {
            return
        }
        frameTimingLogger.enqueueSummary()
    }
}

private fun Long.toMicrosInt(): Int =
    (this / 1_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

@Composable
private fun RenderContent(
    scope: ComposeNativeHostScope,
    content: @Composable ComposeNativeHostScope.() -> Unit,
) {
    if (isComposeNativeHostSharedLibraryRuntime()) {
        CompositionLocalProvider(
            LocalComposeNativeHostHandle provides scope.host,
            LocalComposeNativeHostWindowInfo provides scope.windowInfo.value,
            LocalUriHandler provides NativeHostUriHandler,
        ) {
            content(scope)
        }
    } else {
        CompositionLocalProvider(
            LocalComposeNativeHostHandle provides scope.host,
            LocalComposeNativeHostWindowInfo provides scope.windowInfo.value
        ) {
            content(scope)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)


private fun dragDataKindFromRaw(rawValue: Int): NativeHostDragDataKind? =
    when (rawValue) {
        1 -> NativeHostDragDataKind.FilesList
        2 -> NativeHostDragDataKind.Text
        3 -> NativeHostDragDataKind.Image
        else -> null
    }

private fun externalDragPayload(
    kindRaw: Int,
    files: Array<String>,
    text: String?,
    imageBytes: ByteArray?,
    imageFormat: String?,
): NativeHostDragData =
    NativeHostDragData(
        kind = dragDataKindFromRaw(kindRaw),
        files = files.toList(),
        text = text,
        imageBytes = imageBytes?.takeIf { it.isNotEmpty() },
        imageFormat = imageFormat,
    )
