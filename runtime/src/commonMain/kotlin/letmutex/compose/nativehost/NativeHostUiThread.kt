package letmutex.compose.nativehost

import kotlinx.coroutines.CoroutineDispatcher
import org.jctools.queues.MpscArrayQueue
import org.jctools.queues.atomic.MpscAtomicArrayQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.Queue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

private const val UI_THREAD_QUEUE_CAPACITY = 65_536

/**
 * Dedicated UI thread used by the native host runtime to serialize Compose work.
 */
class NativeHostUiThread(
    private val threadName: String = "ComposeNativeHost-Ui",
) {
    companion object {
        val shared: NativeHostUiThread = NativeHostUiThread()
    }

    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(true)
    private val ready = CountDownLatch(1)
    private val queue: Queue<Runnable> =
        if (isNativeImageRuntime()) {
            // Avoid the Unsafe queue under native image.
            MpscAtomicArrayQueue(UI_THREAD_QUEUE_CAPACITY)
        } else {
            // Prefer the lower-latency queue on the JVM.
            MpscArrayQueue(UI_THREAD_QUEUE_CAPACITY)
        }
    private val availableTasks = Semaphore(0)

    @Volatile
    private var thread: Thread? = null

    val dispatcher: CoroutineDispatcher = NativeHostUiDispatcher(this)

    /**
     * Starts the UI thread if needed and waits until it is ready to accept work.
     */
    fun ensureStarted() {
        if (ready.count == 0L) return

        if (started.compareAndSet(false, true)) {
            val newThread = Thread(::runLoop, threadName).apply {
                isDaemon = true
            }
            thread = newThread
            newThread.start()
        }
        ready.await()
    }

    /**
     * Returns true when called from the managed UI thread.
     */
    fun isUiThread(): Boolean = Thread.currentThread() === thread

    /**
     * Returns a snapshot of the UI thread state and stack trace for diagnostics.
     */
    fun dumpState(): String {
        val activeThread = thread ?: return "ComposeNativeHost UI thread is not started"
        val stack =
            activeThread.stackTrace.joinToString(separator = "\n") { element ->
                "    at $element"
            }
        return buildString {
            append("ComposeNativeHost UI thread state: ")
            append(activeThread.state)
            append('\n')
            append(stack)
        }
    }

    /**
     * Runs the task immediately when already on the UI thread, otherwise posts it.
     */
    fun dispatch(runnable: Runnable) {
        ensureStarted()
        if (isUiThread()) {
            runnable.run()
            return
        }
        post(runnable)
    }

    /**
     * Posts a task to the UI thread without waiting for completion.
     */
    fun post(runnable: Runnable) {
        ensureStarted()
        if (!enqueue(runnable)) {
            System.err.println("ComposeNativeHost UI thread is closed. Task rejected.")
        }
    }

    /**
     * Executes the block on the UI thread and blocks until it completes.
     */
    fun <T> callOnUiThread(block: () -> T): T {
        ensureStarted()
        if (isUiThread()) {
            return block()
        }

        val futureTask = FutureTask {
            runCatching(block)
        }
        check(enqueue(futureTask)) { "ComposeNativeHost UI thread is closed" }
        return futureTask.get().getOrThrow()
    }

    /**
     * Stops the UI thread and rejects any later work.
     */
    fun close() {
        if (!started.get() || !running.compareAndSet(true, false)) {
            return
        }
        availableTasks.release()
    }

    private fun enqueue(runnable: Runnable): Boolean {
        if (!running.get()) return false

        while (running.get()) {
            if (queue.offer(runnable)) {
                availableTasks.release()
                return true
            }
            Thread.onSpinWait()
        }
        return false
    }

    private fun runLoop() {
        ready.countDown()

        while (running.get()) {
            try {
                availableTasks.acquire()
                var remainingTasks = 1 + availableTasks.drainPermits()
                while (remainingTasks > 0) {
                    val task = queue.poll()
                    if (task != null) {
                        try {
                            task.run()
                        } catch (t: Throwable) {
                            System.err.println("ComposeNativeHost UI thread task failed")
                            t.printStackTrace(System.err)
                        }
                    }
                    remainingTasks--
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!running.get()) {
                    break
                }
            }
        }
        queue.clear()
        thread = null
    }
}

private fun isNativeImageRuntime(): Boolean =
    System.getProperty("org.graalvm.nativeimage.imagecode") != null

private class NativeHostUiDispatcher(
    private val uiThread: NativeHostUiThread,
) : CoroutineDispatcher() {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        uiThread.post(block)
    }

    override fun toString(): String = "NativeHostUiDispatcher"
}
