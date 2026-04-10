package letmutex.compose.nativehost

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * Scope passed to hosted content so app code can inspect host state and emit events.
 */
interface ComposeNativeHostScope {
    /**
     * Event bridge exposed to hosted content.
     */
    val host: ComposeNativeHostHandle

    /**
     * Latest window info reported by the native host runtime.
     */
    val windowInfo: State<WindowInfo>
}

/**
 * Handle for sending app-defined events back to the native host runtime.
 */
interface ComposeNativeHostHandle {
    /**
     * Sends an event name and optional payload to the host runtime.
     */
    fun sendEvent(
        name: String,
        payload: String? = null,
    )
}

internal class ComposeNativeHostHandleImpl(
    private val eventDispatcher: ComposeNativeHostEventDispatcher,
) : ComposeNativeHostHandle {
    override fun sendEvent(
        name: String,
        payload: String?,
    ) {
        eventDispatcher.sendEvent(name, payload)
    }
}

/**
 * Callback used by the host runtime to receive app events from Compose content.
 */
internal fun interface ComposeNativeHostEventDispatcher {
    /**
     * Delivers an app event emitted from hosted content.
     */
    fun sendEvent(
        name: String,
        payload: String?,
    )
}

internal class ComposeNativeHostScopeImpl(
    eventDispatcher: ComposeNativeHostEventDispatcher,
) : ComposeNativeHostScope {
    override val host: ComposeNativeHostHandle = ComposeNativeHostHandleImpl(eventDispatcher)

    override val windowInfo: MutableState<WindowInfo> =
        WindowInfo(width = 0, height = 0, scale = 1f, refreshRate = 60, isFocused = true)
            .let(::mutableStateOf)
}
