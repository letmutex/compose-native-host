#pragma once

#include <windows.h>

// DPI Helpers
inline float GetDpiScale(HWND hwnd) {
    typedef UINT(WINAPI *GetDpiForWindowType)(HWND);
    // Resolve GetDpiForWindow once (thread-safe static init); it may be null on
    // older Windows versions where the per-monitor-DPI API is unavailable.
    static const auto getDpi = []() -> GetDpiForWindowType {
        HMODULE user32 = GetModuleHandleW(L"user32.dll");
        if (user32) {
            return (GetDpiForWindowType)GetProcAddress(user32, "GetDpiForWindow");
        }
        return nullptr;
    }();
    if (getDpi) {
        return (float)getDpi(hwnd) / 96.0f;
    }
    HDC hdc = GetDC(hwnd);
    int dpiX = GetDeviceCaps(hdc, LOGPIXELSX);
    ReleaseDC(hwnd, hdc);
    return (float)dpiX / 96.0f;
}

inline void EnableDpiAwareness() {
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

    HMODULE shcore = LoadLibraryExW(L"shcore.dll", nullptr, 0x00000800 /* LOAD_LIBRARY_SEARCH_SYSTEM32 */);
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
