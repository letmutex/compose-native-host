package letmutex.compose.nativehost

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A draggable area that allows the user to move the window by clicking and dragging.
 */
@Composable
expect fun DraggableArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
)

/**
 * Standard system control buttons (Minimize, Maximize, Close) rendered using the native OS theme.
 */
@Composable
expect fun WindowsControlButtons(
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false,
    buttonWidth: Dp = 46.dp,
    buttonHeight: Dp = 32.dp,
    iconSize: TextUnit = 10.sp
)
