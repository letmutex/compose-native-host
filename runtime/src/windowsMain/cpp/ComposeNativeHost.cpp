#include "ComposeNativeHost.h"
#include "HostJvm.h"
#include "ComposeDropTarget.h"
#include "DpiHelper.h"
#include <windowsx.h>
#include <atomic>
#include <iostream>
#include <fstream>
#include <sstream>
#include <algorithm>
#include <thread>
#include <imm.h>

bool g_useSharedLibraryRuntime = false;

// ============================================================================
// GraalVM C-API Function Pointers
// These function pointers map to the `@CEntryPoint` functions exported by
// `RuntimeNativeLibrary.kt` during the GraalVM native image compilation.
// They are dynamically resolved at runtime if the shared library mode is active.
// ============================================================================

typedef jint(JNICALL *graal_create_isolate_t)(void*, void**, void**);
typedef jint(JNICALL *graal_attach_thread_t)(void*, void**);
typedef void*(JNICALL *graal_get_current_thread_t)(void*);
typedef jint(JNICALL *graal_detach_thread_t)(void*);

typedef int64_t(*composeNativeHostRuntimeCreate_t)(void*, int64_t, int32_t);
typedef int32_t(*composeNativeHostRuntimeBindMain_t)(void*, int64_t, const char*);
typedef int32_t(*composeNativeHostRuntimeStart_t)(void*, int64_t);
typedef void(*composeNativeHostRuntimeRequestFrame_t)(void*, int64_t, int64_t);
typedef void(*composeNativeHostRuntimeClose_t)(void*, int64_t);

static graal_create_isolate_t fn_graal_create_isolate = nullptr;
static graal_attach_thread_t fn_graal_attach_thread = nullptr;
static graal_get_current_thread_t fn_graal_get_current_thread = nullptr;
static graal_detach_thread_t fn_graal_detach_thread = nullptr;

static composeNativeHostRuntimeInitializeNoId_t fn_composeNativeHostRuntimeInitialize = nullptr;
static composeNativeHostRuntimeCreate_t fn_composeNativeHostRuntimeCreate = nullptr;
static composeNativeHostRuntimeBindMain_t fn_composeNativeHostRuntimeBindMain = nullptr;
static composeNativeHostRuntimeStart_t fn_composeNativeHostRuntimeStart = nullptr;
static composeNativeHostRuntimeRequestFrame_t fn_composeNativeHostRuntimeRequestFrame = nullptr;
static composeNativeHostRuntimeClose_t fn_composeNativeHostRuntimeClose = nullptr;

composeNativeHostRuntimeHandleExternalDrop_t fn_composeNativeHostRuntimeHandleExternalDragEntered = nullptr;
composeNativeHostRuntimeHandleExternalDrop_t fn_composeNativeHostRuntimeHandleExternalDragMoved = nullptr;
composeNativeHostRuntimeInitialize_t fn_composeNativeHostRuntimeHandleExternalDragExited = nullptr;
composeNativeHostRuntimeInitialize_t fn_composeNativeHostRuntimeHandleExternalDragEnded = nullptr;
composeNativeHostRuntimeHandleExternalDrop_t fn_composeNativeHostRuntimeHandleExternalDrop = nullptr;

static HMODULE g_GraalDll = nullptr;
static void* g_GraalIsolate = nullptr;

static std::string WideToUtf8(const std::wstring& wstr) {
    if (wstr.empty()) return "";
    int size_needed = WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), NULL, 0, NULL, NULL);
    std::string strUtf8(size_needed, 0);
    WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), &strUtf8[0], size_needed, NULL, NULL);
    return strUtf8;
}

static std::wstring Utf8ToWide(const std::string& str) {
    if (str.empty()) return L"";
    int size_needed = MultiByteToWideChar(CP_UTF8, 0, str.c_str(), (int)str.size(), NULL, 0);
    std::wstring wstrTo(size_needed, 0);
    MultiByteToWideChar(CP_UTF8, 0, str.c_str(), (int)str.size(), &wstrTo[0], size_needed);
    return wstrTo;
}

GraalThreadAttachment GetOrAttachThread() {
    if (!g_GraalIsolate || !fn_graal_get_current_thread || !fn_graal_attach_thread) {
        return { nullptr, false };
    }
    void* currentThread = fn_graal_get_current_thread(g_GraalIsolate);
    if (currentThread) {
        return { currentThread, false };
    }
    void* attachedThread = nullptr;
    if (fn_graal_attach_thread(g_GraalIsolate, &attachedThread) == 0 && attachedThread) {
        return { attachedThread, true };
    }
    return { nullptr, false };
}

void DetachThread(const GraalThreadAttachment& attachment) {
    if (attachment.detachOnExit && fn_graal_detach_thread && attachment.thread) {
        fn_graal_detach_thread(attachment.thread);
    }
}

// Holds key properties parsed from the application's .cfg file.
struct AppCfg {
    std::string runtimeMode = "";
    std::string runtimeLibrary = "";
};

// Parses the .cfg file accompanying the host executable to resolve runtime configuration properties.
static AppCfg LoadAppCfg() {
    AppCfg cfg;
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    std::wstring exeDir = exePath;
    size_t lastSlash = exeDir.find_last_of(L"\\/");
    if (lastSlash == std::wstring::npos) {
        return cfg;
    }
    exeDir = exeDir.substr(0, lastSlash);

    std::wstring exeName = exePath;
    lastSlash = exeName.find_last_of(L"\\/");
    if (lastSlash != std::wstring::npos) {
        exeName = exeName.substr(lastSlash + 1);
    }
    size_t lastDot = exeName.find_last_of(L".");
    if (lastDot != std::wstring::npos) {
        exeName = exeName.substr(0, lastDot);
    }

    std::wstring configFile = exeDir + L"\\app\\" + exeName + L".cfg";
    std::ifstream file(configFile);
    if (!file.is_open()) {
        configFile = exeDir + L"\\" + exeName + L".cfg";
        file.open(configFile);
        if (!file.is_open()) {
            return cfg;
        }
    }

    std::string line;
    while (std::getline(file, line)) {
        line.erase(std::remove(line.begin(), line.end(), '\r'), line.end());
        if (line.empty() || line[0] == '[') continue;

        size_t eq = line.find('=');
        if (eq == std::string::npos) continue;

        std::string key = line.substr(0, eq);
        std::string val = line.substr(eq + 1);

        if (key == "runtime.mode") {
            cfg.runtimeMode = val;
        } else if (key == "runtime.library") {
            cfg.runtimeLibrary = val;
        }
    }
    return cfg;
}
#pragma comment(lib, "imm32.lib")
#pragma comment(lib, "ole32.lib")




// ============================================================================
// Runtime Initialization & Routing
// ============================================================================

// Initializes the GraalVM shared library runtime.
// This resolves the path to the native DLL, dynamically loads the C-API function pointers,
// and creates the core GraalVM isolate that will host the Compose runtime.
static bool InitializeSharedLibraryRuntime(const ComposeRuntimeStartup& startup) {
    std::wstring libraryPath;
    if (!startup.libraryPath.empty()) {
        libraryPath = startup.libraryPath;
    } else {
        wchar_t exePath[MAX_PATH];
        GetModuleFileNameW(nullptr, exePath, MAX_PATH);
        std::wstring exeDir = exePath;
        size_t lastSlash = exeDir.find_last_of(L"\\/");
        if (lastSlash != std::wstring::npos) {
            exeDir = exeDir.substr(0, lastSlash);
        }
        std::wstring libName = startup.libraryName.empty() ? L"libcompose-native-host-runtime.dll" : startup.libraryName;

        std::wstring nativePath = exeDir + L"\\native\\" + libName;
        DWORD attribs = GetFileAttributesW(nativePath.c_str());
        if (attribs != INVALID_FILE_ATTRIBUTES) {
            libraryPath = nativePath;
        } else {
            libraryPath = exeDir + L"\\" + libName;
        }
    }

    g_useSharedLibraryRuntime = true;
    g_GraalDll = LoadLibraryW(libraryPath.c_str());
    if (!g_GraalDll) {
        std::cerr << "Failed to load GraalVM shared library: " << WideToUtf8(libraryPath) << ", error: " << GetLastError() << std::endl;
        return false;
    }

    fn_graal_create_isolate = (graal_create_isolate_t)GetProcAddress(g_GraalDll, "graal_create_isolate");
    fn_graal_attach_thread = (graal_attach_thread_t)GetProcAddress(g_GraalDll, "graal_attach_thread");
    fn_graal_get_current_thread = (graal_get_current_thread_t)GetProcAddress(g_GraalDll, "graal_get_current_thread");
    fn_graal_detach_thread = (graal_detach_thread_t)GetProcAddress(g_GraalDll, "graal_detach_thread");

    fn_composeNativeHostRuntimeInitialize = (composeNativeHostRuntimeInitializeNoId_t)GetProcAddress(g_GraalDll, "composeNativeHostRuntimeInitialize");
    fn_composeNativeHostRuntimeCreate = (composeNativeHostRuntimeCreate_t)GetProcAddress(g_GraalDll, "composeNativeHostRuntimeCreate");
    fn_composeNativeHostRuntimeBindMain = (composeNativeHostRuntimeBindMain_t)GetProcAddress(g_GraalDll, "composeNativeHostRuntimeBindMain");
    fn_composeNativeHostRuntimeStart = (composeNativeHostRuntimeStart_t)GetProcAddress(g_GraalDll, "composeNativeHostRuntimeStart");
    fn_composeNativeHostRuntimeRequestFrame = (composeNativeHostRuntimeRequestFrame_t)GetProcAddress(g_GraalDll, "composeNativeHostRuntimeRequestFrame");
    fn_composeNativeHostRuntimeClose = (composeNativeHostRuntimeClose_t)GetProcAddress(g_GraalDll, "composeNativeHostRuntimeClose");

    fn_composeNativeHostRuntimeHandleExternalDragEntered = (composeNativeHostRuntimeHandleExternalDrop_t)GetProcAddress(g_GraalDll, "composeNativeHostRuntimeHandleExternalDragEntered");
    fn_composeNativeHostRuntimeHandleExternalDragMoved = (composeNativeHostRuntimeHandleExternalDrop_t)GetProcAddress(g_GraalDll, "composeNativeHostRuntimeHandleExternalDragMoved");
    fn_composeNativeHostRuntimeHandleExternalDragExited = (composeNativeHostRuntimeInitialize_t)GetProcAddress(g_GraalDll, "composeNativeHostRuntimeHandleExternalDragExited");
    fn_composeNativeHostRuntimeHandleExternalDragEnded = (composeNativeHostRuntimeInitialize_t)GetProcAddress(g_GraalDll, "composeNativeHostRuntimeHandleExternalDragEnded");
    fn_composeNativeHostRuntimeHandleExternalDrop = (composeNativeHostRuntimeHandleExternalDrop_t)GetProcAddress(g_GraalDll, "composeNativeHostRuntimeHandleExternalDrop");

    if (!fn_graal_create_isolate || !fn_graal_attach_thread || !fn_graal_get_current_thread || !fn_graal_detach_thread ||
        !fn_composeNativeHostRuntimeInitialize || !fn_composeNativeHostRuntimeCreate || !fn_composeNativeHostRuntimeBindMain ||
        !fn_composeNativeHostRuntimeStart || !fn_composeNativeHostRuntimeRequestFrame || !fn_composeNativeHostRuntimeClose) {
        std::cerr << "Failed to find GraalVM entrypoints in shared library." << std::endl;
        FreeLibrary(g_GraalDll);
        g_GraalDll = nullptr;
        g_useSharedLibraryRuntime = false;
        return false;
    }

    void* thread = nullptr;
    int res = fn_graal_create_isolate(nullptr, &g_GraalIsolate, &thread);
    if (res != 0 || !g_GraalIsolate) {
        std::cerr << "Failed to create GraalVM isolate: " << res << std::endl;
        FreeLibrary(g_GraalDll);
        g_GraalDll = nullptr;
        g_useSharedLibraryRuntime = false;
        return false;
    }

    if (thread) {
        fn_graal_detach_thread(thread);
    }

    return true;
}

static bool InitializeJvmRuntime(const ComposeRuntimeStartup& startup) {
    return HostJvm::Get().BootstrapJvm();
}

// Core initialization entry point for the Windows Compose host.
// Initializes COM, enables DPI awareness, parses the application configuration (.cfg),
// and routes initialization to either the JVM or the GraalVM shared library.
bool ComposeHostInitialize(const ComposeHostConfiguration& config) {
    OleInitialize(NULL);
    EnableDpiAwareness();

    // Load active mode and runtime parameters from the staged app config.
    AppCfg appCfg = LoadAppCfg();

    if (appCfg.runtimeMode == "sharedLibrary") {
        // Find matching SharedLibrary startup configuration in allowed startups list.
        const ComposeRuntimeStartup* startup = nullptr;
        for (const auto& s : config.startups) {
            if (s.mode == ComposeRuntimeStartupMode::SharedLibrary) {
                startup = &s;
                break;
            }
        }

        if (!startup) {
            std::cerr << "ComposeNativeHostRuntimeMode 'sharedLibrary' is not configured in allowed startups." << std::endl;
            return false;
        }

        return InitializeSharedLibraryRuntime(*startup);
    } else {
        const ComposeRuntimeStartup* startup = nullptr;
        for (const auto& s : config.startups) {
            if (s.mode == ComposeRuntimeStartupMode::Jvm) {
                startup = &s;
                break;
            }
        }

        if (!startup) {
            std::cerr << "ComposeNativeHostRuntimeMode 'jvm' is not configured in allowed startups." << std::endl;
            return false;
        }

        return InitializeJvmRuntime(*startup);
    }
}

void ComposeHostShutdown() {
    OleUninitialize();
    if (g_useSharedLibraryRuntime) {
        if (g_GraalDll) {
            FreeLibrary(g_GraalDll);
            g_GraalDll = nullptr;
        }
        g_GraalIsolate = nullptr;
        g_useSharedLibraryRuntime = false;
    }
}

// Background rendering loop for the GraalVM shared library mode.
// Attaches the current thread to the GraalVM isolate, instantiates the Compose runtime,
// binds the entry point (main class), and enters a VSync loop using DwmFlush to drive frames.
static void StartSharedLibraryRenderLoop(int64_t runtimeId, std::string mainClassName, bool enableProfile) {
    auto s = HostJvm::Get().GetRuntime(runtimeId);
    if (!s) return;

    auto attachment = GetOrAttachThread();
    if (!attachment.thread) {
        std::cerr << "Failed to attach GraalVM thread." << std::endl;
        return;
    }

    fn_composeNativeHostRuntimeInitialize(attachment.thread);
    int64_t handle = fn_composeNativeHostRuntimeCreate(attachment.thread, runtimeId, enableProfile ? 1 : 0);
    if (handle == 0) {
        std::cerr << "Failed to create GraalVM Compose runtime." << std::endl;
        DetachThread(attachment);
        return;
    }
    s->graalRuntimeHandle = handle;

    int32_t bindRes = fn_composeNativeHostRuntimeBindMain(attachment.thread, handle, mainClassName.c_str());
    if (bindRes == 0) {
        std::cerr << "Failed to bind main class in GraalVM Compose runtime: " << mainClassName << std::endl;
        fn_composeNativeHostRuntimeClose(attachment.thread, handle);
        s->graalRuntimeHandle = 0;
        DetachThread(attachment);
        return;
    }

    int32_t startRes = fn_composeNativeHostRuntimeStart(attachment.thread, handle);
    if (startRes == 0) {
        std::cerr << "Failed to start GraalVM Compose runtime." << std::endl;
        fn_composeNativeHostRuntimeClose(attachment.thread, handle);
        s->graalRuntimeHandle = 0;
        DetachThread(attachment);
        return;
    }

    LARGE_INTEGER qpf;
    QueryPerformanceFrequency(&qpf);
    double frequency = (double)qpf.QuadPart;

    typedef void (WINAPI *DwmFlushType)(void);
    HMODULE dwmapi = LoadLibraryW(L"dwmapi.dll");
    DwmFlushType dwmFlushFn = nullptr;
    if (dwmapi) {
        dwmFlushFn = (DwmFlushType)GetProcAddress(dwmapi, "DwmFlush");
        if (!dwmFlushFn) {
            std::cerr << "Warning: DwmFlush function not found in dwmapi.dll. Falling back to Sleep(16)." << std::endl;
        }
    } else {
        std::cerr << "Warning: dwmapi.dll not found. Falling back to Sleep(16)." << std::endl;
    }

    while (s->isRunning.load() && s->graalRuntimeHandle) {
        {
            std::unique_lock<std::mutex> lock(s->lock);
            s->cv.wait(lock, [&] { return s->requestRenderTick || !s->isRunning.load() || !s->graalRuntimeHandle; });
            s->requestRenderTick = false;
        }

        if (!s->isRunning.load() || !s->graalRuntimeHandle) {
            break;
        }

        // A render tick can be a no-op after Compose checks invalidations. Do not use a
        // DXGI frame-latency waitable object here: it is auto-reset and a no-op tick could
        // consume its signal without a Present to re-arm it. See D3D12Renderer::Initialize.
        if (dwmFlushFn) {
            dwmFlushFn();
        } else {
            Sleep(16);
        }

        if (!s->isRunning.load() || !s->graalRuntimeHandle) {
            break;
        }

        LARGE_INTEGER qpc;
        QueryPerformanceCounter(&qpc);
        int64_t vsyncNanos = (int64_t)((double)qpc.QuadPart / frequency * 1000000000.0);

        fn_composeNativeHostRuntimeRequestFrame(attachment.thread, handle, vsyncNanos);
    }

    if (s->graalRuntimeHandle) {
        fn_composeNativeHostRuntimeClose(attachment.thread, handle);
        s->graalRuntimeHandle = 0;
    }

    if (dwmapi) {
        FreeLibrary(dwmapi);
    }

    DetachThread(attachment);
}

static void StartJvmRenderLoop(int64_t runtimeId, std::string mainClassName, bool enableProfile) {
    auto s = HostJvm::Get().GetRuntime(runtimeId);
    if (!s) return;
    if (HostJvm::Get().PrepareRuntime(runtimeId, mainClassName, enableProfile)) {
        HostJvm::Get().RunRenderLoop(runtimeId, &s->isRunning);
    } else {
        std::cerr << "Failed to prepare JVM Compose native runtime." << std::endl;
    }
}

HCOMPOSERUNTIME ComposeRuntimeCreate(HWND hwnd, const ComposeRuntimeConfiguration& config) {
    static std::atomic<int64_t> s_NextRuntimeId{1};
    int64_t runtimeId = s_NextRuntimeId.fetch_add(1);

    auto state = std::make_shared<RuntimeState>();
    state->runtimeId = runtimeId;
    state->hwnd = hwnd;
    state->isRunning = true;
    state->eventCallback = config.eventCallback;
    state->eventUserData = config.eventUserData;

    RECT clientRect;
    GetClientRect(hwnd, &clientRect);
    int clientWidth = clientRect.right - clientRect.left;
    int clientHeight = clientRect.bottom - clientRect.top;

    state->cachedMetrics.width = clientWidth;
    state->cachedMetrics.height = clientHeight;
    state->cachedMetrics.scale = GetDpiScale(hwnd);
    state->cachedMetrics.refreshRate = 60;
    state->cachedMetrics.isFocused = (GetFocus() == hwnd);
    state->cachedMetrics.isMaximized = IsZoomed(hwnd);

    if (!state->renderer.Initialize(hwnd, clientWidth, clientHeight)) {
        std::cerr << "Failed to initialize D3D12 Renderer." << std::endl;
        return nullptr;
    }

    // Register OLE drop target for this HWND
    ComposeDropTarget* dropTarget = new ComposeDropTarget(hwnd, runtimeId);
    if (SUCCEEDED(RegisterDragDrop(hwnd, dropTarget))) {
        state->dropTarget = dropTarget; // dropTarget gets ref count incremented to 2 or kept by target
    } else {
        dropTarget->Release();
    }

    HostJvm::Get().RegisterRuntime(runtimeId, state);

    std::string mainClassName = config.kotlinMainClass;
    if (mainClassName.empty()) {
        std::cerr << "Fatal: ComposeRuntimeConfiguration.kotlinMainClass must not be empty." << std::endl;
        // Clean up the resources we just acquired so nothing leaks.
        if (state->dropTarget) {
            RevokeDragDrop(state->hwnd);
            state->dropTarget->Release();
            state->dropTarget = nullptr;
        }
        state->renderer.Shutdown();
        state->isRunning = false;
        HostJvm::Get().UnregisterRuntime(runtimeId);
        return nullptr;
    }
    bool enableProfile = config.enableProfileRendering;

    if (g_useSharedLibraryRuntime) {
        state->renderThread = std::thread(StartSharedLibraryRenderLoop, runtimeId, mainClassName, enableProfile);
    } else {
        state->renderThread = std::thread(StartJvmRenderLoop, runtimeId, mainClassName, enableProfile);
    }

    return (HCOMPOSERUNTIME)runtimeId;
}

void ComposeRuntimeDestroy(HCOMPOSERUNTIME runtime) {
    int64_t runtimeId = (int64_t)runtime;
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (!state) return;

    {
        std::lock_guard<std::mutex> lock(state->lock);
        state->isRunning = false;
        state->requestRenderTick = true;
    }
    state->cv.notify_one();

    if (state->renderThread.joinable()) {
        state->renderThread.join();
    }

    if (state->dropTarget) {
        RevokeDragDrop(state->hwnd);
        state->dropTarget->Release();
        state->dropTarget = nullptr;
    }

    HostJvm::Get().UnregisterRuntime(runtimeId);
}

void ComposeRuntimeSetEventCallback(HCOMPOSERUNTIME runtime, ComposeRuntimeEventCallback callback, void* userData) {
    int64_t runtimeId = (int64_t)runtime;
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (state) {
        state->eventCallback = callback;
        state->eventUserData = userData;
    }
}


