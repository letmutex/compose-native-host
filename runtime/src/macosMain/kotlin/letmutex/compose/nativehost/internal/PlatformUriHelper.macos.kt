package letmutex.compose.nativehost.internal

internal actual fun openSystemUri(normalizedUri: String) {
    ProcessBuilder("/usr/bin/open", normalizedUri).start()
}
