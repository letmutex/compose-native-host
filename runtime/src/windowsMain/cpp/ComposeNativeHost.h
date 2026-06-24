#pragma once
#include <windows.h>
#include <string>
#include <vector>

// Opaque handle representing a bound Compose runtime instance
typedef void* HCOMPOSERUNTIME;

typedef void (*ComposeRuntimeEventCallback)(HCOMPOSERUNTIME runtime, const char* eventName, const char* eventPayload, void* userData);

struct ComposeRuntimeConfiguration {
    std::wstring appName = L"Compose Native Host";
    std::string kotlinMainClass = "";
    bool enableProfileRendering = false;
    ComposeRuntimeEventCallback eventCallback = nullptr;
    void* eventUserData = nullptr;
};

// Mode of the hosted Compose runtime execution backend on Windows.
enum class ComposeRuntimeStartupMode {
    // Starts the runtime by booting a standard HotSpot JVM and using JNI.
    Jvm,
    // Starts the runtime by dynamically loading a compiled GraalVM shared library.
    SharedLibrary
};

// Programmatic configuration for booting/attaching to the hosted Kotlin/Compose backend.
struct ComposeRuntimeStartup {
    // The startup backend mode (JVM or Shared Library).
    ComposeRuntimeStartupMode mode = ComposeRuntimeStartupMode::Jvm;
    // For SharedLibrary mode: the filename of the shared library (e.g. L"libcompose-native-host-runtime.dll").
    std::wstring libraryName = L"";
    // For SharedLibrary mode: the optional absolute/relative library path to load explicitly.
    std::wstring libraryPath = L"";

    // Helper to create a JVM startup configuration.
    static ComposeRuntimeStartup Jvm() {
        ComposeRuntimeStartup startup;
        startup.mode = ComposeRuntimeStartupMode::Jvm;
        return startup;
    }

    // Helper to create a Shared Library (GraalVM) startup configuration.
    static ComposeRuntimeStartup SharedLibrary(
        const std::wstring& libraryName = L"libcompose-native-host-runtime.dll",
        const std::wstring& libraryPath = L""
    ) {
        ComposeRuntimeStartup startup;
        startup.mode = ComposeRuntimeStartupMode::SharedLibrary;
        startup.libraryName = libraryName;
        startup.libraryPath = libraryPath;
        return startup;
    }
};

// Global configuration passed during ComposeHostInitialize.
struct ComposeHostConfiguration {
    // If true, diagnostic logging is printed to standard streams.
    bool enableLogging = false;
    // Set of allowed runtime startup backends. Defaults to standard JVM only.
    std::vector<ComposeRuntimeStartup> startups = { ComposeRuntimeStartup::Jvm() };
};

// Initializes the Compose Native Host engine (bootstraps JNI/JVM).
// Returns true on success.
bool ComposeHostInitialize(const ComposeHostConfiguration& config);

// Shuts down the Compose Native Host engine (destroys the JVM).
void ComposeHostShutdown();

// Binds an existing HWND to a Compose runtime.
// Registers the window, initializes D3D12, starts the Kotlin runtime, and spawns the render thread.
// Returns a handle on success, or NULL on failure.
HCOMPOSERUNTIME ComposeRuntimeCreate(HWND hwnd, const ComposeRuntimeConfiguration& config);

// Destroys the Compose runtime instance, releasing DirectX and JNI resources.
void ComposeRuntimeDestroy(HCOMPOSERUNTIME runtime);

// Sets the callback for events dispatched from Compose.
void ComposeRuntimeSetEventCallback(HCOMPOSERUNTIME runtime, ComposeRuntimeEventCallback callback, void* userData);

// Routes Win32 window messages to the Compose runtime.
// Call this inside your custom WndProc.
// Returns true if the message was handled by the Compose runtime.
bool ComposeRuntimeHandleMessage(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);
