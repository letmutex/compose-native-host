@file:OptIn(ExperimentalComposeUiApi::class)

/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.draganddrop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.DataFlavor.selectBestTextFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.image.BufferedImage
import java.io.File
import letmutex.compose.nativehost.NativeHostDragAndDropEvent
import letmutex.compose.nativehost.NativeHostDragData
import letmutex.compose.nativehost.NativeHostDragDataKind
import org.jetbrains.skia.Image as SkiaImage

/**
 * Encapsulates the information needed to start a drag-and-drop session from Compose on the desktop.
 */
class DragAndDropTransferData @ExperimentalComposeUiApi constructor(
    /**
     * The object being transferred during a drag-and-drop gesture.
     */
    @property:ExperimentalComposeUiApi
    val transferable: DragAndDropTransferable,
    /**
     * The transfer actions supported by the source of the drag-and-drop session.
     */
    @property:ExperimentalComposeUiApi
    val supportedActions: Iterable<DragAndDropTransferAction>,
    /**
     * The offset of the pointer relative to the drag decoration.
     */
    @property:ExperimentalComposeUiApi
    val dragDecorationOffset: Offset = Offset.Zero,
    /**
     * Invoked when the drag-and-drop gesture completes.
     *
     * The argument to the callback specifies the transfer action with which the gesture completed,
     * or `null` if the gesture did not complete successfully.
     */
    @property:ExperimentalComposeUiApi
    val onTransferCompleted: ((userAction: DragAndDropTransferAction?) -> Unit)? = null,
) {
    init {
        require(supportedActions.firstOrNull() != null) { "supportedActions may not be empty" }
    }
}

/**
 * Represents the actual object transferred during a drag-and-drop.
 */
@ExperimentalComposeUiApi
interface DragAndDropTransferable

/**
 * The possible actions on the transferred object in a drag-and-drop session.
 */
@ExperimentalComposeUiApi
class DragAndDropTransferAction private constructor(private val name: String) {
    override fun toString(): String = name

    companion object {
        /**
         * Indicates the dragged object should be copied into the target.
         */
        val Copy = DragAndDropTransferAction("Copy")

        /**
         * Indicates the dragged object should be moved ("cut" and "pasted") into the target.
         */
        val Move = DragAndDropTransferAction("Move")

        /**
         * Indicates the dragged object should be linked to at the target.
         */
        val Link = DragAndDropTransferAction("Link")
    }
}

/**
 * The event dispatched to [DragAndDropTarget] implementations during a drag-and-drop session.
 */
class DragAndDropEvent @ExperimentalComposeUiApi constructor(
    /**
     * The action currently selected by the user.
     */
    @property:ExperimentalComposeUiApi
    val action: DragAndDropTransferAction?,
    /**
     * The underlying native event.
     */
    @property:ExperimentalComposeUiApi
    val nativeEvent: Any?,
    /**
     * The position of the dragged object relative to the root Compose container.
     */
    internal val positionInRootImpl: Offset,
)

/**
 * The base class for [DragAndDropTransferable] for AWT that simply wraps an AWT [Transferable]
 * instance.
 */
internal interface AwtDragAndDropTransferable : DragAndDropTransferable {
    fun toAwtTransferable(): Transferable
}

/**
 * Returns a [DragAndDropTransferable] that simply wraps an AWT [Transferable] instance.
 */
@ExperimentalComposeUiApi
fun DragAndDropTransferable(transferable: Transferable): DragAndDropTransferable {
    return object : AwtDragAndDropTransferable {
        override fun toAwtTransferable(): Transferable = transferable
    }
}

/**
 * Returns the AWT [Transferable] associated with the [DragAndDropEvent].
 */
@ExperimentalComposeUiApi
val DragAndDropEvent.awtTransferable: Transferable
    get() = when (nativeEvent) {
        is DropTargetDragEvent -> nativeEvent.transferable
        is DropTargetDropEvent -> nativeEvent.transferable
        else -> error("Unrecognized AWT drag event: $nativeEvent")
    }

/**
 * Returns the [DragData] associated with the given [DragAndDropEvent].
 */
@ExperimentalComposeUiApi
fun DragAndDropEvent.dragData(): DragData =
    when (val event = nativeEvent) {
        is NativeHostDragAndDropEvent -> event.data.dragData()
        else -> awtTransferable.dragData()
    }

/**
 * Represent data that is being dragged (or dropped) to a component from outside an application.
 */
@ExperimentalComposeUiApi
interface DragData {
    /**
     * Represents list of files drag and dropped to a component.
     */
    interface FilesList : DragData {
        /**
         * Returns list of file paths drag and droppped to an application in a URI format.
         */
        fun readFiles(): List<String>
    }

    /**
     * Represents an image drag and dropped to a component.
     */
    interface Image : DragData {
        /**
         * Returns an image drag and dropped to an application as a [Painter] type.
         */
        fun readImage(): Painter
    }

    /**
     * Represent text drag and dropped to a component.
     */
    interface Text : DragData {
        /**
         * Provides the best MIME type that describes text returned in [readText]
         */
        val bestMimeType: String

        /**
         * Returns a text dropped to an application.
         */
        fun readText(): String
    }
}

internal val DragAndDropEvent.positionInRoot: Offset
    get() = positionInRootImpl

@OptIn(ExperimentalComposeUiApi::class)
private fun NativeHostDragData.dragData(): DragData =
    when {
        files.isNotEmpty() || kind == NativeHostDragDataKind.FilesList -> NativeHostDragDataFilesList(this)
        imageBytes != null || kind == NativeHostDragDataKind.Image -> NativeHostDragDataImage(this)
        text != null || kind == NativeHostDragDataKind.Text -> NativeHostDragDataText(this)
        else -> UnknownDragData
    }

@OptIn(ExperimentalComposeUiApi::class)
private object UnknownDragData : DragData

@Suppress("DEPRECATION_ERROR")
@OptIn(ExperimentalComposeUiApi::class)
private fun Transferable.dragData(): DragData {
    val bestTextFlavor = selectBestTextFlavor(transferDataFlavors)
    return when {
        isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> DragDataFilesListImpl(this)
        isDataFlavorSupported(DataFlavor.imageFlavor) -> DragDataImageImpl(this)
        bestTextFlavor != null -> DragDataTextImpl(bestTextFlavor, this)
        else -> UnknownDragData
    }
}

@Suppress("DEPRECATION_ERROR")
@OptIn(ExperimentalComposeUiApi::class)
private class DragDataFilesListImpl(
    private val transferable: Transferable,
) : DragData.FilesList {
    override fun readFiles(): List<String> {
        val files =
            runCatching {
                transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
            }.getOrNull().orEmpty()
        return files.filterIsInstance<File>().map { it.toURI().toString() }
    }
}

@Suppress("DEPRECATION_ERROR")
@OptIn(ExperimentalComposeUiApi::class)
private class DragDataImageImpl(
    private val transferable: Transferable,
) : DragData.Image {
    override fun readImage(): Painter =
        (transferable.getTransferData(DataFlavor.imageFlavor) as Image).toPainter()
}

@Suppress("DEPRECATION_ERROR")
@OptIn(ExperimentalComposeUiApi::class)
private class DragDataTextImpl(
    private val bestTextFlavor: DataFlavor,
    private val transferable: Transferable,
) : DragData.Text {
    override val bestMimeType: String = bestTextFlavor.mimeType

    override fun readText(): String = bestTextFlavor.getReaderForText(transferable).readText()
}

@OptIn(ExperimentalComposeUiApi::class)
private class NativeHostDragDataFilesList(
    private val data: NativeHostDragData,
) : DragData.FilesList {
    override fun readFiles(): List<String> = data.files
}

@OptIn(ExperimentalComposeUiApi::class)
private class NativeHostDragDataImage(
    private val data: NativeHostDragData,
) : DragData.Image {
    override fun readImage(): Painter =
        BitmapPainter(SkiaImage.makeFromEncoded(checkNotNull(data.imageBytes)).toComposeImageBitmap())
}

@OptIn(ExperimentalComposeUiApi::class)
private class NativeHostDragDataText(
    private val data: NativeHostDragData,
) : DragData.Text {
    override val bestMimeType: String = "text/plain"

    override fun readText(): String = data.text.orEmpty()
}

private fun Image.toPainter(): Painter {
    if (this is BufferedImage) {
        return BitmapPainter(toComposeImageBitmap())
    }
    val bufferedImage = BufferedImage(getWidth(null), getHeight(null), BufferedImage.TYPE_INT_ARGB)
    val graphics = bufferedImage.createGraphics()
    try {
        graphics.drawImage(this, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return BitmapPainter(bufferedImage.toComposeImageBitmap())
}
