#include "ComposeNativeHost.h"
#include "HostJvm.h"
#include "ComposeDropTarget.h"
#include <windowsx.h>
#include <atomic>
#include <iostream>
#include <imm.h>
#pragma comment(lib, "imm32.lib")
#pragma comment(lib, "ole32.lib")

// Custom window message to marshal IME geometry updates to the UI thread
#define WM_COMPOSE_UPDATE_IME (WM_APP + 100)

// DPI Helpers
static float GetDpiScale(HWND hwnd) {
    typedef UINT(WINAPI *GetDpiForWindowType)(HWND);
    HMODULE user32 = GetModuleHandleA("user32.dll");
    if (user32) {
        auto getDpi = (GetDpiForWindowType)GetProcAddress(user32, "GetDpiForWindow");
        if (getDpi) {
            return (float)getDpi(hwnd) / 96.0f;
        }
    }
    HDC hdc = GetDC(hwnd);
    int dpiX = GetDeviceCaps(hdc, LOGPIXELSX);
    ReleaseDC(hwnd, hdc);
    return (float)dpiX / 96.0f;
}

static void EnableDpiAwareness() {
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    if (user32) {
        typedef BOOL (WINAPI *SetProcessDpiAwarenessContextType)(HANDLE);
        auto setDpiAwareContext = (SetProcessDpiAwarenessContextType)GetProcAddress(user32, "SetProcessDpiAwarenessContext");
        if (setDpiAwareContext) {
            if (setDpiAwareContext((HANDLE)-4)) { // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
                return;
            }
        }
    }

    HMODULE shcore = LoadLibraryW(L"shcore.dll");
    if (shcore) {
        typedef HRESULT (WINAPI *SetProcessDpiAwarenessType)(int);
        auto setDpiAware = (SetProcessDpiAwarenessType)GetProcAddress(shcore, "SetProcessDpiAware");
        if (setDpiAware) {
            if (SUCCEEDED(setDpiAware(2))) { // PROCESS_PER_MONITOR_DPI_AWARE
                FreeLibrary(shcore);
                return;
            }
        }
        FreeLibrary(shcore);
    }

    if (user32) {
        typedef BOOL (WINAPI *SetProcessDPIAwareType)(void);
        auto setDpiAwareFallback = (SetProcessDPIAwareType)GetProcAddress(user32, "SetProcessDPIAware");
        if (setDpiAwareFallback) {
            setDpiAwareFallback();
        }
    }
}

bool ComposeHostInitialize(const ComposeHostConfiguration& config) {
    OleInitialize(NULL);
    EnableDpiAwareness();
    return HostJvm::Get().BootstrapJvm();
}

void ComposeHostShutdown() {
    // Calling DestroyJavaVM() can hang indefinitely if Compose or AWT leaves non-daemon threads running.
    // The OS will reclaim all resources when the process exits.
    OleUninitialize();
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
        mainClassName = "letmutex/compose/nativehost/sample/MainKt";
    }
    bool enableProfile = config.enableProfileRendering;

    state->renderThread = std::thread([runtimeId, mainClassName, enableProfile]() {
        auto s = HostJvm::Get().GetRuntime(runtimeId);
        if (!s) return;
        if (HostJvm::Get().PrepareRuntime(runtimeId, mainClassName, enableProfile)) {
            HostJvm::Get().RunRenderLoop(runtimeId, &s->isRunning);
        } else {
            std::cerr << "Failed to prepare JVM Compose native runtime." << std::endl;
        }
    });

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

bool ComposeRuntimeHandleMessage(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    auto state = HostJvm::Get().GetRuntimeByHwnd(hwnd);
    if (!state) return false;

    auto getModifiers = []() {
        int32_t modifiers = 0;
        if (GetKeyState(VK_CONTROL) < 0) modifiers |= keyboardModifierCtrl;
        if (GetKeyState(VK_LWIN) < 0 || GetKeyState(VK_RWIN) < 0) modifiers |= keyboardModifierMeta;
        if (GetKeyState(VK_MENU) < 0) modifiers |= keyboardModifierAlt;
        if (GetKeyState(VK_SHIFT) < 0) modifiers |= keyboardModifierShift;
        return modifiers;
    };

    auto getButtons = [wParam]() {
        int32_t buttons = 0;
        if (wParam & MK_LBUTTON) buttons |= pointerButtonPrimary;
        if (wParam & MK_RBUTTON) buttons |= pointerButtonSecondary;
        if (wParam & MK_MBUTTON) buttons |= pointerButtonTertiary;
        return buttons;
    };

    switch (message) {
        case WM_SIZE: {
            if (wParam != SIZE_MINIMIZED) {
                int width = LOWORD(lParam);
                int height = HIWORD(lParam);
                state->cachedMetrics.width = width;
                state->cachedMetrics.height = height;
                state->cachedMetrics.isMaximized = (wParam == SIZE_MAXIMIZED);

                {
                    std::lock_guard<std::mutex> lock(state->lock);
                    state->pendingWidth = width;
                    state->pendingHeight = height;
                    state->resizePending = true;
                    state->requestRenderTick = true;
                }
                state->cv.notify_one();
            }
            return true;
        }
        case WM_PAINT: {
            {
                std::lock_guard<std::mutex> lock(state->lock);
                state->requestRenderTick = true;
            }
            state->cv.notify_one();
            return false;
        }
        case WM_ERASEBKGND:
            return true;
        case WM_SETFOCUS:
        case WM_KILLFOCUS: {
            state->cachedMetrics.isFocused = (message == WM_SETFOCUS);
            return true;
        }
        case WM_SETCURSOR: {
            if (LOWORD(lParam) == HTCLIENT) {
                int32_t cursorType = state->currentCursorType.load();
                LPCTSTR cursorName = IDC_ARROW;
                switch (cursorType) {
                    case 0: cursorName = IDC_ARROW; break;
                    case 1: cursorName = IDC_CROSS; break;
                    case 2: cursorName = IDC_IBEAM; break;
                    case 3: cursorName = IDC_WAIT; break;
                    case 4: cursorName = IDC_SIZENESW; break;
                    case 5: cursorName = IDC_SIZENWSE; break;
                    case 6: cursorName = IDC_SIZENWSE; break;
                    case 7: cursorName = IDC_SIZENESW; break;
                    case 8: cursorName = IDC_SIZENS; break;
                    case 9: cursorName = IDC_SIZENS; break;
                    case 10: cursorName = IDC_SIZEWE; break;
                    case 11: cursorName = IDC_SIZEWE; break;
                    case 12: cursorName = IDC_HAND; break;
                    case 13: cursorName = IDC_SIZEALL; break;
                }
                HCURSOR hCursor = LoadCursor(NULL, cursorName);
                SetCursor(hCursor);
                return true;
            }
            break;
        }
        case WM_DPICHANGED: {
            state->cachedMetrics.scale = LOWORD(wParam) / 96.0f;
            RECT* const prcNewWindow = (RECT*)lParam;
            SetWindowPos(hwnd,
                NULL,
                prcNewWindow->left,
                prcNewWindow->top,
                prcNewWindow->right - prcNewWindow->left,
                prcNewWindow->bottom - prcNewWindow->top,
                SWP_NOZORDER | SWP_NOACTIVATE);
            return true;
        }
        case WM_MOUSEMOVE: {
            state->cachedMetrics.hoveredCaptionButton = 0; // Clear NC hover

            if (!state->isMouseTracked) {
                TRACKMOUSEEVENT tme = { sizeof(TRACKMOUSEEVENT), TME_LEAVE, hwnd, 0 };
                TrackMouseEvent(&tme);
                state->isMouseTracked = true;
            }

            PointerEventRecord record = {};
            record.eventType = pointerEventTypeMove;
            record.timestampMillis = GetTickCount64();
            record.x = (float)GET_X_LPARAM(lParam);
            record.y = (float)GET_Y_LPARAM(lParam);
            record.buttonsMask = getButtons();
            record.modifiersMask = getModifiers();
            record.buttonIndex = -1;
            state->inputEvents.EnqueuePointer(record);
            return true;
        }
        case WM_MOUSELEAVE: {
            state->isMouseTracked = false;
            state->cachedMetrics.hoveredCaptionButton = 0;
            
            PointerEventRecord record = {};
            record.eventType = pointerEventTypeExit;
            record.timestampMillis = GetTickCount64();
            record.x = -1.0f;
            record.y = -1.0f;
            record.buttonsMask = getButtons();
            record.modifiersMask = getModifiers();
            record.buttonIndex = -1;
            state->inputEvents.EnqueuePointer(record);
            
            return true;
        }
        case WM_NCMOUSEMOVE: {
            TRACKMOUSEEVENT tme = { sizeof(TRACKMOUSEEVENT), TME_NONCLIENT | TME_LEAVE, hwnd, 0 };
            TrackMouseEvent(&tme);

            int hitTest = wParam;
            int button = 0;
            if (hitTest == HTMINBUTTON) button = 1;
            else if (hitTest == HTMAXBUTTON) button = 2;
            else if (hitTest == HTCLOSE) button = 3;
            
            if (state->cachedMetrics.hoveredCaptionButton != button) {
                state->cachedMetrics.hoveredCaptionButton = button;
                {
                    std::lock_guard<std::mutex> lock(state->lock);
                    state->requestRenderTick = true;
                }
                state->cv.notify_one();
            }
            return false;
        }
        case WM_NCMOUSELEAVE: {
            if (state->cachedMetrics.hoveredCaptionButton != 0) {
                state->cachedMetrics.hoveredCaptionButton = 0;
                {
                    std::lock_guard<std::mutex> lock(state->lock);
                    state->requestRenderTick = true;
                }
                state->cv.notify_one();
            }
            return false;
        }
        case WM_LBUTTONDOWN:
        case WM_RBUTTONDOWN:
        case WM_MBUTTONDOWN: {
            int32_t btnIdx = 0;
            int32_t btnMask = pointerButtonPrimary;
            if (message == WM_RBUTTONDOWN) { btnIdx = 1; btnMask = pointerButtonSecondary; }
            if (message == WM_MBUTTONDOWN) { btnIdx = 2; btnMask = pointerButtonTertiary; }

            PointerEventRecord record = {};
            record.eventType = pointerEventTypePress;
            record.timestampMillis = GetTickCount64();
            record.x = (float)GET_X_LPARAM(lParam);
            record.y = (float)GET_Y_LPARAM(lParam);
            record.buttonsMask = getButtons() | btnMask;
            record.modifiersMask = getModifiers();
            record.buttonIndex = btnIdx;
            state->inputEvents.EnqueuePointer(record);
            return true;
        }
        case WM_LBUTTONUP:
        case WM_RBUTTONUP:
        case WM_MBUTTONUP: {
            int32_t btnIdx = 0;
            int32_t btnMask = pointerButtonPrimary;
            if (message == WM_RBUTTONUP) { btnIdx = 1; btnMask = pointerButtonSecondary; }
            if (message == WM_MBUTTONUP) { btnIdx = 2; btnMask = pointerButtonTertiary; }

            PointerEventRecord record = {};
            record.eventType = pointerEventTypeRelease;
            record.timestampMillis = GetTickCount64();
            record.x = (float)GET_X_LPARAM(lParam);
            record.y = (float)GET_Y_LPARAM(lParam);
            record.buttonsMask = getButtons() & ~btnMask;
            record.modifiersMask = getModifiers();
            record.buttonIndex = btnIdx;
            state->inputEvents.EnqueuePointer(record);
            return true;
        }
        case WM_MOUSEWHEEL: {
            short delta = GET_WHEEL_DELTA_WPARAM(wParam);
            float scrollY = (float)delta / (float)WHEEL_DELTA;
            POINT pt = { GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam) };
            ScreenToClient(hwnd, &pt);

            PointerEventRecord record = {};
            record.eventType = pointerEventTypeScroll;
            record.timestampMillis = GetTickCount64();
            record.x = (float)pt.x;
            record.y = (float)pt.y;
            record.scrollX = 0;
            record.scrollY = -scrollY * 10.0f;
            record.buttonsMask = getButtons();
            record.modifiersMask = getModifiers();
            record.buttonIndex = -1;
            state->inputEvents.EnqueuePointer(record);
            return true;
        }
        case WM_KEYDOWN:
        case WM_SYSKEYDOWN:
        case WM_KEYUP:
        case WM_SYSKEYUP: {
            KeyEventRecord record = {};
            record.eventType = (message == WM_KEYDOWN || message == WM_SYSKEYDOWN) ? keyEventTypeDown : keyEventTypeUp;
            record.timestampMillis = GetTickCount64();
            record.keyCode = (int32_t)wParam;
            record.codePoint = 0;
            record.modifiersMask = getModifiers();
            state->inputEvents.EnqueueKey(record);
            return true;
        }
        case WM_CHAR: {
            wchar_t ch = (wchar_t)wParam;
            if (ch >= 0x20 && ch != 0x7F) {
                TextEventRecord record = {};
                record.eventType = textInputEventTypeCommit;
                record.timestampMillis = GetTickCount64();

                std::wstring wstr(1, ch);
                std::string strUtf8;
                int size_needed = WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), NULL, 0, NULL, NULL);
                strUtf8.resize(size_needed);
                WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), &strUtf8[0], size_needed, NULL, NULL);

                record.text = strUtf8;
                state->inputEvents.EnqueueText(record);
            }
            return true;
        }
        case WM_ENTERSIZEMOVE: {
            state->isInLiveResize.store(true, std::memory_order_release);
            return true;
        }
        case WM_EXITSIZEMOVE: {
            state->isInLiveResize.store(false, std::memory_order_release);
            return true;
        }
        case WM_COMPOSE_UPDATE_IME:
        case WM_IME_STARTCOMPOSITION: {
            // Apply stored text input geometry to the IME composition and candidate windows.
            // This handles two cases:
            //   1. WM_COMPOSE_UPDATE_IME: posted from nativeHostUpdateTextInputGeometry (JNI thread)
            //      to marshal the IMM calls onto the UI thread that owns the HWND.
            //   2. WM_IME_STARTCOMPOSITION: the system sends this when IME composition begins,
            //      and the default handler would reset the position. We intercept it to keep ours.
            // Coordinates from Compose focusedRect are already in physical client pixels,
            // so we do not multiply by cachedMetrics.scale.
            float left = state->focusedRectLeft;
            float top = state->focusedRectTop;
            float right = state->focusedRectRight;
            float bottom = state->focusedRectBottom;

            HIMC himc = ImmGetContext(hwnd);
            if (himc) {
                COMPOSITIONFORM compForm = {};
                compForm.dwStyle = CFS_POINT;
                compForm.ptCurrentPos.x = (LONG)left;
                compForm.ptCurrentPos.y = (LONG)top;
                ImmSetCompositionWindow(himc, &compForm);

                CANDIDATEFORM candidateForm = {};
                candidateForm.dwIndex = 0;
                candidateForm.dwStyle = CFS_EXCLUDE;
                // For CFS_EXCLUDE, ptCurrentPos is the point of interest (caret position)
                candidateForm.ptCurrentPos.x = (LONG)left;
                candidateForm.ptCurrentPos.y = (LONG)top;
                // rcArea defines the exclusion rectangle where the candidate window must not overlap
                candidateForm.rcArea.left = (LONG)left;
                candidateForm.rcArea.top = (LONG)top;
                candidateForm.rcArea.right = (LONG)right;
                candidateForm.rcArea.bottom = (LONG)bottom;
                ImmSetCandidateWindow(himc, &candidateForm);

                ImmReleaseContext(hwnd, himc);
            }
            // For WM_IME_STARTCOMPOSITION, return true to prevent DefWindowProc from resetting the position.
            // For WM_COMPOSE_UPDATE_IME, it's our own message so just return true.
            return true;
        }
    }
    return false;
}
