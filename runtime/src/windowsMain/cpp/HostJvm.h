#pragma once
#include <windows.h>
#include <jni.h>
#include "ComposeNativeHost.h"
#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <memory>
#include <functional>
#include <atomic>
#include <condition_variable>
#include <chrono>
#include <thread>
#include "D3D12Renderer.h"
#include "InputEvents.h"

struct Win32WindowInfo {
    int32_t width = 0;
    int32_t height = 0;
    float scale = 1.0f;
    int32_t refreshRate = 60;
    bool isFocused = true;
    bool isMaximized = false;
    int hoveredCaptionButton = 0;
};

struct DragDropPayload {
    int payloadKind = 0; // 0: none, 1: files, 2: text
    std::vector<std::wstring> files;
    std::wstring text;
};

inline int64_t PackWindowInfo(int width, int height, float scale, int refreshRate, bool isFocused, bool isMaximized, int hoveredCaptionButton) {
    int64_t packedHeight = (int64_t)(height & 0x7FFF);
    int64_t packedWidth = ((int64_t)(width & 0x7FFF)) << 15;
    int64_t packedScale = ((int64_t)((int)(scale * 1000.0f) & 0x3FFF)) << 30;
    int64_t packedRefreshRate = ((int64_t)(refreshRate & 0x7FF)) << 44;
    int64_t packedFocus = (isFocused ? 1LL : 0LL) << 55;
    int64_t packedMaximized = (isMaximized ? 1LL : 0LL) << 56;
    int64_t packedHoveredButton = ((int64_t)(hoveredCaptionButton & 0x3)) << 57;
    return packedHeight | packedWidth | packedScale | packedRefreshRate | packedFocus | packedMaximized | packedHoveredButton;
}

struct RuntimeState {
    int64_t runtimeId = 0;
    HWND hwnd = nullptr;
    D3D12Renderer renderer;
    InputEventStore inputEvents;
    bool requestRenderTick = false;
    Win32WindowInfo cachedMetrics;

    // Deferred resize tracking
    bool resizePending = false;
    int pendingWidth = 0;
    int pendingHeight = 0;

    // Live resize tracking
    std::atomic<bool> isInLiveResize{false};
    bool isMouseTracked = false;

    // Text input geometry
    float focusedRectLeft = 0;
    float focusedRectTop = 0;
    float focusedRectRight = 0;
    float focusedRectBottom = 0;
    int32_t selectionStart = -1;
    int32_t selectionEnd = -1;
    int32_t compositionStart = -1;
    int32_t compositionEnd = -1;

    IUnknown* dropTarget = nullptr;

    std::mutex lock;
    std::condition_variable cv;
    jobject jvmRuntimeRef = nullptr;

    // Running flag and thread for this runtime
    bool isRunning = true;
    std::thread renderThread;

    std::atomic<ComposeRuntimeEventCallback> eventCallback{nullptr};
    std::atomic<void*> eventUserData{nullptr};
    std::atomic<int32_t> currentCursorType{0};
    bool isFirstFrameRendered = false;
};

class HostJvm {
public:
    static HostJvm& Get();

    bool BootstrapJvm();
    void RegisterRuntime(int64_t runtimeId, std::shared_ptr<RuntimeState> state);
    void UnregisterRuntime(int64_t runtimeId);
    std::shared_ptr<RuntimeState> GetRuntime(int64_t runtimeId);
    std::shared_ptr<RuntimeState> GetRuntimeByHwnd(HWND hwnd);

    bool PrepareRuntime(int64_t runtimeId, const std::string& mainClassName, bool profileEnabled = false);
    void RunRenderLoop(int64_t runtimeId, bool* isHostRunning);
    void Shutdown();

    // Drag and Drop public wrapper methods
    bool DragEnter(int64_t runtimeId, int x, int y, int action, const DragDropPayload& payload, int64_t timestampMillis);
    bool DragOver(int64_t runtimeId, int x, int y, int action, const DragDropPayload& payload, int64_t timestampMillis);
    void DragLeave(int64_t runtimeId);
    void DragEnded(int64_t runtimeId);
    bool Drop(int64_t runtimeId, int x, int y, int action, const DragDropPayload& payload, int64_t timestampMillis);

    JavaVM* GetJvm() const { return jvm_; }

private:
    HostJvm();
    ~HostJvm();

    std::wstring ResolveJavaHome();
    std::string ResolveClasspath();
    std::string ResolveBridgePath();
    std::vector<std::string> LoadBundledJvmConfig(const std::wstring& appDir, std::string& classpathOut);

    void WithAttachedEnv(const std::function<void(JNIEnv*)>& block);
    bool InvokeDragDropMethod(int64_t runtimeId, jmethodID methodId, int x, int y, int action, const DragDropPayload& payload, int64_t timestampMillis);

    JavaVM* jvm_ = nullptr;
    HMODULE jvmDll_ = nullptr;
    std::mutex lock_;
    std::unordered_map<int64_t, std::shared_ptr<RuntimeState>> runtimes_;
    std::unordered_map<HWND, std::shared_ptr<RuntimeState>> hwndRuntimes_;

    // JNI Cached method handles
    jclass runtimeClass_ = nullptr;
    jmethodID initializeMethod_ = nullptr;
    jmethodID enterCurrentRuntimeMethod_ = nullptr;
    jmethodID exitCurrentRuntimeMethod_ = nullptr;
    jmethodID constructorMethod_ = nullptr;
    jmethodID isContentBoundMethod_ = nullptr;
    jmethodID startRuntimeMethod_ = nullptr;
    jmethodID requestFrameMethod_ = nullptr;
    jmethodID closeRuntimeMethod_ = nullptr;

    jmethodID handleExternalDragEnteredMethod_ = nullptr;
    jmethodID handleExternalDragMovedMethod_ = nullptr;
    jmethodID handleExternalDragExitedMethod_ = nullptr;
    jmethodID handleExternalDragEndedMethod_ = nullptr;
    jmethodID handleExternalDropMethod_ = nullptr;
};
