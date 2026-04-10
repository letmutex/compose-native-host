package letmutex.compose.nativehost.sample

import letmutex.compose.nativehost.ComposeNativeHost

fun main() = ComposeNativeHost {
    HostedSampleApp(
        copy = SampleCopy(
            eyebrow = "Native macOS host",
            title = "Compose JVM",
            summaryText = "Compose renders into a native host surface while the app owns the real macOS window and lifecycle.",
            resizeHint = "Resize the window. The hosted scene should track the new drawable size immediately.",
        )
    )
}
