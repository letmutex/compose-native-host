package letmutex.compose.nativehost.plugin

import java.util.Locale

enum class ComposeNativeHostTargetRuntime {
    Jvm,
    SharedLibrary,
}

internal val hostOsPrefix: String
    get() = if (System.getProperty("os.name").lowercase(Locale.US).contains("win")) "windows" else "macos"

internal val ComposeNativeHostTargetRuntime.plistValue: String
    get() =
        when (this) {
            ComposeNativeHostTargetRuntime.Jvm -> "jvm"
            ComposeNativeHostTargetRuntime.SharedLibrary -> "sharedLibrary"
        }

internal val ComposeNativeHostTargetRuntime.taskPrefix: String
    get() =
        when (this) {
            ComposeNativeHostTargetRuntime.Jvm -> hostOsPrefix
            ComposeNativeHostTargetRuntime.SharedLibrary -> "${hostOsPrefix}NativeImage"
        }

