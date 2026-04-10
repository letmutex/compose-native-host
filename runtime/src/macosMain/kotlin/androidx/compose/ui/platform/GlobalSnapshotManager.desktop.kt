package androidx.compose.ui.platform

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import letmutex.compose.nativehost.NativeHostUiThread
import letmutex.compose.nativehost.internal.MacOsComposeBridge
import java.awt.EventQueue

internal val GlobalSnapshotManagerDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.PUBLICATION) {
    if (MacOsComposeBridge.isAvailable()) {
        NativeHostUiThread.shared.dispatcher
    } else {
        AwtEventQueueDispatcher
    }
}

private object AwtEventQueueDispatcher : CoroutineDispatcher() {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        EventQueue.invokeLater(block)
    }
}
