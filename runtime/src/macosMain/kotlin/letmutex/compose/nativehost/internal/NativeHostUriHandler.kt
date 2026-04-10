package letmutex.compose.nativehost.internal

import androidx.compose.ui.platform.UriHandler
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

internal object NativeHostUriHandler : UriHandler {
    override fun openUri(uri: String) {
        val normalizedUri = try {
            URI(uri).toString()
        } catch (error: URISyntaxException) {
            throw IllegalArgumentException("Invalid URI", error)
        }

        try {
            ProcessBuilder("/usr/bin/open", normalizedUri).start()
        } catch (error: IOException) {
            throw IllegalArgumentException("Failed to open URI", error)
        }
    }
}
