@file:OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package letmutex.compose.nativehost.internal

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
import kotlinx.coroutines.MainCoroutineDispatcher

internal val ComposeHostSwingDispatcher: CoroutineDispatcher
    get() = ComposeHostSwingMainDispatcher

internal object ComposeHostSwingMainDispatcher : MainCoroutineDispatcher(), Delay {
    override val immediate: MainCoroutineDispatcher
        get() = ComposeHostSwingImmediateMainDispatcher

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
        val timer = scheduleSwingTimer(timeMillis) {
            continuation.resume(Unit) { _, _, _ -> }
        }
        continuation.invokeOnCancellation { timer.stop() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle {
        val timer = scheduleSwingTimer(timeMillis) {
            block.run()
        }
        return object : DisposableHandle {
            override fun dispose() {
                timer.stop()
            }
        }
    }
}

private object ComposeHostSwingImmediateMainDispatcher : MainCoroutineDispatcher(), Delay {
    override val immediate: MainCoroutineDispatcher
        get() = this

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !SwingUtilities.isEventDispatchThread()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        if (SwingUtilities.isEventDispatchThread()) {
            block.run()
        } else {
            SwingUtilities.invokeLater(block)
        }
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val timer = scheduleSwingTimer(timeMillis) {
            continuation.resume(Unit) { _, _, _ -> }
        }
        continuation.invokeOnCancellation { timer.stop() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle {
        val timer = scheduleSwingTimer(timeMillis) {
            block.run()
        }
        return object : DisposableHandle {
            override fun dispose() {
                timer.stop()
            }
        }
    }
}

private fun scheduleSwingTimer(
    timeMillis: Long,
    action: ActionListener,
): Timer =
    Timer(TimeUnit.MILLISECONDS.toMillis(timeMillis).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), action).apply {
        isRepeats = false
        start()
    }
