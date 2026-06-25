@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package letmutex.compose.nativehost.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerIconCrosshair
import androidx.compose.ui.input.pointer.pointerIconDefault
import androidx.compose.ui.input.pointer.pointerIconEastResize
import androidx.compose.ui.input.pointer.pointerIconHand
import androidx.compose.ui.input.pointer.pointerIconMove
import androidx.compose.ui.input.pointer.pointerIconNortheastResize
import androidx.compose.ui.input.pointer.pointerIconNorthResize
import androidx.compose.ui.input.pointer.pointerIconNorthwestResize
import androidx.compose.ui.input.pointer.pointerIconSouthResize
import androidx.compose.ui.input.pointer.pointerIconSoutheastResize
import androidx.compose.ui.input.pointer.pointerIconSouthwestResize
import androidx.compose.ui.input.pointer.pointerIconText
import androidx.compose.ui.input.pointer.pointerIconWait
import androidx.compose.ui.input.pointer.pointerIconWestResize
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import letmutex.compose.nativehost.isComposeNativeHostSharedLibraryRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PatchedApisCardShape = RoundedCornerShape(22.dp)
private val PatchedStatusShape = RoundedCornerShape(999.dp)
private val PointerTestAreaShape = RoundedCornerShape(18.dp)

private data class PatchedCheckStatus(
    val label: String,
    val details: String,
    val tint: Color,
)

private data class CursorPreview(
    val label: String,
    val icon: PointerIcon,
)

private val PendingStatus = PatchedCheckStatus("Not run", "Use the button on this card.", Color(0xFF78716C))

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun PatchedApisPage(
    onInteraction: (String) -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var clipboardDraft by remember {
        mutableStateOf("Compose Native Host patched runtime check")
    }
    var clipboardStatus by remember {
        mutableStateOf(
            PendingStatus.copy(details = "Writes sample text to the Compose clipboard, then reads it back."),
        )
    }
    var dispatcherStatus by remember {
        mutableStateOf(
            PendingStatus.copy(details = "Checks Dispatchers.Main.immediate and lifecycle's desktop override."),
        )
    }
    val sharedLibraryRuntime = remember { isComposeNativeHostSharedLibraryRuntime() }
    val pointerPreviews =
        remember(sharedLibraryRuntime) {
            listOf(
                CursorPreview("Default", pointerIconDefault),
                CursorPreview("Hand", pointerIconHand),
                CursorPreview("Text", pointerIconText),
                CursorPreview("Crosshair", pointerIconCrosshair),
                CursorPreview("Move", pointerIconMove),
                CursorPreview("Wait", pointerIconWait),
                CursorPreview("East resize", pointerIconEastResize),
                CursorPreview("West resize", pointerIconWestResize),
                CursorPreview("North resize", pointerIconNorthResize),
                CursorPreview("South resize", pointerIconSouthResize),
                CursorPreview("NE resize", pointerIconNortheastResize),
                CursorPreview("NW resize", pointerIconNorthwestResize),
                CursorPreview("SE resize", pointerIconSoutheastResize),
                CursorPreview("SW resize", pointerIconSouthwestResize),
            )
        }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Patched Runtime APIs",
            color = Color(0xFF1C1917),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text =
                "This page only reports three checks. Drag and drop stays on the dedicated DragDrop tab.",
            color = Color(0xFF57534E),
            style = MaterialTheme.typography.bodyLarge,
        )
        PatchedApiCard(
            title = "Clipboard",
            summary = "Automatic check for PlatformClipboard_desktopKt.",
        ) {
            PatchedStatus(status = clipboardStatus)
            BasicTextField(
                value = clipboardDraft,
                onValueChange = { clipboardDraft = it },
                modifier =
                    Modifier.fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF1C1917)),
            )
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(clipboardDraft))
                    val readback = clipboardManager.getText()?.text
                    clipboardStatus =
                        if (readback == clipboardDraft) {
                            PatchedCheckStatus("Passed", "Read back the same text that was written.", Color(0xFF166534))
                        } else {
                            PatchedCheckStatus(
                                "Failed",
                                "Expected \"$clipboardDraft\", got ${readback ?: "(null)"}",
                                Color(0xFFB91C1C),
                            )
                        }
                },
            ) {
                Text("Run Clipboard Check")
            }
        }
        PatchedApiCard(
            title = "Pointer Icon",
            summary = "Manual check for PointerIcon_desktopKt.",
        ) {
            Text(
                text =
                    if (sharedLibraryRuntime) {
                        "Hover each tile. Wait falls back to arrow; diagonal resize uses public AppKit on macOS 15+ and falls back to arrow on older macOS."
                    } else {
                        "Hover each tile to compare the standard desktop cursor mappings."
                    },
                color = Color(0xFF57534E),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                pointerPreviews.forEach { preview ->
                    CursorPreviewTile(preview)
                }
            }
        }
        PatchedApiCard(
            title = "Lifecycle + Dispatcher",
            summary = "Automatic check for Dispatchers.Main, MainUIDispatcher_awt, and MainDispatcherChecker.",
        ) {
            PatchedStatus(status = dispatcherStatus)
            Button(
                onClick = {
                    coroutineScope.launch {
                        dispatcherStatus =
                            runCatching {
                                withContext(Dispatchers.Main.immediate) {
                                    Thread.currentThread().name to reflectLifecycleMainDispatcherThread()
                                }
                            }.fold(
                                onSuccess = { (dispatcherThread, lifecycleMain) ->
                                    when (lifecycleMain) {
                                        true -> PatchedCheckStatus(
                                            "Passed",
                                            "Dispatchers.Main.immediate ran on $dispatcherThread and lifecycle returned true.",
                                            Color(0xFF166534),
                                        )
                                        false -> PatchedCheckStatus(
                                            "Failed",
                                            "Dispatchers.Main.immediate ran on $dispatcherThread but lifecycle returned false.",
                                            Color(0xFFB91C1C),
                                        )
                                        null -> PatchedCheckStatus(
                                            "Unavailable",
                                            "Lifecycle checker could not be invoked.",
                                            Color(0xFFB45309),
                                        )
                                    }
                                },
                                onFailure = { error ->
                                    PatchedCheckStatus(
                                        "Unavailable",
                                        error.message ?: "Dispatchers.Main.immediate could not be invoked.",
                                        Color(0xFFB45309),
                                    )
                                },
                            )
                    }
                },
            ) {
                Text("Run Dispatcher Check")
            }
        }
    }
}

@Composable
private fun PatchedApiCard(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFFF9F5EE),
        shape = PatchedApisCardShape,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(
                    text = title,
                    color = Color(0xFF1C1917),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = summary,
                    color = Color(0xFF57534E),
                    style = MaterialTheme.typography.bodyMedium,
                )
                content()
            },
        )
    }
}

@Composable
private fun PatchedStatus(
    status: PatchedCheckStatus,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = status.tint.copy(alpha = 0.12f),
            shape = PatchedStatusShape,
        ) {
            Text(
                text = status.label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = status.tint,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = status.details,
            color = Color(0xFF44403C),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CursorPreviewTile(
    preview: CursorPreview,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier =
                Modifier.size(72.dp)
                    .pointerHoverIcon(preview.icon)
                    .border(BorderStroke(1.dp, Color(0xFF78716C)), PointerTestAreaShape),
            color = Color(0xFFF1F5F9),
            shape = PointerTestAreaShape,
        ) { Box(modifier = Modifier.fillMaxSize()) }
        Text(
            text = preview.label,
            color = Color(0xFF1C1917),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun reflectLifecycleMainDispatcherThread(): Boolean? =
    runCatching {
        val checkerClass = Class.forName("androidx.lifecycle.MainDispatcherChecker")
        val instance = checkerClass.getField("INSTANCE").get(null)
        val method = checkerClass.getDeclaredMethod("isMainDispatcherThread")
        method.isAccessible = true
        method.invoke(instance) as Boolean
    }.getOrNull()
