package letmutex.compose.nativehost

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.geometry.Offset

internal enum class NativeHostDragDataKind {
    FilesList,
    Text,
    Image,
}

internal data class NativeHostDragData(
    val kind: NativeHostDragDataKind? = null,
    val files: List<String> = emptyList(),
    val text: String? = null,
    val imageBytes: ByteArray? = null,
    val imageFormat: String? = null,
) {
    val hasPayload: Boolean
        get() = files.isNotEmpty() || text != null || imageBytes != null
}

internal data class NativeHostDragAndDropEvent(
    val positionInRoot: Offset,
    val data: NativeHostDragData,
)

@OptIn(ExperimentalComposeUiApi::class)
internal fun DragAndDropEvent.nativeHostDragData(): NativeHostDragData? =
    (nativeEvent as? NativeHostDragAndDropEvent)?.data

@OptIn(ExperimentalComposeUiApi::class)
internal fun DragAndDropEvent.nativeHostPositionInRoot(): Offset? =
    (nativeEvent as? NativeHostDragAndDropEvent)?.positionInRoot
