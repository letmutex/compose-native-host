package androidx.lifecycle

import letmutex.compose.nativehost.NativeHostUiThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext

internal object MainDispatcherChecker {
    @Volatile
    private var mainDispatcherThread: Thread? = null

    private fun resolveMainDispatcherThread(): Boolean {
        if (mainDispatcherThread != null) {
            return true
        }
        if (NativeHostUiThread.shared.isUiThread()) {
            mainDispatcherThread = Thread.currentThread()
            return true
        }
        if (!Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext)) {
            mainDispatcherThread = Thread.currentThread()
            return true
        }
        synchronized(this) {
            if (mainDispatcherThread != null) {
                return true
            }
            if (NativeHostUiThread.shared.isUiThread()) {
                mainDispatcherThread = Thread.currentThread()
                return true
            }
            if (!Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext)) {
                mainDispatcherThread = Thread.currentThread()
                return true
            }
            return try {
                runBlocking(Dispatchers.Main) {
                    mainDispatcherThread = Thread.currentThread()
                }
                true
            } catch (_: IllegalStateException) {
                false
            }
        }
    }

    fun isMainDispatcherThread(): Boolean {
        if (NativeHostUiThread.shared.isUiThread()) {
            return true
        }
        val currentThread = Thread.currentThread()
        if (currentThread === mainDispatcherThread) {
            return true
        }
        return if (resolveMainDispatcherThread()) {
            currentThread === mainDispatcherThread
        } else {
            true
        }
    }
}
