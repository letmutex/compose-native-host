package letmutex.compose.nativehost

import letmutex.compose.nativehost.internal.NativeHostBridge
import letmutex.compose.nativehost.internal.NativeHostRenderer
import letmutex.compose.nativehost.internal.MacOsComposeBridge
import letmutex.compose.nativehost.internal.RenderFrameCallback
import letmutex.compose.nativehost.internal.createComposeMetalRenderer
import androidx.compose.runtime.Composable
import kotlin.coroutines.CoroutineContext

internal actual fun createPlatformBridge(runtimeId: Long): NativeHostBridge {
    return MacOsComposeBridge(runtimeId)
}

internal actual fun createPlatformRenderer(
    hostBridge: NativeHostBridge,
    renderFrameCallback: RenderFrameCallback,
    coroutineContext: CoroutineContext,
    content: @Composable () -> Unit
): NativeHostRenderer {
    return createComposeMetalRenderer(
        hostBridge = hostBridge,
        renderFrameCallback = renderFrameCallback,
        coroutineContext = coroutineContext,
        content = content
    )
}
