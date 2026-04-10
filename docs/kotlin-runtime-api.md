# Kotlin Runtime API

Public Kotlin API surface for the `runtime` module.

This snapshot includes the public/common API and the public macOS-specific API that is currently exposed from the artifact. Internal types are intentionally omitted.

## Common API

### Hosted entry point

```kotlin
// Entry point for hosted Compose content inside the native host.
expect fun ComposeNativeHost(content: @Composable ComposeNativeHostScope.() -> Unit)
```

### Host scope and event bridge

```kotlin
interface ComposeNativeHostScope {
    // Event bridge back to the native host.
    val host: ComposeNativeHostHandle
    // Latest window metrics reported by the host.
    val windowInfo: State<WindowInfo>
}

interface ComposeNativeHostHandle {
    // Sends an app-defined event to the native host.
    fun sendEvent(
        name: String,
        payload: String? = null,
    )
}
```

### Composition local

```kotlin
// Active host handle exposed to hosted composables.
val LocalComposeNativeHostHandle = staticCompositionLocalOf<ComposeNativeHostHandle?> { null }
```

### Window info

```kotlin
@JvmInline
value class WindowInfo {
    // Window width in physical pixels.
    val width: Int
    // Window height in physical pixels.
    val height: Int
    // Backing scale factor.
    val scale: Float
    // Display refresh rate in Hz.
    val refreshRate: Int
    // Whether the native window is focused.
    val isFocused: Boolean
}
```

### UI thread

```kotlin
class NativeHostUiThread(
    // Thread name used for the managed UI thread.
    threadName: String = "ComposeNativeHost-Ui",
) {
    companion object {
        // Shared process-wide UI thread instance.
        val shared: NativeHostUiThread
    }

    // Coroutine dispatcher backed by this UI thread.
    val dispatcher: CoroutineDispatcher

    // Starts the UI thread if it is not already running.
    fun ensureStarted()
    // Returns true when called from the managed UI thread.
    fun isUiThread(): Boolean
    // Dumps the UI thread state and stack trace.
    fun dumpState(): String
    // Runs immediately on the UI thread or posts otherwise.
    fun dispatch(runnable: Runnable)
    // Posts work to the UI thread without waiting.
    fun post(runnable: Runnable)
    // Runs work on the UI thread and waits for the result.
    fun <T> callOnUiThread(block: () -> T): T
    // Stops the UI thread and rejects later work.
    fun close()
}
```

## macOS/JVM API

### macOS availability and actual hosted entry point

```kotlin
// macOS implementation of the hosted Compose entry point.
actual fun ComposeNativeHost(content: @Composable ComposeNativeHostScope.() -> Unit)

// Returns true when the native macOS bridge is loaded and usable.
fun isComposeNativeHostAvailable(): Boolean
```
