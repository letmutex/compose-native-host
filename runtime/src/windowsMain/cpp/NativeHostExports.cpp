#include "NativeHostExports.h"
#include "HostJvm.h"
#include <iostream>

// Custom window message to marshal IME geometry updates to the UI thread
#define WM_COMPOSE_UPDATE_IME (WM_APP + 100)

EXPORT int64_t nativeHostGetWindowInfo(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (!state) return 0;
    return PackWindowInfo(
        state->cachedMetrics.width,
        state->cachedMetrics.height,
        state->cachedMetrics.scale,
        state->cachedMetrics.refreshRate,
        state->cachedMetrics.isFocused,
        state->cachedMetrics.isMaximized,
        state->cachedMetrics.hoveredCaptionButton
    );
}

EXPORT int64_t nativeHostD3DAdapterPtr(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    return state ? (jlong)state->renderer.GetAdapter() : 0;
}

EXPORT int64_t nativeHostD3DDevicePtr(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    return state ? (jlong)state->renderer.GetDevice() : 0;
}

EXPORT int64_t nativeHostD3DQueuePtr(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    return state ? (jlong)state->renderer.GetCommandQueue() : 0;
}

EXPORT int64_t nativeHostAcquireDrawableTexturePtr(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (!state) return 0;

    int resizeWidth = 0;
    int resizeHeight = 0;
    bool doResize = false;
    {
        std::lock_guard<std::mutex> lock(state->lock);
        if (state->resizePending) {
            resizeWidth = state->pendingWidth;
            resizeHeight = state->pendingHeight;
            state->resizePending = false;
            doResize = true;
        }
    }
    if (doResize && resizeWidth > 0 && resizeHeight > 0) {
        state->renderer.Resize(resizeWidth, resizeHeight);
    }
    int64_t ptr = state->renderer.AcquireDrawableTexturePtr();
    if (ptr == 0 && doResize) {
        std::lock_guard<std::mutex> lock(state->lock);
        state->resizePending = true;
    }
    return ptr;
}

EXPORT void nativeHostPresentDrawable(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (state) {
        // Skip VSync during live resize to avoid DWM stall in the modal resize loop
        UINT syncInterval = state->isInLiveResize.load(std::memory_order_acquire) ? 0 : 1;
        state->renderer.Present(syncInterval);
        
        if (!state->isFirstFrameRendered) {
            state->isFirstFrameRendered = true;
            auto cb = state->eventCallback.load();
            if (cb) {
                cb((HCOMPOSERUNTIME)state->runtimeId, "phaseChanged", "firstFramePresented", state->eventUserData.load());
            }
        }
    }
}

EXPORT void nativeHostWindowDrag(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (state && state->hwnd) {
        ReleaseCapture();
        SendMessage(state->hwnd, WM_NCLBUTTONDOWN, HTCAPTION, 0);
    }
}

EXPORT void nativeHostWindowMinimize(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (state && state->hwnd) {
        ShowWindow(state->hwnd, SW_MINIMIZE);
    }
}

EXPORT void nativeHostWindowMaximize(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (state && state->hwnd) {
        if (IsZoomed(state->hwnd)) {
            ShowWindow(state->hwnd, SW_RESTORE);
        } else {
            ShowWindow(state->hwnd, SW_MAXIMIZE);
        }
    }
}

EXPORT void nativeHostWindowClose(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (state && state->hwnd) {
        PostMessageW(state->hwnd, WM_CLOSE, 0, 0);
    }
}

EXPORT void nativeHostRequestRenderTick(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (state) {
        {
            std::lock_guard<std::mutex> lock(state->lock);
            state->requestRenderTick = true;
        }
        state->cv.notify_one();
    }
}

EXPORT void nativeHostSetPointerIcon(int64_t runtimeId, int32_t cursorType) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (state) {
        state->currentCursorType.store(cursorType);
        PostMessageW(state->hwnd, WM_SETCURSOR, (WPARAM)state->hwnd, MAKELPARAM(HTCLIENT, WM_MOUSEMOVE));
    }
}

EXPORT void nativeHostEmitAppEvent(int64_t runtimeId, const char *namePtr, const char *payloadPtr) {
    std::cout << "nativeHostEmitAppEvent: " << (namePtr ? namePtr : "null") 
              << ", payload: " << (payloadPtr ? payloadPtr : "null") << std::endl;

    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (state) {
        auto callback = state->eventCallback.load();
        if (callback) {
            callback((HCOMPOSERUNTIME)runtimeId, namePtr, payloadPtr, state->eventUserData.load());
        }
    }
}

EXPORT void nativeHostLogPhaseTiming(const char *namePtr) {
    std::cout << "nativeHostLogPhaseTiming: " << (namePtr ? namePtr : "null") << std::endl;
}

EXPORT void nativeHostEmitProfileFrameSample(
    int64_t runtimeId,
    int32_t refreshRate,
    int32_t rendered,
    int32_t dispatchDelayMicros,
    int32_t inputDrainMicros,
    int32_t acquireDrawableMicros,
    int32_t sceneRenderMicros,
    int32_t submitMicros
) {
    // Profile frame sample stub
}

EXPORT int32_t nativeHostProfileRenderingEnabled(int64_t runtimeId) {
    return 0;
}

EXPORT void nativeHostUpdateTextInputGeometry(
    int64_t runtimeId,
    float focusedRectLeft,
    float focusedRectTop,
    float focusedRectRight,
    float focusedRectBottom,
    int32_t selectionStart,
    int32_t selectionEnd,
    int32_t compositionStart,
    int32_t compositionEnd
) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (state) {
        state->focusedRectLeft = focusedRectLeft;
        state->focusedRectTop = focusedRectTop;
        state->focusedRectRight = focusedRectRight;
        state->focusedRectBottom = focusedRectBottom;
        state->selectionStart = selectionStart;
        state->selectionEnd = selectionEnd;
        state->compositionStart = compositionStart;
        state->compositionEnd = compositionEnd;

        // Post to the UI thread to update IME window positions
        // (ImmSetCompositionWindow/ImmSetCandidateWindow must be called on the thread that owns the HWND)
        PostMessageW(state->hwnd, WM_COMPOSE_UPDATE_IME, 0, 0);
    }
}

EXPORT void nativeHostClearTextInputGeometry(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (state) {
        state->focusedRectLeft = 0.0f;
        state->focusedRectTop = 0.0f;
        state->focusedRectRight = 0.0f;
        state->focusedRectBottom = 0.0f;
        state->selectionStart = -1;
        state->selectionEnd = -1;
        state->compositionStart = -1;
        state->compositionEnd = -1;
    }
}

EXPORT int32_t nativeHostIsRunning(void) {
    return 1;
}

extern bool g_useSharedLibraryRuntime;

EXPORT int32_t nativeHostUsesSharedLibraryRuntime(void) {
    return g_useSharedLibraryRuntime ? 1 : 0;
}

EXPORT void nativeHostWaitForShutdown(void) {
    // Shutdown synchronization wait stub
}

EXPORT int32_t nativeHostIsWindowAttached(int64_t runtimeId) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    return (state && state->hwnd != nullptr) ? 1 : 0;
}

EXPORT int32_t nativeHostWaitForWindowAttached(int64_t runtimeId) {
    // Wait until window attaches
    while (true) {
        auto state = HostJvm::Get().GetRuntime(runtimeId);
        if (!state || !state->isRunning) {
            return 0;
        }
        if (state->hwnd != nullptr) {
            return 1;
        }
        Sleep(10);
    }
    return 0;
}

EXPORT int32_t nativeHostPollFrameStateData(
    int64_t runtimeId,
    int32_t maxCount,
    int64_t *packedWindowInfoOut,
    int64_t *recordsOut,
    char **textsOut
) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (!state) return 0;

    *packedWindowInfoOut = PackWindowInfo(
        state->cachedMetrics.width,
        state->cachedMetrics.height,
        state->cachedMetrics.scale,
        state->cachedMetrics.refreshRate,
        state->cachedMetrics.isFocused,
        state->cachedMetrics.isMaximized,
        state->cachedMetrics.hoveredCaptionButton
    );

    return state->inputEvents.PollBatch(maxCount, recordsOut, textsOut);
}
