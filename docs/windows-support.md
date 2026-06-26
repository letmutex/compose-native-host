# Windows Support in Compose Native Host

Compose Native Host supports Windows using a custom C++ native launcher and JNI bridge that interfaces with Win32 APIs and Direct3D 12 for high-performance graphics rendering.

## Architecture Overview

On Windows, the architecture mirrors the macOS structure but replaces Apple-specific APIs with native Microsoft technologies:

1. **Host App (`windowsMain/cpp`)**: Written in C++, this component registers a Win32 window class, instantiates the main `HWND`, and implements the classic Win32 message loop (`GetMessage` / `DispatchMessage`).
2. **GPU Rendering**: Utilizes Direct3D 12. The C++ renderer sets up the D3D12 device, command queue, and swapchain, drawing onto the window.
3. **Bridge (`windowsMain/native`)**: A JNI bridge compiled into `bridge.dll` that links the C++ host to the Kotlin/JVM application.
4. **Kotlin Runtime (`desktopMain`)**: Intersects window size/DPI scale metrics, converts Win32 mouse/keyboard inputs into Compose inputs, and binds the Direct3D 12 device context using Skiko's `DirectContext.makeDirect3D`.

---

## Prerequisites & Visual Studio Setup

To compile the C++ host and JNI bridge on Windows, you must have the **MSVC compiler (`cl.exe`)** installed:

* Install [Visual Studio 2022](https://visualstudio.microsoft.com/) (Community, Professional, or Enterprise).
* Ensure the **"Desktop development with C++"** workload is selected during installation.

### Compiler Script Location (`vcvars64.bat`)
By default, the plugin queries the Visual Studio installer (`vswhere.exe`) to automatically locate the environment script `vcvars64.bat`.

If you have a custom installation path or prefer to specify it explicitly, you can set the path via:
1. **Gradle project property**: Set `vcvars64Path` in your `gradle.properties` or command-line:
   ```properties
   vcvars64Path=C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\VC\\Auxiliary\\Build\\vcvars64.bat
   ```
2. **Environment variable**: Set the environment variable `VCVARS64_PATH`:
   ```bash
   set VCVARS64_PATH=C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat
   ```

---

## Gradle Plugin Configuration

Windows-specific host settings can be configured inside the `composeNativeHost` extension block:

```kotlin
composeNativeHost {
    appName.set("My Compose App")
    
    windows {
        // Optional custom launcher C++ source directory. Defaults to internal extracted template.
        launcherSourcesDir.set(layout.projectDirectory.dir("src/windowsMain/cpp"))
    }
}
```

---

## Tasks Reference

The following platform-specific tasks are registered on Windows hosts:

| Task Name | Description |
|---|---|
| `windowsStageAppBundle` | Stages the launcher `.exe`, `.cfg`, and `native/bridge.dll` along with JVM libraries into `build/native-app/windows/`. |
| `windowsRun` | Launches the staged Windows application directly. |
| `windowsExtractHostSources` | Extracts the C++ host template sources into `build/generated/`. |
| `windowsBuildLauncher` | Compiles the C++ launcher executable linking against DirectX libraries. |
| `windowsBuildBridge` | Compiles the JNI bridge DLL. |
| `windowsPatchDistributable` | Patches the output of the standard Compose Desktop `createDistributable` task with the native launcher and DLLs. |

### Running the App
```bash
# Clean and run the JVM application
./gradlew -p samples :mixed:windowsRun
```

### Packaging & Packaging MSI
```bash
# Build the staged app folder
./gradlew -p samples :mixed:windowsStageAppBundle

# Build the patched distributable
./gradlew -p samples :mixed:windowsCreateDistributable

# Package as MSI installer
./gradlew -p samples :mixed:windowsPackageMsi
```
