# Swift (CVDisplayLink) to Kotlin (`requestFrame`) Delay


### NSLock + Swift Async Signal Thread + JNI Invoke

```txt
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.005ms mean=0.018ms max=0.055ms p50=0.014ms p90=0.035ms p99=0.051ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.005ms mean=0.017ms max=0.055ms p50=0.014ms p90=0.029ms p99=0.051ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.008ms mean=0.026ms max=0.066ms p50=0.018ms p90=0.046ms p99=0.063ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.005ms mean=0.020ms max=0.065ms p50=0.016ms p90=0.037ms p99=0.047ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.006ms mean=0.018ms max=0.044ms p50=0.019ms p90=0.029ms p99=0.037ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.005ms mean=0.019ms max=0.051ms p50=0.016ms p90=0.039ms p99=0.047ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.006ms mean=0.017ms max=0.045ms p50=0.011ms p90=0.031ms p99=0.042ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.008ms mean=0.020ms max=0.056ms p50=0.016ms p90=0.038ms p99=0.051ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.005ms mean=0.017ms max=0.045ms p50=0.011ms p90=0.034ms p99=0.041ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.006ms mean=0.020ms max=0.055ms p50=0.016ms p90=0.038ms p99=0.046ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.009ms mean=0.020ms max=0.055ms p50=0.015ms p90=0.038ms p99=0.051ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.006ms mean=0.018ms max=0.051ms p50=0.014ms p90=0.035ms p99=0.048ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.006ms mean=0.019ms max=0.062ms p50=0.014ms p90=0.035ms p99=0.054ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.006ms mean=0.026ms max=0.054ms p50=0.025ms p90=0.045ms p99=0.053ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.005ms mean=0.031ms max=2.031ms p50=0.010ms p90=0.029ms p99=0.049ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.005ms mean=0.017ms max=0.053ms p50=0.012ms p90=0.031ms p99=0.043ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.006ms mean=0.018ms max=0.051ms p50=0.011ms p90=0.037ms p99=0.048ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.005ms mean=0.022ms max=0.053ms p50=0.018ms p90=0.041ms p99=0.050ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.005ms mean=0.017ms max=0.057ms p50=0.014ms p90=0.027ms p99=0.048ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.006ms mean=0.023ms max=0.058ms p50=0.023ms p90=0.043ms p99=0.056ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.008ms mean=0.021ms max=0.306ms p50=0.015ms p90=0.036ms p99=0.054ms
ComposeNativeHost vsync->requestFrame delay: samples=120 min=0.006ms mean=0.017ms max=0.045ms p50=0.013ms p90=0.038ms p99=0.045ms
```
