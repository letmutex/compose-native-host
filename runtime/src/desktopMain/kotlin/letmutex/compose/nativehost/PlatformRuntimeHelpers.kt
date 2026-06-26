package letmutex.compose.nativehost

import letmutex.compose.nativehost.internal.NativeHostBridge
import letmutex.compose.nativehost.internal.NativeHostRenderer
import letmutex.compose.nativehost.internal.RenderFrameCallback
import androidx.compose.runtime.Composable
import kotlin.coroutines.CoroutineContext

internal expect fun createPlatformBridge(runtimeId: Long): NativeHostBridge

internal expect fun createPlatformRenderer(
    hostBridge: NativeHostBridge,
    renderFrameCallback: RenderFrameCallback,
    coroutineContext: CoroutineContext,
    content: @Composable () -> Unit
): NativeHostRenderer
