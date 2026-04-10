package letmutex.compose.nativehost

import androidx.compose.runtime.Composable
import letmutex.compose.nativehost.internal.MacOsComposeBridge

@Suppress("UNCHECKED_CAST")
actual fun ComposeNativeHost(content: @Composable ComposeNativeHostScope.() -> Unit) {
    composeNativeHostJvm(content)
}

actual fun isComposeNativeHostAvailable(): Boolean = MacOsComposeBridge.isAvailable()

private fun composeNativeHostJvm(content: @Composable ComposeNativeHostScope.() -> Unit) {
    check(System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
        "ComposeNativeHost currently supports macOS only"
    }
    check(MacOsComposeBridge.isAvailable()) {
        "Launch with the native host runner so the macOS bridge library is loaded."
    }
    ComposeRuntime.initialize()
    ComposeRuntime.bindCurrentRuntime(content)
}
