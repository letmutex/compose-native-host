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
