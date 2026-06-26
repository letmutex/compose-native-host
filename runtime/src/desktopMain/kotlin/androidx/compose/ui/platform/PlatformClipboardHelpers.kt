package androidx.compose.ui.platform

internal expect suspend fun readPlatformClipboardText(): String?

internal expect suspend fun writePlatformClipboardText(text: String): Boolean
