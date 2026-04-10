package letmutex.compose.nativehost.diagnostics

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

private const val FRAME_TIMING_LOG_INTERVAL = 120L

internal class FrameTimingLogger : AutoCloseable {
    private val lock = Any()
    private val frameTimingWindow = FrameTimingWindow(FRAME_TIMING_LOG_INTERVAL.toInt())
    private val closing = AtomicBoolean(false)
    private val pendingLogs = LinkedBlockingQueue<LogEvent>()
    private val worker =
        Thread(
            {
                run()
            },
            "ComposeNativeHost-FrameTimingLogging",
        ).apply {
            isDaemon = true
            start()
        }

    var totalLoggedFrameCount: Long = 0L
        private set

    fun record(
        dispatchDelayNanos: Long,
        inputDrainNanos: Long,
        acquireDrawableNanos: Long,
        sceneRenderNanos: Long,
        submitNanos: Long,
    ) = synchronized(lock) {
        frameTimingWindow.record(
            dispatchDelayNanos = dispatchDelayNanos,
            inputDrainNanos = inputDrainNanos,
            acquireDrawableNanos = acquireDrawableNanos,
            sceneRenderNanos = sceneRenderNanos,
            submitNanos = submitNanos,
        )
        totalLoggedFrameCount += 1
    }

    fun shouldLogIntervalSummary(): Boolean =
        synchronized(lock) {
            totalLoggedFrameCount % FRAME_TIMING_LOG_INTERVAL == 0L
        }

    fun hasSamples(): Boolean =
        synchronized(lock) {
            frameTimingWindow.size > 0
        }

    fun enqueueSummary() {
        if (closing.get()) {
            return
        }
        pendingLogs.offer(LogEvent.Message(summary()))
    }

    override fun close() {
        if (closing.compareAndSet(false, true)) {
            pendingLogs.offer(LogEvent.Close)
        }
    }

    private fun run() {
        while (true) {
            val event =
                try {
                    pendingLogs.take()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            when (event) {
                is LogEvent.Message -> println(event.text)
                LogEvent.Close -> break
            }
        }
    }

    fun summary(): String =
        synchronized(lock) {
            "ComposeNativeHost frame timings: " +
                "win=${frameTimingWindow.size} [avg p50 p90 p99 max] " +
                "total=${frameTimingWindow.totalSummary().format()}ms " +
                "dispatch=${frameTimingWindow.dispatchSummary().format()}ms " +
                "input=${frameTimingWindow.inputSummary().format()}ms " +
                "render=${frameTimingWindow.renderSummary().format()}ms " +
                "acquire=${frameTimingWindow.acquireSummary().format()}ms " +
                "submit=${frameTimingWindow.submitSummary().format()}ms"
        }
}

private sealed interface LogEvent {
    data class Message(
        val text: String,
    ) : LogEvent

    data object Close : LogEvent
}

private data class MetricSummary(
    val avg: Double,
    val p50: Double,
    val p90: Double,
    val p99: Double,
    val max: Double,
) {
    fun format(): String = "[%.3f %.3f %.3f %.3f %.3f]".format(avg, p50, p90, p99, max)
}

private class FrameTimingWindow(
    private val capacity: Int,
) {
    private val dispatchDelayNanos = LongArray(capacity)
    private val inputDrainNanos = LongArray(capacity)
    private val acquireDrawableNanos = LongArray(capacity)
    private val sceneRenderNanos = LongArray(capacity)
    private val submitNanos = LongArray(capacity)
    private var nextIndex = 0
    var size = 0
        private set
    private var totalDispatchDelayNanos = 0L
    private var totalInputDrainNanos = 0L
    private var totalAcquireDrawableNanos = 0L
    private var totalSceneRenderNanos = 0L
    private var totalSubmitNanos = 0L

    fun record(
        dispatchDelayNanos: Long,
        inputDrainNanos: Long,
        acquireDrawableNanos: Long,
        sceneRenderNanos: Long,
        submitNanos: Long,
    ) {
        if (size == capacity) {
            totalDispatchDelayNanos -= this.dispatchDelayNanos[nextIndex]
            totalInputDrainNanos -= this.inputDrainNanos[nextIndex]
            totalAcquireDrawableNanos -= this.acquireDrawableNanos[nextIndex]
            totalSceneRenderNanos -= this.sceneRenderNanos[nextIndex]
            totalSubmitNanos -= this.submitNanos[nextIndex]
        } else {
            size += 1
        }
        this.dispatchDelayNanos[nextIndex] = dispatchDelayNanos
        this.inputDrainNanos[nextIndex] = inputDrainNanos
        this.acquireDrawableNanos[nextIndex] = acquireDrawableNanos
        this.sceneRenderNanos[nextIndex] = sceneRenderNanos
        this.submitNanos[nextIndex] = submitNanos
        totalDispatchDelayNanos += dispatchDelayNanos
        totalInputDrainNanos += inputDrainNanos
        totalAcquireDrawableNanos += acquireDrawableNanos
        totalSceneRenderNanos += sceneRenderNanos
        totalSubmitNanos += submitNanos
        nextIndex = (nextIndex + 1) % capacity
    }

    fun totalSummary(): MetricSummary =
        summarize(
            totalNanos =
                totalDispatchDelayNanos +
                    totalInputDrainNanos +
                    totalAcquireDrawableNanos +
                    totalSceneRenderNanos +
                    totalSubmitNanos,
            samples = totalSamplesNanos(),
        )

    fun dispatchSummary(): MetricSummary = summarize(totalDispatchDelayNanos, dispatchDelayNanos)

    fun inputSummary(): MetricSummary = summarize(totalInputDrainNanos, inputDrainNanos)

    fun acquireSummary(): MetricSummary = summarize(totalAcquireDrawableNanos, acquireDrawableNanos)

    fun renderSummary(): MetricSummary = summarize(totalSceneRenderNanos, sceneRenderNanos)

    fun submitSummary(): MetricSummary = summarize(totalSubmitNanos, submitNanos)

    private fun summarize(
        totalNanos: Long,
        samples: LongArray,
    ): MetricSummary =
        MetricSummary(
            avg = totalNanos.toDouble() / size.toDouble() / 1_000_000.0,
            p50 = percentileMillis(samples, 0.50),
            p90 = percentileMillis(samples, 0.90),
            p99 = percentileMillis(samples, 0.99),
            max = percentileMillis(samples, 1.0),
        )

    private fun totalSamplesNanos(): LongArray {
        val samples = LongArray(size)
        var index = 0
        while (index < size) {
            val sampleIndex = (nextIndex - size + index + capacity) % capacity
            samples[index] =
                dispatchDelayNanos[sampleIndex] +
                    inputDrainNanos[sampleIndex] +
                    acquireDrawableNanos[sampleIndex] +
                    sceneRenderNanos[sampleIndex] +
                    submitNanos[sampleIndex]
            index += 1
        }
        return samples
    }

    private fun percentileMillis(
        samples: LongArray,
        percentile: Double,
    ): Double {
        if (samples.isEmpty()) {
            return 0.0
        }
        val sorted = samples.copyOf()
        sorted.sort()
        val clampedPercentile = percentile.coerceIn(0.0, 1.0)
        val rank = ((sorted.size - 1) * clampedPercentile).toInt()
        return sorted[rank].toDouble() / 1_000_000.0
    }
}
