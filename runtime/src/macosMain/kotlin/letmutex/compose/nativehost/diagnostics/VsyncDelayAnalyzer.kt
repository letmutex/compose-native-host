package letmutex.compose.nativehost.diagnostics

import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.ceil

private const val VSYNC_RENDER_SIGNAL_DELAY_LOG_INTERVAL = 120

internal class VsyncDelayAnalyzer(
    private val capacity: Int = VSYNC_RENDER_SIGNAL_DELAY_LOG_INTERVAL,
) : AutoCloseable {
    private val pendingSamples = LinkedBlockingQueue<Long>()
    private val worker =
        Thread(
            {
                run()
            },
            "ComposeNativeHost-VsyncDelayAnalysis",
        ).apply {
            isDaemon = true
            start()
        }

    fun record(
        vsyncNanos: Long,
        renderSignalNanos: Long,
    ) {
        if (vsyncNanos <= 0L || renderSignalNanos < vsyncNanos) {
            return
        }
        pendingSamples.offer(renderSignalNanos - vsyncNanos)
    }

    override fun close() {
        worker.interrupt()
    }

    private fun run() {
        val batch = LongArray(capacity)
        var size = 0
        while (!Thread.currentThread().isInterrupted) {
            val sample =
                try {
                    pendingSamples.take()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            batch[size] = sample
            size += 1
            if (size == capacity) {
                logBatch(batch)
                size = 0
            }
        }
    }

    private fun logBatch(batch: LongArray) {
        val sorted = batch.copyOf().apply { sort() }
        println(
            "ComposeNativeHost vsync->requestFrame delay: " +
                "samples=${sorted.size} " +
                "min=${formatMillis(sorted.first())}ms " +
                "mean=${formatMillis(sorted.average())}ms " +
                "max=${formatMillis(sorted.last())}ms " +
                "p50=${formatMillis(percentile(sorted, 0.50))}ms " +
                "p90=${formatMillis(percentile(sorted, 0.90))}ms " +
                "p99=${formatMillis(percentile(sorted, 0.99))}ms",
        )
    }

    private fun percentile(
        sorted: LongArray,
        percentile: Double,
    ): Double {
        if (sorted.size == 1) {
            return sorted[0].toDouble()
        }
        val rank = ceil(percentile * sorted.size.toDouble()).toInt() - 1
        val index = rank.coerceIn(0, sorted.lastIndex)
        return sorted[index].toDouble()
    }

    private fun formatMillis(valueNanos: Long): String = formatMillis(valueNanos.toDouble())

    private fun formatMillis(valueNanos: Double): String = "%.3f".format(valueNanos / 1_000_000.0)
}
