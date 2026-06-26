package androidx.compose.ui.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.text.Charsets

internal actual suspend fun readPlatformClipboardText(): String? =
    withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("powershell", "-NoProfile", "-Command", "Get-Clipboard").start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor()
            output.trimEnd('\r', '\n').ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

internal actual suspend fun writePlatformClipboardText(text: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("clip").start()
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
