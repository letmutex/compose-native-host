package letmutex.compose.nativehost

import androidx.compose.runtime.Composable
import letmutex.compose.nativehost.internal.MacOsComposeBridge

actual fun ComposeNativeHost(content: @Composable ComposeNativeHostScope.() -> Unit) {
    check(MacOsComposeBridge.isAvailable()) {
        "Launch with the native host runner so the macOS bridge library is loaded."
    }
    ComposeRuntime.initialize()
    ComposeRuntime.bindCurrentRuntime(content)
}

actual fun isComposeNativeHostAvailable(): Boolean {
    return MacOsComposeBridge.isAvailable()
}

internal actual fun isComposeNativeHostSharedLibraryRuntime(): Boolean {
    return MacOsComposeBridge.isSharedLibraryRuntime()
}
