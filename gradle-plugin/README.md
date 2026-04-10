# Compose Native Host Plugin

Gradle plugin id: `io.github.letmutex.compose.nativehost`

This plugin builds and packages the macOS native host used by the Compose Native Host runtime. It supports:

- JVM-hosted app bundles
- GraalVM shared-library hosted app bundles
- Staged `.app` bundles for local runs
- Patched Compose Desktop distributables

## Documentation

- [../README.md](../README.md): project overview, setup, and runtime docs index
- [../docs/kotlin-runtime-api.md](../docs/kotlin-runtime-api.md): public Kotlin runtime API snapshot
- [../docs/swift-runtime-api.md](../docs/swift-runtime-api.md): public Swift runtime API snapshot

## Full Configuration

```kotlin
import letmutex.compose.nativehost.plugin.ComposeNativeHostTargetRuntime

composeNativeHost {
    // Application name used for bundle metadata and as the default executable base name.
    appName.set("Example App")

    // CFBundleIdentifier written into Info.plist.
    bundleIdentifier.set("com.example.app")

    // Optional explicit executable name. Defaults to a sanitized appName.
    executableName.set("ExampleApp")

    // Optional JVM target name that provides the hosted app classes. Defaults to "desktop".
    jvmTargetName.set("desktop")

    // Optional JVM args passed to the native launcher when using the JVM runtime path.
    jvmArgs(
        "-Xmx2g",
        "-Dexample.flag=true",
    )

    macos {
        // Optional final .app name. Defaults to "<executableName>.app".
        bundleName.set("ExampleApp.app")

        // Optional display name shown by macOS. Defaults to appName.
        bundleDisplayName.set("Example App")

        // Optional CFBundleVersion. Defaults to "1".
        bundleVersion.set("42")

        // Optional CFBundleShortVersionString. Defaults to "1.0.0".
        shortVersion.set("1.2.3")

        // Optional minimum supported macOS version. Defaults to "13.0".
        minimumSystemVersion.set("13.0")

        // Optional codesign identity. Defaults to "-".
        codeSignIdentity.set("-")

        // Optional Swift launcher sources dir. Defaults to "src/macosMain/swift".
        launcherSourcesDir.set(layout.projectDirectory.dir("src/macosMain/swift"))

        // Optional extra files or directories copied into the bundle.
        // "runtimes" controls which runtime bundles receive this content.
        bundleContent(
            from = layout.projectDirectory.file("extra/MyTool"),
            into = "Contents/Resources/tools",
            executable = true,
            builtBy = "buildMyTool",
            runtimes = setOf(
                ComposeNativeHostTargetRuntime.Jvm,
                ComposeNativeHostTargetRuntime.SharedLibrary,
            ),
        )

        // Example: only copy into JVM-hosted bundles.
        bundleContent(
            from = layout.buildDirectory.file("generated/sqlite/libsqliteJni.dylib"),
            into = "Contents/app/sqlite",
            executable = true,
            builtBy = "extractBundledSqliteNative",
            runtimes = setOf(ComposeNativeHostTargetRuntime.Jvm),
        )
    }

    nativeImage {
        // Required to enable native-image support.
        // Registers Kotlin top-level main classes into the generated Graal entrypoint switch.
        mainClasses("com.example.MainKt")

        // Optional helper Java sources dir. Defaults to "src/graalInterop/java".
        helperSourcesDir.set(layout.projectDirectory.dir("src/graalInterop/java"))

        // Optional helper resources dir. Defaults to "src/graalInterop/resources".
        helperResourcesDir.set(layout.projectDirectory.dir("src/graalInterop/resources"))

        // Optional output shared library base name. Defaults to "libcompose-native-host-runtime".
        sharedLibraryBaseName.set("libcompose-native-host-runtime")

        // Optional package prefixes scanned from the packaged app jar for generated JNI config.
        jniConfigPackages.addAll(
            listOf(
                "org/jetbrains/skia/",
                "org/jetbrains/skiko/",
                "com/example/",
            ),
        )

        // Optional extra fully qualified class names always added to generated JNI config.
        jniConfigTypes.addAll(
            listOf(
                "com.example.NativeBindings",
            ),
        )

        // Optional extra resource patterns passed to native-image via -H:IncludeResources.
        // Default patterns already include Compose resources and Skiko sha256 metadata.
        includeResourcePatterns.add("index.js")

        // Optional jar entry regex patterns for shared libraries copied into
        // Contents/Resources/native beside the runtime dylib.
        // By default the plugin already collects the Skiko macOS dylib.
        // Add extra patterns when the shared-library runtime must load more dylibs from disk.
        // Use addAll(...) to append without replacing the default Skiko pattern.
        collectedSharedLibraryPatterns.addAll(
            listOf(
                ".*libsqliteJni\\.dylib",
                ".*libhtmd_bridge\\.dylib",
            ),
        )

        // Optional packages preserved in the native-image build for lookup stability.
        preservePackages.add("com.example")

        // Optional extra native-image args for staged shared-library builds.
        devExtraBuildArgs("-Ob")

        // Optional extra native-image args for distributable shared-library builds.
        bundleExtraBuildArgs("--verbose")
    }
}
```

## Runtime Targets

- `ComposeNativeHostTargetRuntime.Jvm`: JVM-hosted native app bundles.
- `ComposeNativeHostTargetRuntime.SharedLibrary`: GraalVM shared-library-hosted native app bundles.

If `bundleContent(..., runtimes = ...)` is omitted, the content is copied into both runtime bundle variants.

## Main Tasks

- `macosRun`: Launch the staged JVM-hosted native app bundle.
- `macosNativeImageRun`: Launch the staged shared-library native app bundle.
- `macosBundleInfo`: Print the staged JVM bundle path.
- `macosNativeImageBundleInfo`: Print the staged shared-library bundle path.
- `macosStageAppBundle`: Stage the JVM-hosted native app bundle.
- `macosNativeImageStageAppBundle`: Stage the shared-library native app bundle.

Native-image support is only active when `nativeImage { mainClasses(...) }` is configured.
