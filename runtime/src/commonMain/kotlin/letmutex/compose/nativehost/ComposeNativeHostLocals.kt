package letmutex.compose.nativehost

import androidx.compose.runtime.staticCompositionLocalOf

/** Composition local that exposes the active native host handle when available. */
val LocalComposeNativeHostHandle = staticCompositionLocalOf<ComposeNativeHostHandle?> { null }
