package letmutex.compose.nativehost

import androidx.compose.runtime.Composable

/**
 * Entry point for hosted Compose content on the native host runtime.
 */
expect fun ComposeNativeHost(content: @Composable ComposeNativeHostScope.() -> Unit)

/**
 * Returns true when the current process can talk to the native host bridge.
 */
expect fun isComposeNativeHostAvailable(): Boolean

/**
 * Returns true when running in a shared library environment.
 */
internal expect fun isComposeNativeHostSharedLibraryRuntime(): Boolean

/**
 * Log phase timing metric to the native host environment.
 */
internal expect fun logPhaseTiming(name: String)
