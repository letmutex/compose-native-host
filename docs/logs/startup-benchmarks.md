# Startup Benchmarks

Measured with `compose-native-host/scripts/bench_startup_times.ts`.

Configuration:

- 3 warmup runs
- 10 measured runs
- 5 second timeout per run

## Results

```text
== AppKit ==
[NativeHost] [81.20/87.78/103.05 ms] Phase: Window Attached
[NativeHost] [83.11/90.38/105.81 ms] Phase: JVM Thread Start
[NativeHost] [93.03/100.90/115.64 ms] Phase: Native Window First Draw

== SwiftUi ==
[NativeHost] [95.24/98.79/106.87 ms] Phase: SwiftUI View Created
[NativeHost] [107.07/112.80/121.31 ms] Phase: Window Attached
[NativeHost] [109.14/115.52/123.20 ms] Phase: JVM Thread Start
[NativeHost] [113.78/120.49/127.67 ms] Phase: Native Window First Draw

== Mixed ==
[NativeHost] [85.90/89.97/93.09 ms] Phase: SwiftUI View Created
[NativeHost] [97.39/102.41/107.28 ms] Phase: Window Attached
[NativeHost] [99.71/104.93/110.88 ms] Phase: JVM Thread Start
[NativeHost] [104.13/109.09/116.86 ms] Phase: Native Window First Draw
```

## Notes

- `AppKit` is the startup baseline and has the lowest overhead.
- `Mixed` still pays SwiftUI view creation cost, but window ownership remains explicit in AppKit.
- `SwiftUi` has the highest startup overhead and the most window-lifecycle quirks.
