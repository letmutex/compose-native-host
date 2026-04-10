package letmutex.compose.nativehost

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.infra.Blackhole

@Threads(1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class NativeHostUiThreadBenchmark {
    @State(Scope.Thread)
    open class StartupState {
        lateinit var uiThread: NativeHostUiThread

        @Setup(Level.Invocation)
        fun setUp() {
            uiThread = NativeHostUiThread(threadName = "ComposeNativeHost-Ui-Benchmark")
        }

        @TearDown(Level.Invocation)
        fun tearDown() {
            uiThread.close()
        }
    }

    @State(Scope.Benchmark)
    open class RunningState {
        lateinit var uiThread: NativeHostUiThread

        @Setup(Level.Trial)
        fun setUp() {
            uiThread = NativeHostUiThread(threadName = "ComposeNativeHost-Ui-Benchmark")
            uiThread.ensureStarted()
        }

        @TearDown(Level.Trial)
        fun tearDown() {
            uiThread.close()
        }
    }

    @Benchmark
    fun startup(state: StartupState) {
        state.uiThread.ensureStarted()
    }

    @Benchmark
    fun invokeRoundTrip(
        state: RunningState,
        blackhole: Blackhole,
    ) {
        blackhole.consume(state.uiThread.callOnUiThread { 1 })
    }

    @Benchmark
    fun postRoundTrip(state: RunningState) {
        val latch = CountDownLatch(1)
        state.uiThread.post(
            Runnable {
                latch.countDown()
            },
        )
        latch.await()
    }

    @Benchmark
    fun postBurstX10(state: RunningState) {
        postBatch(state, count = 10)
    }

    @Benchmark
    fun postBurstX100(state: RunningState) {
        postBatch(state, count = 100)
    }

    @Benchmark
    fun postBurstX1000(state: RunningState) {
        postBatch(state, count = 1000)
    }

    @Benchmark
    fun postBacklogX10Blocking1ms(state: RunningState) {
        postBatch(
            state = state,
            count = 10,
            perTaskBlockNanos = TimeUnit.MILLISECONDS.toNanos(1),
        )
    }

    @Benchmark
    fun postBacklogX100Blocking1ms(state: RunningState) {
        postBatch(
            state = state,
            count = 100,
            perTaskBlockNanos = TimeUnit.MILLISECONDS.toNanos(1),
        )
    }

    @Benchmark
    fun postBacklogX1000Blocking1ms(state: RunningState) {
        postBatch(
            state = state,
            count = 1000,
            perTaskBlockNanos = TimeUnit.MILLISECONDS.toNanos(1),
        )
    }

    @Benchmark
    fun dispatchInlineOnUiThread(
        state: RunningState,
        blackhole: Blackhole,
    ) {
        blackhole.consume(
            state.uiThread.callOnUiThread {
                state.uiThread.dispatch(Runnable {})
                1
            },
        )
    }

    private fun postBatch(
        state: RunningState,
        count: Int,
        perTaskBlockNanos: Long = 0L,
    ) {
        val latch = CountDownLatch(count)
        repeat(count) {
            state.uiThread.post(
                Runnable {
                    if (perTaskBlockNanos > 0L) {
                        LockSupport.parkNanos(perTaskBlockNanos)
                    }
                    latch.countDown()
                },
            )
        }
        latch.await()
    }
}
