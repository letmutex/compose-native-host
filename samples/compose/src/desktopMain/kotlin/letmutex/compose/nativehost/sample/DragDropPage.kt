@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package letmutex.compose.nativehost.sample

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draganddrop.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
private data class DropPayload(
    val files: List<String> = emptyList(),
    val text: String? = null,
    val image: Painter? = null,
)

@OptIn(ExperimentalComposeUiApi::class)
private data class DropPreview(
    val positionInRoot: Offset,
    val action: DragAndDropTransferAction?,
    val data: DropPayload,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DragDropPage(
    onInteraction: (String) -> Unit,
) {
    var activeDrop by remember { mutableStateOf<DropPreview?>(null) }
    var committedDrop by remember { mutableStateOf<DropPreview?>(null) }
    var clearDropJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun cancelDropReset() {
        clearDropJob?.cancel()
        clearDropJob = null
    }

    fun scheduleDropReset(drop: DropPreview) {
        cancelDropReset()
        clearDropJob =
            coroutineScope.launch {
                delay(5_000)
                if (committedDrop == drop) {
                    committedDrop = null
                }
            }
    }

    val dropTarget =
        remember(onInteraction) {
            object : DragAndDropTarget {
                override fun onStarted(event: DragAndDropEvent) = Unit

                override fun onEntered(event: DragAndDropEvent) {
                    cancelDropReset()
                    activeDrop = event.toDropPreview()
                }

                override fun onMoved(event: DragAndDropEvent) {
                    cancelDropReset()
                    activeDrop = event.toDropPreview()
                }

                override fun onChanged(event: DragAndDropEvent) {
                    cancelDropReset()
                    activeDrop = event.toDropPreview()
                }

                override fun onExited(event: DragAndDropEvent) {
                    activeDrop = null
                }

                override fun onEnded(event: DragAndDropEvent) {
                    activeDrop = null
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val preview = event.toDropPreview() ?: activeDrop ?: return false
                    activeDrop = preview
                    committedDrop = preview
                    scheduleDropReset(preview)
                    onInteraction(dropInteractionMessage(preview))
                    return true
                }
            }
        }
    val displayDrop = activeDrop ?: committedDrop

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Drag And Drop",
            color = Color(0xFF1C1917),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text =
                "This page now uses Compose's official drop-target API. Drop files, text, or images from Finder or another macOS window onto the target.",
            color = Color(0xFF57534E),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ExternalDropTarget(
                modifier =
                    Modifier.weight(1.15f)
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = { event ->
                                event.hasSupportedPayload()
                            },
                            target = dropTarget,
                        ),
                activeDrop = activeDrop,
            )
            DropPayloadPanel(
                modifier = Modifier.weight(0.85f),
                displayDrop = displayDrop,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExternalDropTarget(
    activeDrop: DropPreview?,
    modifier: Modifier = Modifier,
) {
    val targetColor =
        if (activeDrop != null) {
            Color(0xFF2563EB)
        } else {
            Color(0xFFF59E0B)
        }
    val title =
        if (activeDrop != null) {
            "Release To Drop"
        } else {
            "Drop Target"
        }
    val summary =
        if (activeDrop != null) {
            "Compose is receiving official drag target callbacks from the native-host bridge."
        } else {
            "Bring a file, text selection, or image onto the orange target."
        }
    val coordinateText =
        activeDrop?.let { drop ->
            "x=${drop.positionInRoot.x.toInt()}, y=${drop.positionInRoot.y.toInt()} (${drop.action.displayName()})"
        } ?: "Waiting for external drag input."

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFFEFE3D2),
        shape = RoundedCornerShape(28.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(22.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = targetColor,
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = title,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = summary,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text(
                        text = coordinateText,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DropPayloadPanel(
    displayDrop: DropPreview?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFFF9F5EE),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Payload",
                color = Color(0xFF1C1917),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (displayDrop == null) {
                PayloadPlaceholder("No external payload has reached the target yet.")
                return@Column
            }
            PayloadStat(
                title = "Action",
                value = displayDrop.action.displayName(),
            )
            PayloadStat(
                title = "Files",
                value = if (displayDrop.data.files.isEmpty()) "None" else "${displayDrop.data.files.size} item(s)",
            )
            displayDrop.data.files.take(3).forEach { file ->
                PayloadChip(file.substringAfterLast('/').ifBlank { file })
            }
            PayloadStat(
                title = "Text",
                value =
                    displayDrop.data.text
                        ?.takeIf { it.isNotBlank() }
                        ?.replace('\n', ' ')
                        ?.take(120)
                        ?: "None",
            )
            if (displayDrop.data.image != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    color = Color(0xFFE7DDD0),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Image(
                        painter = displayDrop.data.image,
                        contentDescription = "Dropped image preview",
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                PayloadStat(
                    title = "Image",
                    value = "None",
                )
            }
        }
    }
}

@Composable
private fun PayloadPlaceholder(text: String) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(160.dp)
                .background(Color(0xFFE7DDD0), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(18.dp),
            color = Color(0xFF57534E),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PayloadStat(
    title: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            color = Color(0xFF7C2D12),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            color = Color(0xFF292524),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PayloadChip(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFFE0E7FF),
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(8.dp).background(Color(0xFF2563EB), RoundedCornerShape(999.dp)),
            )
            Text(
                text = label,
                color = Color(0xFF1E3A8A),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.toDropPreview(): DropPreview? {
    val payload = dragData().toDropPayload() ?: return null
    return DropPreview(
        positionInRoot = positionInRoot,
        action = action,
        data = payload,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.hasSupportedPayload(): Boolean = dragData().toDropPayload() != null

@OptIn(ExperimentalComposeUiApi::class)
private fun DragData.toDropPayload(): DropPayload? =
    when (this) {
        is DragData.FilesList -> DropPayload(files = readFiles())
        is DragData.Text -> DropPayload(text = readText().takeIf { it.isNotBlank() })
        is DragData.Image -> DropPayload(image = readImage())
        else -> null
    }

private fun dropInteractionMessage(drop: DropPreview): String =
    buildString {
        append("Dropped ")
        when {
            drop.data.files.isNotEmpty() -> append("${drop.data.files.size} file(s)")
            drop.data.text != null -> append("text")
            drop.data.image != null -> append("an image")
            else -> append("payload")
        }
        append(" at x=${drop.positionInRoot.x.toInt()}, y=${drop.positionInRoot.y.toInt()}.")
    }

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropTransferAction?.displayName(): String =
    when (this) {
        DragAndDropTransferAction.Copy -> "Copy"
        DragAndDropTransferAction.Move -> "Move"
        DragAndDropTransferAction.Link -> "Link"
        else -> "Unknown"
    }
