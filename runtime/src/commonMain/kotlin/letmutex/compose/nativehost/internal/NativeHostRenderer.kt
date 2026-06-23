package letmutex.compose.nativehost.internal

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.geometry.Offset
import letmutex.compose.nativehost.NativeHostDragData
import letmutex.compose.nativehost.WindowInfo

@OptIn(ExperimentalComposeUiApi::class)
interface NativeHostRenderer : AutoCloseable {
    fun dispatchInputBatch(
        windowInfo: WindowInfo,
        records: LongArray,
        eventCount: Int,
        texts: Array<String?>,
    )

    fun render(
        windowInfo: WindowInfo,
        nanoTime: Long,
        dispatchDelayNanos: Long,
        inputDrainNanos: Long,
    ): Boolean

    fun handleExternalDragEntered(
        windowInfo: WindowInfo,
        positionInRootX: Float,
        positionInRootY: Float,
        actionRaw: Int,
        timestampMillis: Long,
        payload: Any,
    ): Boolean

    fun handleExternalDragMoved(
        windowInfo: WindowInfo,
        positionInRootX: Float,
        positionInRootY: Float,
        actionRaw: Int,
        timestampMillis: Long,
        payload: Any,
    ): Boolean

    fun handleExternalDragExited(windowInfo: WindowInfo)

    fun handleExternalDragEnded(windowInfo: WindowInfo)

    fun handleExternalDrop(
        windowInfo: WindowInfo,
        positionInRootX: Float,
        positionInRootY: Float,
        actionRaw: Int,
        timestampMillis: Long,
        payload: Any,
    ): Boolean
}
