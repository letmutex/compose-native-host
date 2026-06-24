#include "ComposeNativeHost.h"
#include "ComposeWindowHelper.h"
#include <iostream>
#include <vector>
#include <memory>
#include <algorithm>
#include <thread>
#include <objidl.h>

#define WM_OPEN_WINDOW (WM_USER + 1)

struct SampleWindow {
    HWND hwnd = nullptr;
    HCOMPOSERUNTIME runtime = nullptr;
    bool isFirstFrameRendered = false;
    int hoveredButton = 0;
};

std::vector<std::shared_ptr<SampleWindow>> g_ActiveWindows;
HINSTANCE g_hInstance = nullptr;
bool g_IsComposeInitialized = false;

LRESULT CALLBACK WndProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);
void CreateSampleWindow();

std::shared_ptr<SampleWindow> GetSampleWindow(HWND hwnd) {
    for (auto& window : g_ActiveWindows) {
        if (window->hwnd == hwnd) {
            return window;
        }
    }
    return nullptr;
}


void OnComposeEvent(HCOMPOSERUNTIME runtime, const char* name, const char* payload, void* userData) {
    std::cout << "[C++ Callback] Event received: " << name << ", payload: " << (payload ? payload : "null") << std::endl;
    if (strcmp(name, "phaseChanged") == 0 && payload && strcmp(payload, "firstFramePresented") == 0) {
        HWND hwnd = (HWND)userData;
        if (auto window = GetSampleWindow((HWND)userData)) {
            window->isFirstFrameRendered = true;
        }
    } else if (strcmp(name, "sample.window.open") == 0) {
        HWND hwnd = (HWND)userData;
        if (hwnd) {
            PostMessageW(hwnd, WM_OPEN_WINDOW, 0, 0);
        }
    }
}

#define WM_INIT_COMPOSE (WM_APP + 1)

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    g_hInstance = hInstance;

    OleInitialize(NULL);
    ComposeWindowHelper::Initialize();

    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    if (user32) {
        typedef BOOL(WINAPI * SetProcessDpiAwarenessContextType)(HANDLE);
        auto setDpiAwareContext = (SetProcessDpiAwarenessContextType)GetProcAddress(user32, "SetProcessDpiAwarenessContext");
        if (setDpiAwareContext) {
            setDpiAwareContext((HANDLE)-4); // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
        }
    }

    const wchar_t CLASS_NAME[] = L"ComposeRuntimeSampleWindow";

    WNDCLASSW wc = {};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInstance;
    wc.lpszClassName = CLASS_NAME;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)GetStockObject(WHITE_BRUSH);

    if (!RegisterClassW(&wc)) {
        return 1;
    }

    CreateSampleWindow();

    if (g_ActiveWindows.empty()) {
        return 1;
    }

    // Start JVM asynchronously so it doesn't block the UI
    std::thread([]() {
        ComposeHostConfiguration hostConfig;
        hostConfig.enableLogging = true;
        if (ComposeHostInitialize(hostConfig)) {
            g_IsComposeInitialized = true;
            // Tell the main thread to create the compose runtimes
            for (auto& window : g_ActiveWindows) {
                PostMessageW(window->hwnd, WM_INIT_COMPOSE, 0, 0);
            }
        } else {
            std::cerr << "Failed to initialize Compose Host" << std::endl;
        }
    }).detach();

    MSG msg = {};
    while (GetMessageW(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    ComposeHostShutdown();
    ComposeWindowHelper::Shutdown();
    ExitProcess((UINT)msg.wParam);
    return (int)msg.wParam;
}

void CreateSampleWindow() {
    static int s_WindowIndex = 0;
    s_WindowIndex++;

    std::wstring title = L"Compose Native Host Sample Mixed";
    if (s_WindowIndex > 1) {
        title += L" " + std::to_wstring(s_WindowIndex);
    }

    const wchar_t CLASS_NAME[] = L"ComposeRuntimeSampleWindow";

    // Cascade window positions
    int x = CW_USEDEFAULT;
    int y = CW_USEDEFAULT;
    if (!g_ActiveWindows.empty()) {
        RECT rect;
        GetWindowRect(g_ActiveWindows.back()->hwnd, &rect);
        x = rect.left + 32;
        y = rect.top + 32;
    }

    HWND hwnd = CreateWindowExW(
        0,
        CLASS_NAME,
        title.c_str(),
        WS_OVERLAPPEDWINDOW,
        x, y, 1320, 920,
        nullptr,
        nullptr,
        g_hInstance,
        nullptr
    );

    if (hwnd == NULL) {
        std::cerr << "Failed to create window" << std::endl;
        return;
    }

    ComposeWindowHelper::ExtendContentIntoTitleBar(hwnd);

    auto window = std::make_shared<SampleWindow>();
    window->hwnd = hwnd;
    g_ActiveWindows.push_back(window);

    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);
    
    if (g_IsComposeInitialized) {
        PostMessageW(hwnd, WM_INIT_COMPOSE, 0, 0);
    }
}

LRESULT CALLBACK WndProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    auto window = GetSampleWindow(hwnd);
    bool isFirstFrameRendered = window ? window->isFirstFrameRendered : false;
    int hoveredButton = window ? window->hoveredButton : 0;

    if (message == WM_LBUTTONDOWN) {
        // If Compose hasn't fully rendered the first frame, we intercept the click BEFORE Compose
        // processes it, enabling the splash screen to be dragged natively without breaking Compose later.
        if (!isFirstFrameRendered) {
            if (ComposeWindowHelper::HandleCaptionDrag(hwnd, lParam)) {
                return 0;
            }
        }
    }

    if (ComposeRuntimeHandleMessage(hwnd, message, wParam, lParam)) {
        return 0;
    }

    switch (message) {
        case WM_NCMOUSEMOVE: {
            if (!isFirstFrameRendered && window) {
                int hovered = 0;
                if (wParam == HTMINBUTTON) hovered = 1;
                else if (wParam == HTMAXBUTTON) hovered = 2;
                else if (wParam == HTCLOSE) hovered = 3;
                
                if (window->hoveredButton != hovered) {
                    window->hoveredButton = hovered;
                    InvalidateRect(hwnd, NULL, FALSE);
                }
            }
            break;
        }
        case WM_NCMOUSELEAVE: {
            if (!isFirstFrameRendered && window) {
                if (window->hoveredButton != 0) {
                    window->hoveredButton = 0;
                    InvalidateRect(hwnd, NULL, FALSE);
                }
            }
            break;
        }
        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hwnd, &ps);
            if (!isFirstFrameRendered) {
                ComposeWindowHelper::DrawCaptionButtons(hwnd, hdc, hoveredButton);
            }
            EndPaint(hwnd, &ps);
            return 0;
        }
        case WM_NCCALCSIZE: {
            return ComposeWindowHelper::HideNativeTitleBar(hwnd, wParam, lParam);
        }
        case WM_NCHITTEST: {
            return ComposeWindowHelper::HitTestBorderlessResize(hwnd, wParam, lParam);
        }
        case WM_OPEN_WINDOW:
            CreateSampleWindow();
            return 0;

        case WM_INIT_COMPOSE: {
            if (window && !window->runtime) {
                ComposeRuntimeConfiguration config;
                config.kotlinMainClass = "letmutex.compose.nativehost.sample.HostedMainKt";
                config.eventCallback = OnComposeEvent;
                config.eventUserData = hwnd;
                window->runtime = ComposeRuntimeCreate(hwnd, config);
            }
            return 0;
        }
        case WM_DESTROY: {
            for (auto it = g_ActiveWindows.begin(); it != g_ActiveWindows.end(); ++it) {
                if ((*it)->hwnd == hwnd) {
                    if ((*it)->runtime) {
                        ComposeRuntimeDestroy((*it)->runtime);
                    }
                    g_ActiveWindows.erase(it);
                    break;
                }
            }
            if (g_ActiveWindows.empty()) {
                PostQuitMessage(0);
            }
            return 0;
        }
    }
    return DefWindowProcW(hwnd, message, wParam, lParam);
}
