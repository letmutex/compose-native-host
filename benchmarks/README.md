# macOS Native Host Benchmarks

JMH benchmarks for [`NativeHostUiThread`](../compose-native-host/src/commonMain/kotlin/letmutex/compose/nativehost/NativeHostUiThread.kt).

## Run

Run the default suite:

```bash
./gradlew :composeNativeHostMacosBenchmarks:jmh
```

Run one benchmark in isolation:

```bash
./gradlew :composeNativeHostMacosBenchmarks:jmh -PjmhInclude=NativeHostUiThreadBenchmark.invokeRoundTrip
```

## UI Thread Benchmark Results

Measured with JDK `17.0.18` (Corretto), JMH `1.37`, `3` warmup iterations, `5` measurement iterations, and `1` fork.

### MpscArrayQueue + LockSupport wakeup

`LockSupport.park/unpark` replaces the semaphore wakeup path. It helps single-operation wakeups, but sustained burst draining regresses sharply, so this experiment is not kept.

Commit: `Discarded`

| Benchmark | Mode | Result |
| --- | --- | --- |
| `startup` | `thrpt` | `40.594 +- 0.592 ops/ms` |
| `dispatchInlineOnUiThread` | `thrpt` | `268.555 +- 25.264 ops/ms` |
| `invokeRoundTrip` | `thrpt` | `257.137 +- 24.401 ops/ms` |
| `postRoundTrip` | `thrpt` | `264.498 +- 2.645 ops/ms` |
| `postBurstX10` | `thrpt` | `252.931 +- 5.841 ops/ms` |
| `postBurstX100` | `thrpt` | `83.451 +- 3.751 ops/ms` |
| `postBurstX1000` | `thrpt` | `9.950 +- 0.129 ops/ms` |
| `postBacklogX10Blocking1ms` | `thrpt` | `0.080 +- 0.001 ops/ms` |
| `postBacklogX100Blocking1ms` | `thrpt` | `0.011 +- 0.003 ops/ms` |
| `postBacklogX1000Blocking1ms` | `thrpt` | `0.004 +- 0.003 ops/ms` |

### MpscArrayQueue + semaphore wakeup

`MpscArrayQueue` replaces `LinkedBlockingQueue`, while a `Semaphore` keeps the single consumer parked until producers publish work. This improves burst-heavy posting throughput the most, with only small changes in synchronous handoff and no meaningful change in the blocked-backlog cases.

Commit: `a290059`

| Benchmark | Mode | Result |
| --- | --- | --- |
| `startup` | `thrpt` | `39.509 +- 2.427 ops/ms` |
| `dispatchInlineOnUiThread` | `thrpt` | `241.782 +- 22.845 ops/ms` |
| `invokeRoundTrip` | `thrpt` | `260.161 +- 2.820 ops/ms` |
| `postRoundTrip` | `thrpt` | `254.268 +- 9.059 ops/ms` |
| `postBurstX10` | `thrpt` | `250.607 +- 3.893 ops/ms` |
| `postBurstX100` | `thrpt` | `196.674 +- 16.323 ops/ms` |
| `postBurstX1000` | `thrpt` | `71.027 +- 8.517 ops/ms` |
| `postBacklogX10Blocking1ms` | `thrpt` | `0.080 +- 0.001 ops/ms` |
| `postBacklogX100Blocking1ms` | `thrpt` | `0.008 +- 0.001 ops/ms` |
| `postBacklogX1000Blocking1ms` | `thrpt` | `0.001 +- 0.001 ops/ms` |

### MpscAtomicArrayQueue + semaphore wakeup

`MpscAtomicArrayQueue` replaces the native-image fallback queue, while keeping the same semaphore wakeup path. It stays close to the `MpscArrayQueue` JVM results without relying on the Unsafe-based implementation.

Commit: `ba74dfc`

| Benchmark | Mode | Result |
| --- | --- | --- |
| `startup` | `thrpt` | `40.714 +- 0.692 ops/ms` |
| `dispatchInlineOnUiThread` | `thrpt` | `260.142 +- 3.018 ops/ms` |
| `invokeRoundTrip` | `thrpt` | `263.518 +- 7.906 ops/ms` |
| `postRoundTrip` | `thrpt` | `265.402 +- 15.082 ops/ms` |
| `postBurstX10` | `thrpt` | `244.400 +- 12.600 ops/ms` |
| `postBurstX100` | `thrpt` | `198.110 +- 10.791 ops/ms` |
| `postBurstX1000` | `thrpt` | `70.813 +- 5.881 ops/ms` |
| `postBacklogX10Blocking1ms` | `thrpt` | `0.067 +- 0.001 ops/ms` |
| `postBacklogX100Blocking1ms` | `thrpt` | `0.007 +- 0.002 ops/ms` |
| `postBacklogX1000Blocking1ms` | `thrpt` | `0.001 +- 0.001 ops/ms` |

### FutureTask + enqueue guards

`FutureTask` is used for `callOnUiThread()`, startup adds a ready fast-path, and enqueue/close handling is stricter. This mostly improves burst and synchronous handoff throughput; the blocked-backlog cases stay flat because they are dominated by the injected `1 ms` stall.

Commit: `ddc70e8`

| Benchmark | Mode | Result |
| --- | --- | --- |
| `startup` | `thrpt` | `37.794 +- 0.958 ops/ms` |
| `dispatchInlineOnUiThread` | `thrpt` | `252.509 +- 1.803 ops/ms` |
| `invokeRoundTrip` | `thrpt` | `258.473 +- 3.691 ops/ms` |
| `postRoundTrip` | `thrpt` | `251.520 +- 3.875 ops/ms` |
| `postBurstX10` | `thrpt` | `233.357 +- 6.883 ops/ms` |
| `postBurstX100` | `thrpt` | `178.533 +- 2.918 ops/ms` |
| `postBurstX1000` | `thrpt` | `16.551 +- 0.084 ops/ms` |
| `postBacklogX10Blocking1ms` | `thrpt` | `0.067 +- 0.001 ops/ms` |
| `postBacklogX100Blocking1ms` | `thrpt` | `0.008 +- 0.002 ops/ms` |
| `postBacklogX1000Blocking1ms` | `thrpt` | `0.001 +- 0.001 ops/ms` |

### LinkedBlockingQueue + CountDownLatch

`LinkedBlockingQueue` feeds the UI thread, `CountDownLatch` handles readiness and `callOnUiThread()` waiting, and the queue is drained by a daemon thread. The benchmark set now separates pure burst posting from blocked-UI backlog behavior.

Commit: `8244ab1`

| Benchmark | Mode | Result |
| --- | --- | --- |
| `startup` | `thrpt` | `38.454 +- 0.488 ops/ms` |
| `dispatchInlineOnUiThread` | `thrpt` | `242.944 +- 3.119 ops/ms` |
| `invokeRoundTrip` | `thrpt` | `247.908 +- 6.447 ops/ms` |
| `postRoundTrip` | `thrpt` | `250.839 +- 5.443 ops/ms` |
| `postBurstX10` | `thrpt` | `234.224 +- 17.522 ops/ms` |
| `postBurstX100` | `thrpt` | `133.619 +- 3.043 ops/ms` |
| `postBurstX1000` | `thrpt` | `11.790 +- 0.238 ops/ms` |
| `postBacklogX10Blocking1ms` | `thrpt` | `0.068 +- 0.001 ops/ms` |
| `postBacklogX100Blocking1ms` | `thrpt` | `0.008 +- 0.002 ops/ms` |
| `postBacklogX1000Blocking1ms` | `thrpt` | `0.001 +- 0.001 ops/ms` |
