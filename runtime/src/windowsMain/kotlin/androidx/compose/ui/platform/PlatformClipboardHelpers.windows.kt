package androidx.compose.ui.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import letmutex.compose.nativehost.internal.WindowsComposeBridgeBindings

internal actual suspend fun readPlatformClipboardText(): String? =
    withContext(Dispatchers.IO) {
        WindowsComposeBridgeBindings.nativeHostReadClipboardText()?.ifEmpty { null }
    }

internal actual suspend fun writePlatformClipboardText(text: String): Boolean =
    withContext(Dispatchers.IO) {
        WindowsComposeBridgeBindings.nativeHostWriteClipboardText(text)
    }
