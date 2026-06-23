@file:OptIn(InternalCoroutinesApi::class)

package letmutex.compose.nativehost.internal

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import letmutex.compose.nativehost.NativeHostUiThread

internal class NativeHostMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int = Int.MAX_VALUE

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher {
        if (letmutex.compose.nativehost.isComposeNativeHostAvailable()) {
            return NativeHostMainDispatcher
        }
        return ComposeHostSwingMainDispatcher
    }

    override fun hintOnError(): String =
        "ComposeNativeHost main dispatcher is only active when the native host bridge is loaded."
}

private object NativeHostMainDispatcher : MainCoroutineDispatcher() {
    override val immediate: MainCoroutineDispatcher
        get() = this

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        NativeHostUiThread.shared.post(block)
    }

    override fun limitedParallelism(
        parallelism: Int,
        name: String?,
    ): CoroutineDispatcher = super.limitedParallelism(parallelism, name)
}
