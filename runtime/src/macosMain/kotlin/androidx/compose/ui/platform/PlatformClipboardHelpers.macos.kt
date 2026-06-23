package androidx.compose.ui.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.text.Charsets

private fun ProcessBuilder.withUtf8Locale(): ProcessBuilder =
    apply {
        val environment = environment()
        val utf8Locale =
            environment["LANG"]
                ?.takeIf(::isUtf8Locale)
                ?: "en_US.UTF-8"
        environment["LANG"] = utf8Locale
        if (!isUtf8Locale(environment["LC_ALL"])) {
            environment.remove("LC_ALL")
        }
        if (!isUtf8Locale(environment["LC_CTYPE"])) {
            environment["LC_CTYPE"] = utf8Locale
        }
    }

private fun isUtf8Locale(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalizedValue = value.uppercase()
    return normalizedValue.endsWith("UTF-8") || normalizedValue.endsWith("UTF8")
}

internal actual suspend fun readPlatformClipboardText(): String? =
    withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("pbpaste").withUtf8Locale().start()
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
            val process = ProcessBuilder("pbcopy").withUtf8Locale().start()
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

