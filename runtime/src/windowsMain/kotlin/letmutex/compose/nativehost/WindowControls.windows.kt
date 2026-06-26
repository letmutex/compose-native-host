package letmutex.compose.nativehost

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import letmutex.compose.nativehost.internal.WindowsComposeBridgeBindings
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

import androidx.compose.ui.platform.LocalViewConfiguration

@Composable
actual fun DraggableArea(
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val host = LocalComposeNativeHostHandle.current
    val viewConfiguration = LocalViewConfiguration.current
    Box(
        modifier = modifier.pointerInput(viewConfiguration.doubleTapTimeoutMillis) {
            var lastClickTime = 0L
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.type == PointerEventType.Press) {
                        val change = event.changes.firstOrNull()
                        if (change != null && !change.isConsumed) {
                            change.consume()
                            val now = change.uptimeMillis
                            if (now - lastClickTime < viewConfiguration.doubleTapTimeoutMillis) {
                                host?.maximizeWindow()
                                lastClickTime = 0L
                            } else {
                                host?.performWindowDrag()
                                lastClickTime = now
                            }
                        }
                    }
                }
            }
        }
    ) {
        content()
    }
}

// ============================================================================
// Modern Flat Buttons (Windows 10/11 native look)
// ============================================================================

enum class CaptionButtonType { Minimize, Maximize, Close }

@Composable
private fun ModernCaptionButton(
    type: CaptionButtonType,
    isDarkMode: Boolean,
    buttonWidth: Dp,
    buttonHeight: Dp,
    iconSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val host = LocalComposeNativeHostHandle.current
    val windowInfo = LocalComposeNativeHostWindowInfo.current
    val isClose = type == CaptionButtonType.Close
    val isMaximized = windowInfo?.isMaximized == true

    val nativeHoveredButton = windowInfo?.hoveredCaptionButton ?: 0
    val isNativeHovered = when (type) {
        CaptionButtonType.Minimize -> nativeHoveredButton == 1
        CaptionButtonType.Maximize -> nativeHoveredButton == 2
        CaptionButtonType.Close -> nativeHoveredButton == 3
    }

    // Use Compose pressed state but Native hovered state
    val isEffectivelyHovered = isNativeHovered || isHovered

    val bgColor = when {
        isClose && isPressed -> Color(0xFFF1707A)
        isClose && isEffectivelyHovered -> Color(0xFFE81123)
        isPressed -> Color(0x33000000)
        isEffectivelyHovered -> Color(0x1A000000)
        else -> Color.Transparent
    }

    val fgColor = when {
        isDarkMode -> Color.White
        isClose && (isEffectivelyHovered || isPressed) -> Color.White
        else -> Color(0xFF171717)
    }

    Box(
        modifier = modifier
            .size(buttonWidth, buttonHeight)
            .background(bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    when (type) {
                        CaptionButtonType.Minimize -> host?.minimizeWindow()
                        CaptionButtonType.Maximize -> host?.maximizeWindow()
                        CaptionButtonType.Close -> host?.closeWindow()
                    }
                }
            )
    ) {
        val titleBarBgColor = if (bgColor == Color.Transparent) {
            if (isDarkMode) Color(0xFF000000) else Color(0xFFFFFFFF)
        } else bgColor

        Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(10.dp)) {
                // Determine crisp integer stroke width based on display scaling
                val strokePx = if (density >= 2f) 2f else 1f
                val halfStroke = strokePx / 2f

                val w = kotlin.math.round(size.width)
                val h = kotlin.math.round(size.height)

                when (type) {
                    CaptionButtonType.Minimize -> {
                        val topEdge = kotlin.math.round((h - strokePx) / 2f)
                        val y = topEdge + halfStroke
                        drawLine(
                            color = fgColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = strokePx
                        )
                    }
                    CaptionButtonType.Maximize -> {
                        if (isMaximized) {
                            val rectSize = kotlin.math.round(w * 0.8f)
                            val offset = kotlin.math.round(w * 0.2f)

                            // Front rectangle (bottom-left)
                            drawRect(
                                color = fgColor,
                                topLeft = Offset(halfStroke, offset + halfStroke),
                                size = Size(rectSize - strokePx, rectSize - strokePx),
                                style = Stroke(width = strokePx)
                            )

                            // Back rectangle (top-right) drawn with 4 distinct line segments
                            // to avoid overlapping the front rectangle, eliminating the need for a mask!

                            // Top edge
                            drawLine(fgColor, Offset(offset, halfStroke), Offset(w, halfStroke), strokeWidth = strokePx)
                            // Right edge
                            drawLine(fgColor, Offset(w - halfStroke, 0f), Offset(w - halfStroke, rectSize), strokeWidth = strokePx)
                            // Left edge stub (stops exactly where the front rect begins)
                            drawLine(fgColor, Offset(offset + halfStroke, 0f), Offset(offset + halfStroke, offset), strokeWidth = strokePx)
                            // Bottom edge stub (starts exactly where the front rect ends)
                            drawLine(fgColor, Offset(rectSize, rectSize - halfStroke), Offset(w, rectSize - halfStroke), strokeWidth = strokePx)
                        } else {
                            drawRect(
                                color = fgColor,
                                topLeft = Offset(halfStroke, halfStroke),
                                size = Size(w - strokePx, h - strokePx),
                                style = Stroke(width = strokePx)
                            )
                        }
                    }
                    CaptionButtonType.Close -> {
                        // Native Win10 close X has a visual weight between 1px-noAA (too thin)
                        // and 1px-AA (too bold). 0.7 DP with AA provides the exact native visual weight.
                        val closeStroke = 0.7f * density
                        drawLine(
                            color = fgColor,
                            start = Offset(0f, 0f),
                            end = Offset(w, h),
                            strokeWidth = closeStroke
                        )
                        drawLine(
                            color = fgColor,
                            start = Offset(0f, h),
                            end = Offset(w, 0f),
                            strokeWidth = closeStroke
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Main Entry
// ============================================================================

@Composable
actual fun WindowsControlButtons(
    modifier: Modifier,
    isDarkMode: Boolean,
    buttonWidth: Dp,
    buttonHeight: Dp,
    iconSize: TextUnit
) {
    Row(modifier = modifier) {
        ModernCaptionButton(
            type = CaptionButtonType.Minimize,
            isDarkMode = isDarkMode,
            buttonWidth = buttonWidth,
            buttonHeight = buttonHeight,
            iconSize = iconSize
        )
        ModernCaptionButton(
            type = CaptionButtonType.Maximize,
            isDarkMode = isDarkMode,
            buttonWidth = buttonWidth,
            buttonHeight = buttonHeight,
            iconSize = iconSize
        )
        ModernCaptionButton(
            type = CaptionButtonType.Close,
            isDarkMode = isDarkMode,
            buttonWidth = buttonWidth,
            buttonHeight = buttonHeight,
            iconSize = iconSize
        )
    }
}
