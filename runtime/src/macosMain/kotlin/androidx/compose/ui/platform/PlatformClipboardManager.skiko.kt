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
import androidx.compose.ui.text.AnnotatedString
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import kotlinx.coroutines.runBlocking

class PlatformClipboardManager : ClipboardManager {
    private val clipboard: Clipboard = createPlatformClipboard()

    override fun getText(): AnnotatedString? =
        runBlocking {
            clipboard.getClipEntry()?.asAwtTransferable?.readStringData()?.let(::AnnotatedString)
        }

    override fun setText(annotatedString: AnnotatedString) {
        runBlocking {
            clipboard.setClipEntry(ClipEntry(ClipboardTextTransferable(annotatedString.text)))
        }
    }

    override fun getClip(): ClipEntry? =
        runBlocking {
            clipboard.getClipEntry()
        }

    override fun setClip(clipEntry: ClipEntry?) {
        runBlocking {
            clipboard.setClipEntry(clipEntry)
        }
    }

    override val nativeClipboard: Any
        get() = clipboard.nativeClipboard
}

private class ClipboardTextTransferable(
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

private fun Transferable.readStringData(): String? {
    if (!isDataFlavorSupported(DataFlavor.stringFlavor)) {
        return null
    }
    return try {
        getTransferData(DataFlavor.stringFlavor) as? String
    } catch (_: IOException) {
        null
    } catch (_: UnsupportedFlavorException) {
        null
    }
}
