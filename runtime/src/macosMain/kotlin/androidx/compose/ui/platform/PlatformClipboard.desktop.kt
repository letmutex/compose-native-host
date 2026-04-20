/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalComposeUiApi::class)

package androidx.compose.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard as AwtClipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import kotlin.text.Charsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import letmutex.compose.nativehost.internal.MacOsComposeBridge

typealias NativeClipboard = Any

internal class AwtPlatformClipboard internal constructor() : Clipboard {
    private val systemClipboard by lazy {
        try {
            Toolkit.getDefaultToolkit().systemClipboard
        } catch (_: HeadlessException) {
            null
        }
    }

    override suspend fun getClipEntry(): ClipEntry? {
        val transferable = systemClipboard?.getContents(null) ?: return null
        val flavors = transferable.transferDataFlavors
        if (flavors?.size == 0) return null
        return ClipEntry(transferable)
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val transferable = clipEntry?.asAwtTransferable
        systemClipboard?.setContents(
            transferable ?: EmptyTransferable,
            transferable as? ClipboardOwner,
        )
    }

    override val nativeClipboard: NativeClipboard
        get() = systemClipboard ?: error("systemClipboard is not available in headless mode")
}

private class NativeHostPlatformClipboard : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? {
        val text = readNativeHostClipboardText() ?: return null
        return ClipEntry(StringTransferable(text))
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.asAwtTransferable?.readStringData().orEmpty()
        writeNativeHostClipboardText(text)
    }

    override val nativeClipboard: NativeClipboard
        get() = NativeHostClipboard
}

private object NativeHostClipboard

/**
 * Returns [java.awt.datatransfer.Clipboard] instance if it's available, or null otherwise.
 * It might throw an exception when accessed in a headless mode.
 */
@ExperimentalComposeUiApi
val Clipboard.awtClipboard: AwtClipboard?
    get() = nativeClipboard as? AwtClipboard

/**
 * A wrapper for platform clip entry instance which can be used to access
 * or set the Clipboard content. The actual implementation may vary
 * depending on the underlying GUI toolkit and on the actual implementation
 * of Clipboard.nativeClipboard.
 *
 * See [asAwtTransferable] to access [Transferable].
 */
class ClipEntry
@ExperimentalComposeUiApi
constructor(
    @property:ExperimentalComposeUiApi
    val nativeClipEntry: Any,
) {
    // TODO https://youtrack.jetbrains.com/issue/CMP-1260/ClipboardManager.-Implement-getClip-getClipMetadata-setClip
    val clipMetadata: ClipMetadata
        get() = TODO("ClipMetadata is not implemented. Consider using nativeClipboard")
}

class ClipMetadata

/**
 * Returns a [Transferable] instance if the [ClipEntry.nativeClipEntry]
 * type is [Transferable]. Otherwise, it returns null.
 */
@ExperimentalComposeUiApi
val ClipEntry.asAwtTransferable: Transferable?
    get() = nativeClipEntry as? Transferable

internal fun createPlatformClipboard(): Clipboard {
    return if (shouldUseNativeHostClipboard()) {
        NativeHostPlatformClipboard()
    } else {
        AwtPlatformClipboard()
    }
}

private fun shouldUseNativeHostClipboard(): Boolean {
    if (!MacOsComposeBridge.isAvailable()) {
        return false
    }
    return MacOsComposeBridge.isSharedLibraryRuntime()
}

private suspend fun readNativeHostClipboardText(): String? =
    withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("pbpaste").withUtf8Locale().start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor()
            output.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

private suspend fun writeNativeHostClipboardText(text: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("pbcopy").withUtf8Locale().start()
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(text)
            }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

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
    if (value.isNullOrBlank()) {
        return false
    }
    val normalizedValue = value.uppercase()
    return normalizedValue.endsWith("UTF-8") || normalizedValue.endsWith("UTF8")
}

private fun Transferable.readStringData(): String? {
    if (!isDataFlavorSupported(DataFlavor.stringFlavor)) {
        return null
    }
    return try {
        getTransferData(DataFlavor.stringFlavor) as? String
    } catch (_: IOException) {
        null
    }
}

private class StringTransferable(
    private val value: String,
) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = supportedFlavors

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor in supportedFlavors

    override fun getTransferData(flavor: DataFlavor): Any {
        if (flavor == DataFlavor.stringFlavor) {
            return value
        }
        throw UnsupportedFlavorException(flavor)
    }

    companion object {
        private val supportedFlavors = arrayOf(DataFlavor.stringFlavor)
    }
}

private object EmptyTransferable : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = emptyArray()

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = false

    override fun getTransferData(flavor: DataFlavor?): Any {
        throw UnsupportedFlavorException(flavor)
    }
}
