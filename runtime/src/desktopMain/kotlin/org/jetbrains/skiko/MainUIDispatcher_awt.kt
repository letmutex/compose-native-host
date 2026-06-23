package org.jetbrains.skiko

import java.lang.Runnable
import kotlinx.coroutines.CoroutineDispatcher
import letmutex.compose.nativehost.NativeHostUiThread
import letmutex.compose.nativehost.internal.ComposeHostSwingDispatcher

val MainUIDispatcher: CoroutineDispatcher
    get() =
        if (letmutex.compose.nativehost.isComposeNativeHostAvailable()) {
            NativeHostUiThread.shared.dispatcher
        } else {
            ComposeHostSwingDispatcher
        }
