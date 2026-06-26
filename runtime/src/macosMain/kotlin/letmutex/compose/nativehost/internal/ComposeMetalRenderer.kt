package letmutex.compose.nativehost.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import letmutex.compose.nativehost.NativeHostDragAndDropEvent
import letmutex.compose.nativehost.NativeHostDragData
import letmutex.compose.nativehost.dragAndDropActionFromRaw
import letmutex.compose.nativehost.RenderFrameStats
import letmutex.compose.nativehost.WindowInfo
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal class ComposeMetalRenderer(
    private val hostBridge: NativeHostBridge,
    private val renderFrameCallback: RenderFrameCallback,
    private val content: @Composable () -> Unit,
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
) : NativeHostRenderer {
    private val platformContext = ComposePlatformContext(hostBridge)
    private val renderThread = ComposeMetalRenderThread(hostBridge, renderFrameCallback)
    private var scene: ComposeScene? = null
    private var currentWindowInfo: WindowInfo? = null
    private var activeExternalDragAction: DragAndDropTransferAction? = null
    private var activeExternalDragPayload: NativeHostDragData? = null
    private var activeExternalDragPosition: Offset = Offset.Zero
    private var activeExternalDragTimestampMillis: Long = 0L
    private val recorder = PictureRecorder()
    private var pictureBounds = Rect.makeWH(0f, 0f)

    init {
        platformContext.setTextInputGeometryListener(hostBridge::updateTextInputGeometry)
    }

    override fun dispatchInputBatch(
        windowInfo: WindowInfo,
        records: LongArray,
        eventCount: Int,
        texts: Array<String?>,
    ) {
        platformContext.updateWindowInfo(windowInfo)
        val activeScene = ensureScene(windowInfo)
        dispatchMacOsInputEvents(records, eventCount, texts, activeScene, platformContext)
    }

    override fun render(
        windowInfo: WindowInfo,
        nanoTime: Long,
        dispatchDelayNanos: Long,
        inputDrainNanos: Long,
    ): Boolean {
        platformContext.updateWindowInfo(windowInfo)
        val activeScene = ensureScene(windowInfo)
        if (!activeScene.hasInvalidations()) {
            return false
        }
        val renderStartNanos = System.nanoTime()
        if (pictureBounds.width.toInt() != windowInfo.width
            || pictureBounds.height.toInt() != windowInfo.height
        ) {
            pictureBounds = Rect.makeWH(windowInfo.width.toFloat(), windowInfo.height.toFloat())
        }
        val recordingCanvas = recorder.beginRecording(bounds = pictureBounds)
        activeScene.render(recordingCanvas.asComposeCanvas(), nanoTime)
        val picture = recorder.finishRecordingAsPicture()
        val renderDurationNanos = System.nanoTime() - renderStartNanos

        if (activeScene.hasInvalidations()) {
            hostBridge.requestRender()
        }
        return renderThread.submitAsync(
            windowInfo = windowInfo,
            picture = picture,
            dispatchDelayNanos = dispatchDelayNanos,
            inputDrainNanos = inputDrainNanos,
            sceneRenderNanos = renderDurationNanos,
        )
    }

    override fun handleExternalDragEntered(
        windowInfo: WindowInfo,
        positionInRootX: Float,
        positionInRootY: Float,
        actionRaw: Int,
        timestampMillis: Long,
        payload: Any,
    ): Boolean {
        val dragData = payload as NativeHostDragData
        val action = dragAndDropActionFromRaw(actionRaw)
        val positionInRoot = Offset(positionInRootX, positionInRootY)
        val activeScene = ensureScene(windowInfo)
        val event = externalDragEvent(positionInRoot, action, timestampMillis, dragData)
        val rootNode = activeScene.rootDragAndDropNode
        val accepted = rootNode.acceptDragAndDropTransfer(event)
        if (!accepted) {
            clearExternalDragState()
            return false
        }
        rootNode.onStarted(event)
        rootNode.onEntered(event)
        updateExternalDragState(positionInRoot, action, timestampMillis, dragData)
        hostBridge.requestRender()
        return rootNode.hasEligibleDropTarget
    }

    override fun handleExternalDragMoved(
        windowInfo: WindowInfo,
        positionInRootX: Float,
        positionInRootY: Float,
        actionRaw: Int,
        timestampMillis: Long,
        payload: Any,
    ): Boolean {
        val dragData = payload as NativeHostDragData
        val action = dragAndDropActionFromRaw(actionRaw)
        val positionInRoot = Offset(positionInRootX, positionInRootY)
        val activeScene = ensureScene(windowInfo)
        val event = externalDragEvent(positionInRoot, action, timestampMillis, dragData)
        val rootNode = activeScene.rootDragAndDropNode
        if (activeExternalDragPayload == null) {
            return handleExternalDragEntered(
                windowInfo = windowInfo,
                positionInRootX = positionInRootX,
                positionInRootY = positionInRootY,
                actionRaw = actionRaw,
                timestampMillis = timestampMillis,
                payload = payload,
            )
        }
        if (activeExternalDragAction != action) {
            rootNode.onChanged(event)
        }
        rootNode.onMoved(event)
        updateExternalDragState(positionInRoot, action, timestampMillis, dragData)
        hostBridge.requestRender()
        return rootNode.hasEligibleDropTarget
    }

    override fun handleExternalDragExited(windowInfo: WindowInfo) {
        if (activeExternalDragPayload == null) {
            return
        }
        val activeScene = ensureScene(windowInfo)
        val event =
            externalDragEvent(
                positionInRoot = activeExternalDragPosition,
                action = activeExternalDragAction,
                timestampMillis = activeExternalDragTimestampMillis,
                payload = checkNotNull(activeExternalDragPayload),
            )
        val rootNode = activeScene.rootDragAndDropNode
        rootNode.onExited(event)
        rootNode.onEnded(event)
        clearExternalDragState()
        hostBridge.requestRender()
    }

    override fun handleExternalDragEnded(windowInfo: WindowInfo) {
        if (activeExternalDragPayload == null) {
            return
        }
        val activeScene = ensureScene(windowInfo)
        val event =
            externalDragEvent(
                positionInRoot = activeExternalDragPosition,
                action = activeExternalDragAction,
                timestampMillis = activeExternalDragTimestampMillis,
                payload = checkNotNull(activeExternalDragPayload),
            )
        activeScene.rootDragAndDropNode.onEnded(event)
        clearExternalDragState()
        hostBridge.requestRender()
    }

    override fun handleExternalDrop(
        windowInfo: WindowInfo,
        positionInRootX: Float,
        positionInRootY: Float,
        actionRaw: Int,
        timestampMillis: Long,
        payload: Any,
    ): Boolean {
        val dragData = payload as NativeHostDragData
        val action = dragAndDropActionFromRaw(actionRaw)
        val positionInRoot = Offset(positionInRootX, positionInRootY)
        val activeScene = ensureScene(windowInfo)
        val event = externalDragEvent(positionInRoot, action, timestampMillis, dragData)
        val rootNode = activeScene.rootDragAndDropNode
        if (activeExternalDragPayload == null) {
            val accepted = rootNode.acceptDragAndDropTransfer(event)
            if (!accepted) {
                clearExternalDragState()
                return false
            }
            rootNode.onStarted(event)
            rootNode.onEntered(event)
        }
        val dropAccepted = rootNode.onDrop(event)
        rootNode.onEnded(event)
        clearExternalDragState()
        hostBridge.requestRender()
        return dropAccepted
    }

    override fun close() {
        renderThread.close()
        scene?.close()
        recorder.close()
        scene = null
        currentWindowInfo = null
    }

    private fun ensureScene(windowInfo: WindowInfo): ComposeScene {
        val existingScene = scene
        if (existingScene != null) {
            if (currentWindowInfo != windowInfo) {
                if (
                    currentWindowInfo?.width != windowInfo.width ||
                    currentWindowInfo?.height != windowInfo.height
                ) {
                    renderThread.clearRenderSurfaceCache()
                }
                existingScene.density = Density(windowInfo.scale)
                existingScene.size = IntSize(windowInfo.width, windowInfo.height)
                currentWindowInfo = windowInfo
            }
            return existingScene
        }
        return CanvasLayersComposeScene(
            density = Density(windowInfo.scale),
            layoutDirection = LayoutDirection.Ltr,
            size = IntSize(windowInfo.width, windowInfo.height),
            coroutineContext = coroutineContext,
            platformContext = platformContext,
            invalidate = hostBridge::requestRender,
        ).also { composeScene ->
            composeScene.setContent {
                content()
            }
            scene = composeScene
            currentWindowInfo = windowInfo
        }
    }

    private fun externalDragEvent(
        positionInRoot: Offset,
        action: DragAndDropTransferAction?,
        timestampMillis: Long,
        payload: NativeHostDragData,
    ): DragAndDropEvent =
        DragAndDropEvent(
            action = action,
            nativeEvent =
                NativeHostDragAndDropEvent(
                    positionInRoot = positionInRoot,
                    data = payload,
                ),
            positionInRootImpl = positionInRoot,
        )

    private fun updateExternalDragState(
        positionInRoot: Offset,
        action: DragAndDropTransferAction?,
        timestampMillis: Long,
        payload: NativeHostDragData,
    ) {
        activeExternalDragAction = action
        activeExternalDragPayload = payload
        activeExternalDragPosition = positionInRoot
        activeExternalDragTimestampMillis = timestampMillis
    }

    private fun clearExternalDragState() {
        activeExternalDragAction = null
        activeExternalDragPayload = null
        activeExternalDragPosition = Offset.Zero
        activeExternalDragTimestampMillis = 0L
    }

}

internal fun createComposeMetalRenderer(
    hostBridge: NativeHostBridge,
    renderFrameCallback: RenderFrameCallback,
    content: @Composable () -> Unit,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
): ComposeMetalRenderer =
    ComposeMetalRenderer(hostBridge, renderFrameCallback, content, coroutineContext)
