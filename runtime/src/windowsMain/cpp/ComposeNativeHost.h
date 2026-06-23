#pragma once
#include <windows.h>
#include <string>

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

struct ComposeHostConfiguration {
    bool enableLogging = false;
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
