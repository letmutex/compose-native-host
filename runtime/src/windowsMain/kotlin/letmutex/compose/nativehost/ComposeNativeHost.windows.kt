package letmutex.compose.nativehost

import androidx.compose.runtime.Composable
import letmutex.compose.nativehost.internal.WindowsComposeBridge
import letmutex.compose.nativehost.internal.WindowsComposeBridgeBindings

actual fun ComposeNativeHost(content: @Composable ComposeNativeHostScope.() -> Unit) {
    check(WindowsComposeBridge.isAvailable()) {
        "Launch with the native host runner so the Windows bridge library is loaded."
    }
    ComposeRuntime.initialize()
    ComposeRuntime.bindCurrentRuntime(content)
}

actual fun isComposeNativeHostAvailable(): Boolean {
    return WindowsComposeBridge.isAvailable()
}

internal actual fun isComposeNativeHostSharedLibraryRuntime(): Boolean {
    return WindowsComposeBridge.isSharedLibraryRuntime()
}

internal actual fun logPhaseTiming(name: String) {
    WindowsComposeBridgeBindings.nativeHostLogPhaseTiming(name)
}
