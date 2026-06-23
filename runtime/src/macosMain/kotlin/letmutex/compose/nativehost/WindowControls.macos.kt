package letmutex.compose.nativehost

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

@Composable
actual fun DraggableArea(
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier) { content() }
}

@Composable
actual fun WindowsControlButtons(
    modifier: Modifier,
    isDarkMode: Boolean,
    buttonWidth: Dp,
    buttonHeight: Dp,
    iconSize: TextUnit
) {
    // No-op on macOS
}
