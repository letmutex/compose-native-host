package letmutex.compose.nativehost

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.geometry.Offset

@OptIn(ExperimentalComposeUiApi::class)
fun dragAndDropActionFromRaw(rawValue: Int): DragAndDropTransferAction? =
    when (rawValue) {
        2 -> DragAndDropTransferAction.Move
        3 -> DragAndDropTransferAction.Link
        1 -> DragAndDropTransferAction.Copy
        else -> null
    }

enum class NativeHostDragDataKind {
    FilesList,
    Text,
    Image,
}

data class NativeHostDragData(
    val kind: NativeHostDragDataKind? = null,
    val files: List<String> = emptyList(),
    val text: String? = null,
    val imageBytes: ByteArray? = null,
    val imageFormat: String? = null,
) {
    val hasPayload: Boolean
        get() = files.isNotEmpty() || text != null || imageBytes != null
}

data class NativeHostDragAndDropEvent(
    val positionInRoot: Offset,
    val data: NativeHostDragData,
)

@OptIn(ExperimentalComposeUiApi::class)
internal fun DragAndDropEvent.nativeHostDragData(): NativeHostDragData? =
    (nativeEvent as? NativeHostDragAndDropEvent)?.data

@OptIn(ExperimentalComposeUiApi::class)
internal fun DragAndDropEvent.nativeHostPositionInRoot(): Offset? =
    (nativeEvent as? NativeHostDragAndDropEvent)?.positionInRoot
