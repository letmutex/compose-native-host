package letmutex.compose.nativehost.internal

import letmutex.compose.nativehost.RenderFrameStats
import letmutex.compose.nativehost.WindowInfo
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Picture
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_CACHED_RENDER_SURFACES = 4

private data class CachedRenderSurface(
    val width: Int,
    val height: Int,
    val renderTarget: BackendRenderTarget,
    val surface: Surface,
)

internal fun interface RenderFrameCallback {
    fun onFrameRendered(
        refreshRate: Int,
        dispatchDelayNanos: Long,
        inputDrainNanos: Long,
        renderStats: RenderFrameStats,
    )
}

internal class ComposeMetalRenderThread(
    private val hostBridge: NativeHostBridge,
    private val renderFrameCallback: RenderFrameCallback,
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(true)
    private val ready = CountDownLatch(1)
    private val availableTasks = Semaphore(0)
    private val pendingLock = Any()
    private val surfaceProps = SurfaceProps()
    private val renderSurfaceCache =
        LinkedHashMap<Long, CachedRenderSurface>(MAX_CACHED_RENDER_SURFACES, 0.75f, true)

    @Volatile
    private var thread: Thread? = null

    private var context: DirectContext? = null
    private var pendingRenderRequest = PendingRenderRequest()
    private var renderingRenderRequest = PendingRenderRequest()
    private var hasPendingRenderRequest = false
    private var clearRenderSurfaceCachePending = false

    fun submitAsync(
        windowInfo: WindowInfo,
        picture: Picture,
        dispatchDelayNanos: Long,
        inputDrainNanos: Long,
        sceneRenderNanos: Long,
    ): Boolean {
        ensureStarted()
        if (!running.get()) {
            picture.close()
            return false
        }
        synchronized(pendingLock) {
            if (!running.get()) {
                picture.close()
                return false
            }
            val shouldSignal = !hasPendingRenderRequest
            pendingRenderRequest.picture?.close()
            pendingRenderRequest.set(
                windowInfo = windowInfo,
                picture = picture,
                dispatchDelayNanos = dispatchDelayNanos,
                inputDrainNanos = inputDrainNanos,
                sceneRenderNanos = sceneRenderNanos,
            )
            hasPendingRenderRequest = true
            if (shouldSignal) {
                availableTasks.release()
            }
            return true
        }
    }

    private fun renderPendingFrame(request: PendingRenderRequest) {
        val windowInfo = checkNotNull(request.windowInfo)
        val picture = checkNotNull(request.picture)
        val dispatchDelayNanos = request.dispatchDelayNanos
        val inputDrainNanos = request.inputDrainNanos
        val sceneRenderNanos = request.sceneRenderNanos
        val acquireStartNanos = System.nanoTime()
        val texturePtr = hostBridge.acquireDrawableTexturePtr()
        val submitStartNanos = System.nanoTime()
        val acquireDurationNanos = submitStartNanos - acquireStartNanos
        if (texturePtr == 0L) {
            picture.close()
            request.clear()
            renderFrameCallback.onFrameRendered(
                refreshRate = windowInfo.refreshRate,
                dispatchDelayNanos = dispatchDelayNanos,
                inputDrainNanos = inputDrainNanos,
                renderStats =
                    RenderFrameStats(
                        rendered = false,
                        acquireDrawableNanos = acquireDurationNanos,
                    ),
            )
            return
        }

        try {
            val cachedSurface = cachedRenderSurface(ensureContext(), windowInfo, texturePtr)
            cachedSurface.surface.canvas.clear(0x00000000)
            cachedSurface.surface.canvas.drawPicture(picture)
            cachedSurface.surface.flushAndSubmit()
            hostBridge.presentDrawable()
            renderFrameCallback.onFrameRendered(
                refreshRate = windowInfo.refreshRate,
                dispatchDelayNanos = dispatchDelayNanos,
                inputDrainNanos = inputDrainNanos,
                renderStats =
                    RenderFrameStats(
                        rendered = true,
                        acquireDrawableNanos = acquireDurationNanos,
                        sceneRenderNanos = sceneRenderNanos,
                        submitNanos = System.nanoTime() - submitStartNanos,
                    ),
            )
        } finally {
            picture.close()
            request.clear()
        }
    }

    private fun drainPendingWork() {
        while (true) {
            val shouldClearRenderSurfaceCache: Boolean
            val pendingRender: PendingRenderRequest?
            synchronized(pendingLock) {
                shouldClearRenderSurfaceCache = clearRenderSurfaceCachePending
                clearRenderSurfaceCachePending = false
                pendingRender =
                    if (hasPendingRenderRequest) {
                        val reusableRequest = renderingRenderRequest
                        renderingRenderRequest = pendingRenderRequest
                        pendingRenderRequest = reusableRequest
                        hasPendingRenderRequest = false
                        renderingRenderRequest
                    } else {
                        null
                    }
            }
            if (shouldClearRenderSurfaceCache) {
                clearRenderSurfaceCacheInternal()
            }
            if (pendingRender != null) {
                renderPendingFrame(pendingRender)
            }
            if (!shouldClearRenderSurfaceCache && pendingRender == null) {
                return
            }
        }
    }

    fun clearRenderSurfaceCache() {
        ensureStarted()
        if (!running.get()) {
            return
        }
        val shouldSignal: Boolean
        synchronized(pendingLock) {
            shouldSignal = !clearRenderSurfaceCachePending
            clearRenderSurfaceCachePending = true
        }
        if (shouldSignal) {
            availableTasks.release()
        }
    }

    override fun close() {
        if (!started.get() || !running.compareAndSet(true, false)) {
            return
        }
        availableTasks.release()
    }

    private fun ensureStarted() {
        if (ready.count == 0L) return

        if (started.compareAndSet(false, true)) {
            val newThread =
                Thread(::runLoop, "ComposeNativeHost-Render").apply {
                    isDaemon = true
                }
            thread = newThread
            newThread.start()
        }
        ready.await()
    }

    private fun runLoop() {
        ready.countDown()
        while (running.get()) {
            try {
                availableTasks.acquire()
                availableTasks.drainPermits()
                try {
                    drainPendingWork()
                } catch (t: Throwable) {
                    System.err.println("ComposeNativeHost render thread task failed")
                    t.printStackTrace(System.err)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!running.get()) {
                    break
                }
            }
        }
        synchronized(pendingLock) {
            pendingRenderRequest.picture?.close()
            pendingRenderRequest.clear()
            renderingRenderRequest.picture?.close()
            renderingRenderRequest.clear()
            hasPendingRenderRequest = false
            clearRenderSurfaceCachePending = false
        }
        clearRenderSurfaceCacheInternal()
        context?.abandon()
        context?.close()
        context = null
        thread = null
    }

    private fun ensureContext(): DirectContext {
        val existingContext = context
        if (existingContext != null) {
            return existingContext
        }
        val devicePtr = hostBridge.getDevicePtr()
        val queuePtr = hostBridge.getQueuePtr()
        check(devicePtr != 0L) { "Metal device pointer is unavailable" }
        check(queuePtr != 0L) { "Metal queue pointer is unavailable" }
        return DirectContext.makeMetal(devicePtr, queuePtr).also {
            context = it
        }
    }

    private fun cachedRenderSurface(
        context: DirectContext,
        windowInfo: WindowInfo,
        texturePtr: Long,
    ): CachedRenderSurface {
        val cached = renderSurfaceCache[texturePtr]
        if (cached != null &&
            cached.width == windowInfo.width &&
            cached.height == windowInfo.height
        ) {
            return cached
        }

        if (cached != null) {
            disposeCachedRenderSurface(cached)
            renderSurfaceCache.remove(texturePtr)
        }

        val renderTarget =
            BackendRenderTarget.makeMetal(windowInfo.width, windowInfo.height, texturePtr)
        val surface =
            Surface.makeFromBackendRenderTarget(
                context = context,
                rt = renderTarget,
                origin = SurfaceOrigin.TOP_LEFT,
                colorFormat = SurfaceColorFormat.BGRA_8888,
                colorSpace = ColorSpace.sRGB,
                surfaceProps = surfaceProps,
            ) ?: error("Failed to create a Metal-backed surface")
        val created =
            CachedRenderSurface(
                width = windowInfo.width,
                height = windowInfo.height,
                renderTarget = renderTarget,
                surface = surface,
            )
        renderSurfaceCache[texturePtr] = created
        trimRenderSurfaceCache()
        return created
    }

    private fun trimRenderSurfaceCache() {
        while (renderSurfaceCache.size > MAX_CACHED_RENDER_SURFACES) {
            val eldestKey = renderSurfaceCache.entries.firstOrNull()?.key ?: return
            val eldest = renderSurfaceCache.remove(eldestKey) ?: return
            disposeCachedRenderSurface(eldest)
        }
    }

    private fun clearRenderSurfaceCacheInternal() {
        renderSurfaceCache.values.forEach(::disposeCachedRenderSurface)
        renderSurfaceCache.clear()
    }

    private fun disposeCachedRenderSurface(cached: CachedRenderSurface) {
        cached.surface.close()
        cached.renderTarget.close()
    }
}

private class PendingRenderRequest {
    var windowInfo: WindowInfo? = null
    var picture: Picture? = null
    var dispatchDelayNanos: Long = 0L
    var inputDrainNanos: Long = 0L
    var sceneRenderNanos: Long = 0L

    fun set(
        windowInfo: WindowInfo,
        picture: Picture,
        dispatchDelayNanos: Long,
        inputDrainNanos: Long,
        sceneRenderNanos: Long,
    ) {
        this.windowInfo = windowInfo
        this.picture = picture
        this.dispatchDelayNanos = dispatchDelayNanos
        this.inputDrainNanos = inputDrainNanos
        this.sceneRenderNanos = sceneRenderNanos
    }

    fun clear() {
        windowInfo = null
        picture = null
        dispatchDelayNanos = 0L
        inputDrainNanos = 0L
        sceneRenderNanos = 0L
    }
}
