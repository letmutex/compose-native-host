package letmutex.compose.nativehost.sample

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import letmutex.compose.nativehost.WindowInfo
import kotlin.math.roundToInt

fun main() =
    application {
        val windowState = rememberWindowState(width = 1320.dp, height = 920.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = "Compose Native Host Sample Compose",
            state = windowState,
            visible = true,
            undecorated = false,
            transparent = false,
            resizable = true,
        ) {
            window.title = ""
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            ComposeDesktopSampleWindow()
        }
    }

@Composable
private fun ComposeDesktopSampleWindow(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val windowInfo =
            remember(maxWidth, maxHeight, density.density) {
                with(density) {
                    WindowInfo(
                        width = maxWidth.toPx().roundToInt(),
                        height = maxHeight.toPx().roundToInt(),
                        scale = this.density,
                        refreshRate = 0,
                        isFocused = true,
                    )
                }
        }
        SampleApp(
            windowInfo = windowInfo,
            copy = SampleCopy(
                eyebrow = "Compose Desktop window",
                title = "Compose JVM",
                summaryText = "This variant runs as a plain Compose Desktop app with no native host bridge in the loop.",
                resizeHint = "Resize the Compose Desktop window to compare the standard desktop path against the native-hosted samples.",
            ),
        )
    }
}
