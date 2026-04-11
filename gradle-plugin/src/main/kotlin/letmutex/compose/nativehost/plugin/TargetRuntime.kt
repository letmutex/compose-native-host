package letmutex.compose.nativehost.plugin

enum class ComposeNativeHostTargetRuntime {
    Jvm,
    SharedLibrary,
}

internal val ComposeNativeHostTargetRuntime.plistValue: String
    get() =
        when (this) {
            ComposeNativeHostTargetRuntime.Jvm -> "jvm"
            ComposeNativeHostTargetRuntime.SharedLibrary -> "sharedLibrary"
        }

internal val ComposeNativeHostTargetRuntime.taskPrefix: String
    get() =
        when (this) {
            ComposeNativeHostTargetRuntime.Jvm -> "macos"
            ComposeNativeHostTargetRuntime.SharedLibrary -> "macos-native-image"
        }
