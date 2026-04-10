<h1 align="center">Compose Native Host</h1>
<p align="center">Compose in native macOS window</p>

![Sample Screenshot](./sample.png)

> [!IMPORTANT]
> This project is experimental. API and features are subject to change.

## Why go native?

- You own the entry point. Compose is just a view in your native window
- Embed Compose view in AppKit, SwiftUI, or both
- Smooth scrolling, smooth window resizing. Sorry AWT
- Built-in GraalVM native image support. Sorry AWT, again
- Multi-window and multi-runtime support
- Android-inspired profile frame rendering
- No code changes needed for existing Compose Desktop content

## Documentation

- [docs/kotlin-runtime-api.md](./docs/kotlin-runtime-api.md): Kotlin runtime API snapshot
- [docs/swift-runtime-api.md](./docs/swift-runtime-api.md): Swift runtime API snapshot
- [gradle-plugin/README.md](./gradle-plugin/README.md): Gradle plugin configuration and task reference

## Minimal SwiftUI Setup

Gradle

```kotlin
// build.gradle.kts
plugins {
    id("io.github.letmutex.compose.nativehost") version "<VERSION>"
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation("io.github.letmutex.compose-native-host:runtime:<VERSION>")
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

composeNativeHost {
    appName.set("Sample")
    bundleIdentifier.set("example.sample")
}

// Compose Desktop configs
compose.desktop {
    application {
        mainClass = "example.MainKt"
    }
}
```

Kotlin

```kotlin
// src/desktopMain/kotlin/example/Main.kt
package example

import letmutex.compose.nativehost.ComposeNativeHost

fun main() = ComposeNativeHost {
    App()
}
```

Swift

```swift
// src/macosMain/swift/SwiftUiApp.swift
import SwiftUI
import ComposeNativeHost

final class SampleAppDelegate: ComposeAppDelegateBase {
    fileprivate lazy var runtime = makeComposeRuntime(
        configuration: ComposeRuntimeConfiguration(kotlinMainClass: "example.MainKt")
    )
}

@main
struct SampleApp: App {
    @NSApplicationDelegateAdaptor(SampleAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ComposeView(runtime: appDelegate.runtime)
        }
    }
}
```

## Minimal GraalVM Config

Use a GraalVM JDK that already has `native-image` available under `bin/`.

Environment

- Set one of `-PgraalvmHome=/path/to/graalvm`, `GRAALVM_HOME=/path/to/graalvm`, `org.gradle.java.home=/path/to/graalvm`, or `JAVA_HOME=/path/to/graalvm`.
- `macosNativeImageRun` and the native image bundle tasks use that GraalVM home directly.
- Plain `macosRun` still uses a regular JVM launch path.

Gradle

```kotlin
composeNativeHost {
    appName.set("Sample")
    bundleIdentifier.set("example.sample")
    nativeImage {
        mainClasses("example.MainKt")
    }
}
```

Swift startups

```swift
final class SampleAppDelegate: ComposeAppDelegateBase {
    override init() {
        super.init(configuration: ComposeHostConfiguration(startups: [.jvm, .sharedLibrary()]))
    }
}
```

Use `.jvm` so the same host still runs without a bundled shared library, and add `.sharedLibrary()` so the staged native image bundle can bind the generated Graal runtime when present.

## Run App

Use `macosRun` for the staged JVM bundle or `macosNativeImageRun` for the staged native image bundle.

```bash
./gradlew -p samples :appkit:macosRun

# run the native image bundle
./gradlew -p samples :appkit:macosNativeImageRun
```

## Bundle App

```bash
# build JVM / native image .app
./gradlew -p samples :appkit:macosCreateDistributable
./gradlew -p samples :appkit:macosNativeImageCreateDistributable

# package JVM / native image .dmg
./gradlew -p samples :appkit:macosPackageDmg
./gradlew -p samples :appkit:macosNativeImagePackageDmg

# package JVM / native image release .dmg
./gradlew -p samples :appkit:macosPackageReleaseDmg
./gradlew -p samples :appkit:macosNativeImagePackageReleaseDmg
```

## Modules

- `runtime`: shared host API plus macOS runtime, native bridge, and native host sources.
- `gradle-plugin`: Gradle plugin for macOS launcher generation, bundle staging, and macOS app tasks.
- `samples/compose`: pure Compose Desktop sample app and shared Kotlin sample content.
- `samples/appkit`: AppKit-owned sample window using the shared hosted Kotlin sample.
- `samples/swiftui`: SwiftUI-owned sample window using the shared hosted Kotlin sample.
- `samples/swiftui-min`: minimal SwiftUI-owned sample extracted from the setup shown above.
- `samples/mixed`: AppKit-owned window with SwiftUI content composition around the hosted Compose surface.

# How it works

### Host (Swift)
You own the App entry point in **Swift**. Your app's Swift sources (e.g., in `src/macosMain/swift`) define the AppKit/SwiftUI lifecycle. The plugin compiles these along with shared runtime helpers into the final native binary.

### Runtime (Kotlin & Swift)
*   **Kotlin**: Manages the Compose state, layout, and logic. Uses **Skiko** for Skia rendering bindings.
*   **Swift**: Provides the native macOS host API (`ComposeHostRuntime`, `ComposeView`). These Swift sources are bundled inside the Gradle plugin and extracted during the build to be compiled into your app.

### Bridge (JNI/Obj C)
A thin Objective C bridge handles low level JNI communication between the Kotlin JVM/Native Image and the Swift host.

### Renderer (Metal)
Rendering is performed directly on the **GPU via Metal**. The runtime uses a custom renderer that bypasses AWT/Swing, ensuring smooth synchronization with macOS window resizing and animations.

### Plugin (The Orchestrator)
The Gradle plugin automates the complex **native build pipeline**. It:
1. Extracts internal Swift/Native sources.
2. Compiles both runtime and app swift sources using `swiftc` and `clang`.
3. Bundles the native bridge library (`.dylib`) and launcher into the `.app` bundle.
4. Optionally triggers **GraalVM Native Image** for high performance native binaries.

### Runtime modes
*   **JVM**: Standard Kotlin/JVM JARs are packaged and launched by the native host using a bundled JVM.
*   **Native**: Kotlin code is compiled into a standalone shared library via GraalVM, allowing for instant startup and reduced memory overhead.

### Simplified Render Path

```text
+---------------+          +--------------+          +----------------+          +-----------+
|   Swift App   | --(1)--> | Bridge Layer | --(2)--> | Kotlin Runtime | --(3)--> | Metal GPU |
| (Entry Point) |          | (JNI / C-API)|          | (JVM / Native) |          | (Texture) |
+---------------+          +--------------+          +----------------+          +-----------+
```

# License

```text
Copyright 2026 letmutex

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
