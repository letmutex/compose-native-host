package letmutex.compose.nativehost.plugin

internal const val mainDispatcherFactoryServiceEntry =
    "META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory"

internal const val nativeHostMainDispatcherFactoryClass =
    "letmutex.compose.nativehost.internal.NativeHostMainDispatcherFactory"

internal fun nativeHostMainDispatcherFactoryServiceContents(): ByteArray =
    "$nativeHostMainDispatcherFactoryClass\n".toByteArray(Charsets.UTF_8)
