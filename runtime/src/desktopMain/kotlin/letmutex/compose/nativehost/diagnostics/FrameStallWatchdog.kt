package letmutex.compose.nativehost.diagnostics

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val FRAME_STALL_TIMEOUT_NANOS = 3_000_000_000L
private const val NO_WATCHDOG_DEADLINE_NANOS = -1L

internal class FrameStallWatchdog(
    private val framePending: AtomicBoolean,
    private val frameLoopScheduled: AtomicBoolean,
    private val renderInFlight: AtomicBoolean,
    private val lastFrameRequestNanos: AtomicLong,
    private val lastFrameStartNanos: AtomicLong,
    private val lastFrameCompleteNanos: AtomicLong,
    private val dumpState: () -> String,
) : AutoCloseable {
    private val lastStallDumpNanos = AtomicLong(0L)
    private val stateLock = ReentrantLock()
    private val stateChanged = stateLock.newCondition()

    private var closed = false

    private val worker =
        Thread(
            {
                run()
            },
            "ComposeNativeHost-Watchdog",
        ).apply {
            isDaemon = true
            start()
        }

    fun onFrameRequested() {
        signalStateChanged()
    }

    fun onFrameStateChanged() {
        signalStateChanged()
    }

    override fun close() {
        stateLock.withLock {
            if (closed) {
                return
            }
            closed = true
            stateChanged.signalAll()
        }
    }

    private fun run() {
        stateLock.lock()
        try {
            while (!closed) {
                val waitNanos = nextWaitNanos()
                when {
                    waitNanos == NO_WATCHDOG_DEADLINE_NANOS -> stateChanged.await()
                    waitNanos > 0L -> stateChanged.awaitNanos(waitNanos)
                    else -> {
                        stateLock.unlock()
                        try {
                            checkForFrameStall()
                        } finally {
                            stateLock.lock()
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            stateLock.unlock()
        }
    }

    private fun signalStateChanged() {
        stateLock.withLock {
            if (!closed) {
                stateChanged.signalAll()
            }
        }
    }

    private fun nextWaitNanos(): Long {
        if (!framePending.get() && !frameLoopScheduled.get() && !renderInFlight.get()) {
            return NO_WATCHDOG_DEADLINE_NANOS
        }

        val completedAt = lastFrameCompleteNanos.get()
        val requestedAt = lastFrameRequestNanos.get()
        if (requestedAt <= completedAt) {
            return NO_WATCHDOG_DEADLINE_NANOS
        }

        val now = System.nanoTime()
        val stallAt = requestedAt + FRAME_STALL_TIMEOUT_NANOS
        if (now < stallAt) {
            return stallAt - now
        }

        val previousDumpAt = lastStallDumpNanos.get()
        if (previousDumpAt == 0L) {
            return 0L
        }

        val nextDumpAt = previousDumpAt + FRAME_STALL_TIMEOUT_NANOS
        return if (now < nextDumpAt) {
            nextDumpAt - now
        } else {
            0L
        }
    }

    private fun checkForFrameStall() {
        val frameLoopScheduled = frameLoopScheduled.get()
        val renderInFlight = renderInFlight.get()
        if (!framePending.get() && !frameLoopScheduled && !renderInFlight) {
            return
        }
        val now = System.nanoTime()
        val completedAt = lastFrameCompleteNanos.get()
        val requestedAt = lastFrameRequestNanos.get()
        if (requestedAt <= completedAt || now - requestedAt < FRAME_STALL_TIMEOUT_NANOS) {
            return
        }
        val previousDumpAt = lastStallDumpNanos.get()
        if (previousDumpAt != 0L && now - previousDumpAt < FRAME_STALL_TIMEOUT_NANOS) {
            return
        }
        lastStallDumpNanos.set(now)
        System.err.println(
            buildString {
                appendLine("ComposeNativeHost frame stall detected")
                appendLine("  framePending=${framePending.get()}")
                appendLine("  frameLoopScheduled=$frameLoopScheduled")
                appendLine("  renderInFlight=$renderInFlight")
                appendLine("  requestAgeMs=${(now - requestedAt) / 1_000_000}")
                appendLine("  renderStartAgeMs=${ageMillis(now, lastFrameStartNanos.get())}")
                appendLine("  renderCompleteAgeMs=${ageMillis(now, completedAt)}")
                append(dumpState())
            },
        )
    }

    private fun ageMillis(nowNanos: Long, thenNanos: Long): Long =
        if (thenNanos == 0L) {
            -1L
        } else {
            (nowNanos - thenNanos) / 1_000_000
        }
}
