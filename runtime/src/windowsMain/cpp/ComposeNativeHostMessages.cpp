#include "HostJvm.h"
#include <windowsx.h>
#include <imm.h>

#define WM_COMPOSE_UPDATE_IME (WM_APP + 100)

static int32_t GetModifiers() {
    int32_t modifiers = 0;
    if (GetKeyState(VK_CONTROL) < 0) modifiers |= keyboardModifierCtrl;
    if (GetKeyState(VK_LWIN) < 0 || GetKeyState(VK_RWIN) < 0) modifiers |= keyboardModifierMeta;
    if (GetKeyState(VK_MENU) < 0) modifiers |= keyboardModifierAlt;
    if (GetKeyState(VK_SHIFT) < 0) modifiers |= keyboardModifierShift;
    return modifiers;
}

static int32_t GetButtons(WPARAM wParam) {
    int32_t buttons = 0;
    if (wParam & MK_LBUTTON) buttons |= pointerButtonPrimary;
    if (wParam & MK_RBUTTON) buttons |= pointerButtonSecondary;
    if (wParam & MK_MBUTTON) buttons |= pointerButtonTertiary;
    return buttons;
}

static std::string WideToUtf8(const std::wstring& wstr) {
    if (wstr.empty()) return "";
    int size_needed = WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), NULL, 0, NULL, NULL);
    std::string strUtf8(size_needed, 0);
    WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), &strUtf8[0], size_needed, NULL, NULL);
    return strUtf8;
}

static bool HandleSize(const std::shared_ptr<RuntimeState>& state, WPARAM wParam, LPARAM lParam) {
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

static bool HandlePaint(const std::shared_ptr<RuntimeState>& state) {
    {
        std::lock_guard<std::mutex> lock(state->lock);
        state->requestRenderTick = true;
    }
    state->cv.notify_one();
    return false;
}

static bool HandleSetCursor(const std::shared_ptr<RuntimeState>& state, LPARAM lParam) {
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
    return false;
}

static bool HandleDpiChanged(const std::shared_ptr<RuntimeState>& state, HWND hwnd, WPARAM wParam, LPARAM lParam) {
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

static bool HandleMouseMove(const std::shared_ptr<RuntimeState>& state, HWND hwnd, WPARAM wParam, LPARAM lParam) {
    state->cachedMetrics.hoveredCaptionButton = 0;

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
    record.buttonsMask = GetButtons(wParam);
    record.modifiersMask = GetModifiers();
    record.buttonIndex = -1;
    state->inputEvents.EnqueuePointer(record);
    return true;
}

static bool HandleMouseLeave(const std::shared_ptr<RuntimeState>& state, WPARAM wParam) {
    state->isMouseTracked = false;
    state->cachedMetrics.hoveredCaptionButton = 0;

    PointerEventRecord record = {};
    record.eventType = pointerEventTypeExit;
    record.timestampMillis = GetTickCount64();
    record.x = -1.0f;
    record.y = -1.0f;
    record.buttonsMask = GetButtons(wParam);
    record.modifiersMask = GetModifiers();
    record.buttonIndex = -1;
    state->inputEvents.EnqueuePointer(record);
    return true;
}

static bool HandleNcMouseMove(const std::shared_ptr<RuntimeState>& state, HWND hwnd, WPARAM wParam) {
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

static bool HandleNcMouseLeave(const std::shared_ptr<RuntimeState>& state) {
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

static bool HandleMouseButtonDown(const std::shared_ptr<RuntimeState>& state, UINT message, WPARAM wParam, LPARAM lParam) {
    int32_t btnIdx = 0;
    int32_t btnMask = pointerButtonPrimary;
    if (message == WM_RBUTTONDOWN) { btnIdx = 1; btnMask = pointerButtonSecondary; }
    if (message == WM_MBUTTONDOWN) { btnIdx = 2; btnMask = pointerButtonTertiary; }

    PointerEventRecord record = {};
    record.eventType = pointerEventTypePress;
    record.timestampMillis = GetTickCount64();
    record.x = (float)GET_X_LPARAM(lParam);
    record.y = (float)GET_Y_LPARAM(lParam);
    record.buttonsMask = GetButtons(wParam) | btnMask;
    record.modifiersMask = GetModifiers();
    record.buttonIndex = btnIdx;
    state->inputEvents.EnqueuePointer(record);
    return true;
}

static bool HandleMouseButtonUp(const std::shared_ptr<RuntimeState>& state, UINT message, WPARAM wParam, LPARAM lParam) {
    int32_t btnIdx = 0;
    int32_t btnMask = pointerButtonPrimary;
    if (message == WM_RBUTTONUP) { btnIdx = 1; btnMask = pointerButtonSecondary; }
    if (message == WM_MBUTTONUP) { btnIdx = 2; btnMask = pointerButtonTertiary; }

    PointerEventRecord record = {};
    record.eventType = pointerEventTypeRelease;
    record.timestampMillis = GetTickCount64();
    record.x = (float)GET_X_LPARAM(lParam);
    record.y = (float)GET_Y_LPARAM(lParam);
    record.buttonsMask = GetButtons(wParam) & ~btnMask;
    record.modifiersMask = GetModifiers();
    record.buttonIndex = btnIdx;
    state->inputEvents.EnqueuePointer(record);
    return true;
}

static bool HandleMouseWheel(const std::shared_ptr<RuntimeState>& state, HWND hwnd, WPARAM wParam, LPARAM lParam) {
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
    record.buttonsMask = GetButtons(wParam);
    record.modifiersMask = GetModifiers();
    record.buttonIndex = -1;
    state->inputEvents.EnqueuePointer(record);
    return true;
}

static bool HandleKey(const std::shared_ptr<RuntimeState>& state, UINT message, WPARAM wParam, LPARAM lParam) {
    KeyEventRecord record = {};
    record.eventType = (message == WM_KEYDOWN || message == WM_SYSKEYDOWN) ? keyEventTypeDown : keyEventTypeUp;
    record.timestampMillis = GetTickCount64();
    record.keyCode = (int32_t)wParam;
    record.codePoint = 0;
    record.modifiersMask = GetModifiers();
    state->inputEvents.EnqueueKey(record);
    return true;
}

static bool HandleChar(const std::shared_ptr<RuntimeState>& state, WPARAM wParam) {
    wchar_t ch = (wchar_t)wParam;
    if (ch >= 0x20 && ch != 0x7F) {
        TextEventRecord record = {};
        record.eventType = textInputEventTypeCommit;
        record.timestampMillis = GetTickCount64();

        std::wstring wstr(1, ch);
        std::string strUtf8 = WideToUtf8(wstr);

        record.text = strUtf8;
        state->inputEvents.EnqueueText(record);
    }
    return true;
}

static bool HandleIme(const std::shared_ptr<RuntimeState>& state, HWND hwnd, UINT message) {
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
        candidateForm.ptCurrentPos.x = (LONG)left;
        candidateForm.ptCurrentPos.y = (LONG)top;
        candidateForm.rcArea.left = (LONG)left;
        candidateForm.rcArea.top = (LONG)top;
        candidateForm.rcArea.right = (LONG)right;
        candidateForm.rcArea.bottom = (LONG)bottom;
        ImmSetCandidateWindow(himc, &candidateForm);

        ImmReleaseContext(hwnd, himc);
    }
    return true;
}

bool ComposeRuntimeHandleMessage(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    auto state = HostJvm::Get().GetRuntimeByHwnd(hwnd);
    if (!state) return false;

    switch (message) {
        case WM_SIZE:
            return HandleSize(state, wParam, lParam);
        case WM_PAINT:
            return HandlePaint(state);
        case WM_ERASEBKGND:
            return true;
        case WM_SETFOCUS:
        case WM_KILLFOCUS:
            state->cachedMetrics.isFocused = (message == WM_SETFOCUS);
            return true;
        case WM_SETCURSOR:
            return HandleSetCursor(state, lParam);
        case WM_DPICHANGED:
            return HandleDpiChanged(state, hwnd, wParam, lParam);
        case WM_MOUSEMOVE:
            return HandleMouseMove(state, hwnd, wParam, lParam);
        case WM_MOUSELEAVE:
            return HandleMouseLeave(state, wParam);
        case WM_NCMOUSEMOVE:
            return HandleNcMouseMove(state, hwnd, wParam);
        case WM_NCMOUSELEAVE:
            return HandleNcMouseLeave(state);
        case WM_LBUTTONDOWN:
        case WM_RBUTTONDOWN:
        case WM_MBUTTONDOWN:
            return HandleMouseButtonDown(state, message, wParam, lParam);
        case WM_LBUTTONUP:
        case WM_RBUTTONUP:
        case WM_MBUTTONUP:
            return HandleMouseButtonUp(state, message, wParam, lParam);
        case WM_MOUSEWHEEL:
            return HandleMouseWheel(state, hwnd, wParam, lParam);
        case WM_KEYDOWN:
        case WM_SYSKEYDOWN:
        case WM_KEYUP:
        case WM_SYSKEYUP:
            return HandleKey(state, message, wParam, lParam);
        case WM_CHAR:
            return HandleChar(state, wParam);
        case WM_ENTERSIZEMOVE:
            state->isInLiveResize.store(true, std::memory_order_release);
            return true;
        case WM_EXITSIZEMOVE:
            state->isInLiveResize.store(false, std::memory_order_release);
            return true;
        case WM_COMPOSE_UPDATE_IME:
        case WM_IME_STARTCOMPOSITION:
            return HandleIme(state, hwnd, message);
    }
    return false;
}
