#pragma once
#include <windows.h>
#include <string>
#include <vector>

// Opaque handle representing a bound Compose runtime instance
typedef void* HCOMPOSERUNTIME;

// Lifecycle phases of the hosted Compose runtime (mirrors Swift's ComposeHostLaunchPhase)
enum class ComposeHostLaunchPhase {
    Launching = 0,        // Host startup initiated
    WindowAttached,       // Window & Direct3D 12 initialized
    JvmReady,             // Kotlin runtime / GraalVM entry point bound
    FirstFramePresented,  // First Compose frame presented to screen
    Closing               // Teardown initiated
};

// Event types dispatched by the hosted Compose runtime (mirrors Swift's ComposeHostEvent)
enum class ComposeHostEventType {
    PhaseChanged = 1,
    WindowFocused,
    WindowOccluded,
    App
};

// Strongly-typed event structure
struct ComposeHostEvent {
    ComposeHostEventType type;
    union {
        ComposeHostLaunchPhase phase;  // When type == ComposeHostEventType::PhaseChanged
        bool focused;                  // When type == ComposeHostEventType::WindowFocused
        bool occluded;                 // When type == ComposeHostEventType::WindowOccluded
        struct AppPayload {
            const char* name;
            const char* payload;       // May be nullptr if no payload
        } app;                         // When type == ComposeHostEventType::App
    };
};

// Callback dispatched on lifecycle and window events.
// Note: May be invoked concurrently from UI, render, or JVM threads. Callbacks must be
// thread-safe and non-blocking. String pointers in ComposeHostEvent (app.name, app.payload)
// are only valid for the synchronous duration of the callback invocation.
typedef void (*ComposeRuntimeEventCallback)(HCOMPOSERUNTIME runtime, const ComposeHostEvent* event, void* userData);

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

// Emits a phase timing log line when host logging is enabled.
void LogPhaseTiming(const char* namePtr);

// Binds an existing HWND to a Compose runtime.
// Registers the window, initializes D3D12, starts the Kotlin runtime, and spawns the render thread.
// Returns a handle on success, or NULL on failure.
HCOMPOSERUNTIME ComposeRuntimeCreate(HWND hwnd, const ComposeRuntimeConfiguration& config);

// Destroys the Compose runtime instance, releasing DirectX and JNI resources.
void ComposeRuntimeDestroy(HCOMPOSERUNTIME runtime);

// Sets the callback for events dispatched from Compose.
void ComposeRuntimeSetEventCallback(HCOMPOSERUNTIME runtime, ComposeRuntimeEventCallback callback, void* userData);

// Queries the current launch phase of the runtime.
ComposeHostLaunchPhase ComposeRuntimeGetLaunchPhase(HCOMPOSERUNTIME runtime);

// Queries whether the hosted window currently has keyboard/input focus.
bool ComposeRuntimeIsWindowFocused(HCOMPOSERUNTIME runtime);

// Queries whether the hosted window is currently occluded or minimized.
bool ComposeRuntimeIsWindowOccluded(HCOMPOSERUNTIME runtime);

// Routes Win32 window messages to the Compose runtime.
// Call this inside your custom WndProc.
// Returns true if the message was handled by the Compose runtime.
bool ComposeRuntimeHandleMessage(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);
