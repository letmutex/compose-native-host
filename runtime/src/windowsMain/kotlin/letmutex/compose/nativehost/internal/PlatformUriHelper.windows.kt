package letmutex.compose.nativehost.internal

internal actual fun openSystemUri(normalizedUri: String) {
    ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", normalizedUri).start()
}
