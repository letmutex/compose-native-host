package org.jetbrains.skiko

import java.lang.Runnable
import kotlinx.coroutines.CoroutineDispatcher
import letmutex.compose.nativehost.NativeHostUiThread
import letmutex.compose.nativehost.internal.ComposeHostSwingDispatcher
import letmutex.compose.nativehost.internal.MacOsComposeBridge

val MainUIDispatcher: CoroutineDispatcher
    get() =
        if (MacOsComposeBridge.isAvailable()) {
            NativeHostUiThread.shared.dispatcher
        } else {
            ComposeHostSwingDispatcher
        }
