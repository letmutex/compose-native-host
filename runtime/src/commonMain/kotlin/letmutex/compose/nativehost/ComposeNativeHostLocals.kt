package letmutex.compose.nativehost

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/** Composition local that exposes the active native host handle when available. */
val LocalComposeNativeHostHandle = staticCompositionLocalOf<ComposeNativeHostHandle?> { null }

/** Composition local that exposes the active native host window info when available. */
val LocalComposeNativeHostWindowInfo = compositionLocalOf<WindowInfo?> { null }
