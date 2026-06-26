package letmutex.compose.nativehost

import letmutex.compose.nativehost.internal.NativeHostBridge
import letmutex.compose.nativehost.internal.NativeHostRenderer
import letmutex.compose.nativehost.internal.RenderFrameCallback
import letmutex.compose.nativehost.internal.WindowsComposeBridge
import letmutex.compose.nativehost.internal.createComposeDirect3DRenderer
import androidx.compose.runtime.Composable
import kotlin.coroutines.CoroutineContext

internal actual fun createPlatformBridge(runtimeId: Long): NativeHostBridge {
    return WindowsComposeBridge(runtimeId)
}

internal actual fun createPlatformRenderer(
    hostBridge: NativeHostBridge,
    renderFrameCallback: RenderFrameCallback,
    coroutineContext: CoroutineContext,
    content: @Composable () -> Unit
): NativeHostRenderer {
    return createComposeDirect3DRenderer(
        hostBridge = hostBridge,
        renderFrameCallback = renderFrameCallback,
        coroutineContext = coroutineContext,
        content = content
    )
}
