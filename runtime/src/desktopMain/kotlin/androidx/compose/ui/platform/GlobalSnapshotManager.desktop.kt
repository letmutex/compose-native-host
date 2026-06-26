package androidx.compose.ui.platform

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import letmutex.compose.nativehost.NativeHostUiThread
import letmutex.compose.nativehost.isComposeNativeHostAvailable
import java.awt.EventQueue

internal val GlobalSnapshotManagerDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.PUBLICATION) {
    if (isComposeNativeHostAvailable()) {
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
