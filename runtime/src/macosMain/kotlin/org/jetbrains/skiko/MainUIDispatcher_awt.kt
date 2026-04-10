package org.jetbrains.skiko

import java.awt.event.ActionListener
import java.lang.Runnable
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import letmutex.compose.nativehost.NativeHostUiThread
import letmutex.compose.nativehost.internal.MacOsComposeBridge

val MainUIDispatcher: CoroutineDispatcher
    get() =
        if (MacOsComposeBridge.isAvailable()) {
            NativeHostUiThread.shared.dispatcher
        } else {
            ComposeHostSwingDispatcher
        }

@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private object ComposeHostSwingDispatcher : CoroutineDispatcher(), Delay {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        SwingUtilities.invokeLater(block)
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val timer = schedule(timeMillis, TimeUnit.MILLISECONDS) {
            continuation.resume(Unit) { }
        }
        continuation.invokeOnCancellation { timer.stop() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle {
        val timer = schedule(timeMillis, TimeUnit.MILLISECONDS) {
            block.run()
        }
        return object : DisposableHandle {
            override fun dispose() {
                timer.stop()
            }
        }
    }

    private fun schedule(
        time: Long,
        unit: TimeUnit,
        action: ActionListener,
    ): Timer =
        Timer(unit.toMillis(time).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), action).apply {
            isRepeats = false
            start()
        }
}
