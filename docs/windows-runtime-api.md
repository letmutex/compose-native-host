# Windows Runtime API

Public app-facing C++ API surface for the Windows native host runtime.

This snapshot intentionally excludes low-level bridge exports from `Bridge.cpp`, `NativeHostExports.cpp`, and other internal host plumbing that is not meant to be used directly from app code.

## Startup and configuration

```cpp
// Use: #include "ComposeNativeHost.h"

/// Lifecycle phases of the hosted Compose runtime.
enum class ComposeHostLaunchPhase {
    /// Host startup initiated.
    Launching = 0,

    /// Native window and Direct3D 12 device initialized.
    WindowAttached,

    /// Kotlin runtime or GraalVM entry point bound and ready.
    JvmReady,

    /// The first Compose frame has been presented.
    FirstFramePresented,

    /// Teardown initiated.
    Closing
};

/// Event types dispatched by the hosted Compose runtime.
enum class ComposeHostEventType {
    /// Launch phase changed.
    PhaseChanged = 1,

    /// Window focus changed.
    WindowFocused,

    /// Window occlusion or minimization changed.
    WindowOccluded,

    /// App-defined event emitted from hosted Compose code.
    App
};

/// Strongly-typed event structure dispatched by the runtime.
struct ComposeHostEvent {
    ComposeHostEventType type;
    union {
        /// When type == ComposeHostEventType::PhaseChanged
        ComposeHostLaunchPhase phase;

        /// When type == ComposeHostEventType::WindowFocused
        bool focused;

        /// When type == ComposeHostEventType::WindowOccluded
        bool occluded;

        /// When type == ComposeHostEventType::App
        struct AppPayload {
            const char* name;
            const char* payload;
        } app;
    };
};

/// Callback invoked on lifecycle and window events.
/// Note: May be invoked concurrently from UI, render, or JVM threads. String pointers
/// in ComposeHostEvent are only valid for the synchronous duration of the callback.
typedef void (*ComposeRuntimeEventCallback)(
    HCOMPOSERUNTIME runtime,
    const ComposeHostEvent* event,
    void* userData
);

/// Execution backend mode for the hosted runtime on Windows.
enum class ComposeRuntimeStartupMode {
    /// Starts the runtime by booting a standard HotSpot JVM using JNI.
    Jvm,

    /// Starts the runtime by dynamically loading a compiled GraalVM shared library.
    SharedLibrary
};

/// Programmatic configuration for booting or attaching to the hosted Compose runtime.
struct ComposeRuntimeStartup {
    /// The startup backend mode (JVM or SharedLibrary).
    ComposeRuntimeStartupMode mode = ComposeRuntimeStartupMode::Jvm;

    /// Shared library filename (for SharedLibrary mode).
    std::wstring libraryName = L"";

    /// Explicit path to the shared library (for SharedLibrary mode).
    std::wstring libraryPath = L"";

    /// Helper to create a JVM startup configuration.
    static ComposeRuntimeStartup Jvm();

    /// Helper to create a Shared Library (GraalVM) startup configuration.
    static ComposeRuntimeStartup SharedLibrary(
        const std::wstring& libraryName = L"libcompose-native-host-runtime.dll",
        const std::wstring& libraryPath = L""
    );
};

/// Configuration passed when initializing the process-wide host engine.
struct ComposeHostConfiguration {
    /// Enables diagnostic logging to standard streams.
    bool enableLogging = false;

    /// Allowed runtime startup backends. Defaults to standard JVM only.
    std::vector<ComposeRuntimeStartup> startups = { ComposeRuntimeStartup::Jvm() };
};

/// Configuration for an individual hosted Compose runtime instance.
struct ComposeRuntimeConfiguration {
    /// Application name or window title.
    std::wstring appName = L"Compose Native Host";

    /// Kotlin entry point invoked for this runtime.
    std::string kotlinMainClass = "";

    /// Enables profile frame sample emission.
    bool enableProfileRendering = false;

    /// Event callback invoked on host lifecycle and app events.
    ComposeRuntimeEventCallback eventCallback = nullptr;

    /// User data pointer passed to the event callback.
    void* eventUserData = nullptr;
};
```

## Host engine and runtime lifecycle

```cpp
// Use: #include "ComposeNativeHost.h"

/// Opaque handle representing a bound Compose runtime instance.
typedef void* HCOMPOSERUNTIME;

/// Initializes the Compose Native Host engine (bootstraps JNI/JVM or GraalVM isolate).
/// Returns true on success.
bool ComposeHostInitialize(const ComposeHostConfiguration& config);

/// Shuts down the Compose Native Host engine and releases global runtime resources.
void ComposeHostShutdown();

/// Emits a phase timing log line when host logging is enabled.
void LogPhaseTiming(const char* namePtr);

/// Binds an existing HWND to a Compose runtime.
/// Initializes Direct3D 12, starts the Kotlin runtime, and spawns the render thread.
/// Returns a runtime handle on success, or NULL on failure.
HCOMPOSERUNTIME ComposeRuntimeCreate(
    HWND hwnd,
    const ComposeRuntimeConfiguration& config
);

/// Destroys a Compose runtime instance, releasing DirectX and JNI resources.
void ComposeRuntimeDestroy(HCOMPOSERUNTIME runtime);

/// Updates the event callback on an active runtime instance.
void ComposeRuntimeSetEventCallback(
    HCOMPOSERUNTIME runtime,
    ComposeRuntimeEventCallback callback,
    void* userData
);

/// Queries the current launch phase of the runtime.
ComposeHostLaunchPhase ComposeRuntimeGetLaunchPhase(HCOMPOSERUNTIME runtime);

/// Queries whether the hosted window currently has keyboard/input focus.
bool ComposeRuntimeIsWindowFocused(HCOMPOSERUNTIME runtime);

/// Queries whether the hosted window is currently occluded or minimized.
bool ComposeRuntimeIsWindowOccluded(HCOMPOSERUNTIME runtime);
```

## Window message routing

```cpp
// Use: #include "ComposeNativeHost.h"

/// Routes Win32 window messages to the Compose runtime.
/// Call this inside your custom WndProc. Handles input, resizing, DPI, and IME.
/// Returns true if the message was handled by the Compose runtime.
bool ComposeRuntimeHandleMessage(
    HWND hwnd,
    UINT message,
    WPARAM wParam,
    LPARAM lParam
);
```

## Window and chrome helpers

```cpp
// Use: #include "ComposeWindowHelper.h"

/// Configures per-monitor V2 DPI awareness for the process.
void EnableDpiAwareness();

/// Queries the current DPI scaling factor for a window.
float GetDpiScale(HWND hwnd);

class ComposeWindowHelper {
public:
    struct CaptionButtonOptions {
        bool isDarkMode = false;
        float buttonWidth = 46.0f;
        float buttonHeight = 32.0f;
    };

    struct CaptionDragOptions {
        float titleBarHeight = 32.0f;
        float rightReservedWidth = 138.0f;
        float leftReservedWidth = 0.0f;
    };

    /// Initializes GDI+ for splash screen and caption rendering.
    static void Initialize();

    /// Shuts down GDI+.
    static void Shutdown();

    /// Extends the client area into the title bar space using DWM.
    static void ExtendContentIntoTitleBar(HWND hwnd);

    /// Handles WM_NCCALCSIZE to retain drop shadow while removing the native title bar.
    static LRESULT HideNativeTitleBar(HWND hwnd, WPARAM wParam, LPARAM lParam);

    /// Handles WM_NCHITTEST for frame resizing and custom caption buttons.
    static LRESULT HitTestFrameAndButtons(
        HWND hwnd,
        WPARAM wParam,
        LPARAM lParam,
        const CaptionButtonOptions& options = {}
    );

    /// Detects which caption button is hovered (1: min, 2: max, 3: close).
    static int DetectHoveredButton(
        HWND hwnd,
        int x,
        int y,
        const CaptionButtonOptions& options = {}
    );

    /// Handles title bar drag on WM_LBUTTONDOWN.
    static bool HandleCaptionDrag(
        HWND hwnd,
        LPARAM lParam,
        const CaptionDragOptions& options = {}
    );

    /// Handles clicks on custom caption buttons (min/max/close).
    static bool HandleCaptionButtonClick(
        HWND hwnd,
        UINT message,
        WPARAM wParam,
        LPARAM lParam,
        const CaptionButtonOptions& options = {}
    );

    /// Renders custom caption buttons matching Compose Desktop's visual style.
    static void DrawCaptionButtons(
        HWND hwnd,
        HDC hdc,
        int hoveredButton = 0,
        const CaptionButtonOptions& options = {}
    );
};
```
