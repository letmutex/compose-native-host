#pragma once

#include <windows.h>
#include "DpiHelper.h"
#include <windowsx.h>
#include <dwmapi.h>
#include <gdiplus.h>
#include <cmath>

#pragma comment(lib, "dwmapi.lib")
#pragma comment(lib, "gdiplus.lib")

class ComposeWindowHelper {
private:
    static ULONG_PTR& GetGdiplusToken() {
        static ULONG_PTR token = 0;
        return token;
    }

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

    // Initializes GDI+ for the splash screen rendering.
    // Call this once at the start of your application.
    static void Initialize() {
        Gdiplus::GdiplusStartupInput gdiplusStartupInput;
        Gdiplus::GdiplusStartup(&GetGdiplusToken(), &gdiplusStartupInput, NULL);
    }

    // Shuts down GDI+. Call this before your application exits.
    static void Shutdown() {
        if (GetGdiplusToken()) {
            Gdiplus::GdiplusShutdown(GetGdiplusToken());
            GetGdiplusToken() = 0;
        }
    }



    // Removes standard Windows borders and extends the client area perfectly
    // into the title bar space.
    static void ExtendContentIntoTitleBar(HWND hwnd) {
        MARGINS margins = {-1, -1, -1, -1};
        DwmExtendFrameIntoClientArea(hwnd, &margins);
        SetWindowPos(hwnd, nullptr, 0, 0, 0, 0, SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    }

    // Call this inside your WM_NCCALCSIZE block to retain the drop shadow
    // while completely removing the standard title bar.
    static LRESULT HideNativeTitleBar(HWND hwnd, WPARAM wParam, LPARAM lParam) {
        if (wParam == TRUE) {
            NCCALCSIZE_PARAMS* params = (NCCALCSIZE_PARAMS*)lParam;
            RECT originalWindowRect = params->rgrc[0];
            DefWindowProcW(hwnd, WM_NCCALCSIZE, wParam, lParam);
            
            if (IsZoomed(hwnd)) {
                // For maximized windows, prevent the UI from rendering off-screen
                int borderY = GetSystemMetrics(SM_CYFRAME) + GetSystemMetrics(SM_CXPADDEDBORDER);
                params->rgrc[0].top = originalWindowRect.top + borderY;
            } else {
                params->rgrc[0].top = originalWindowRect.top;
            }
            return 0;
        }
        return DefWindowProcW(hwnd, WM_NCCALCSIZE, wParam, lParam);
    }

    // Call this inside your WM_NCHITTEST block to enable standard edge resizing
    // while allowing Compose to handle all UI events (HTCLIENT) inside the window.
    static LRESULT HitTestFrameAndButtons(HWND hwnd, WPARAM wParam, LPARAM lParam, const CaptionButtonOptions& options = {}) {
        POINT pt;
        pt.x = GET_X_LPARAM(lParam);
        pt.y = GET_Y_LPARAM(lParam);

        POINT ptClient = pt;
        ScreenToClient(hwnd, &ptClient);

        int customButton = DetectHoveredButton(hwnd, ptClient.x, ptClient.y, options);
        if (customButton == 1) return HTMINBUTTON;
        if (customButton == 2) return HTMAXBUTTON;
        if (customButton == 3) return HTCLOSE;

        LRESULT result = 0;
        if (DwmDefWindowProc(hwnd, WM_NCHITTEST, wParam, lParam, &result)) {
            if (result != HTMINBUTTON && result != HTMAXBUTTON && result != HTCLOSE) {
                return result;
            }
        }

        RECT rcWindow;
        GetWindowRect(hwnd, &rcWindow);

        int borderX = GetSystemMetrics(SM_CXFRAME) + GetSystemMetrics(SM_CXPADDEDBORDER);
        int borderY = GetSystemMetrics(SM_CYFRAME) + GetSystemMetrics(SM_CXPADDEDBORDER);

        bool isLeft = pt.x < rcWindow.left + borderX;
        bool isRight = pt.x >= rcWindow.right - borderX;
        bool isTop = pt.y < rcWindow.top + borderY;
        bool isBottom = pt.y >= rcWindow.bottom - borderY;

        if (isLeft && isTop) return HTTOPLEFT;
        if (isRight && isTop) return HTTOPRIGHT;
        if (isLeft && isBottom) return HTBOTTOMLEFT;
        if (isRight && isBottom) return HTBOTTOMRIGHT;
        if (isLeft) return HTLEFT;
        if (isRight) return HTRIGHT;
        if (isTop) return HTTOP;
        if (isBottom) return HTBOTTOM;

        return HTCLIENT;
    }

    // Detects which caption button is hovered based on client coordinates
    static int DetectHoveredButton(HWND hwnd, int x, int y, const CaptionButtonOptions& options = {}) {
        float dpiScale = GetDpiScale(hwnd);

        int btnWidth = (int)std::round(options.buttonWidth * dpiScale);
        int btnHeight = (int)std::round(options.buttonHeight * dpiScale);
        int totalBtnWidth = btnWidth * 3;

        RECT rcWindow;
        GetClientRect(hwnd, &rcWindow);

        if (y >= 0 && y < btnHeight && x >= rcWindow.right - totalBtnWidth && x < rcWindow.right) {
            int offset = x - (rcWindow.right - totalBtnWidth);
            if (offset < btnWidth) return 1;
            if (offset < btnWidth * 2) return 2;
            return 3;
        }
        return 0;
    }

    // Call this inside your WM_LBUTTONDOWN block to enable dragging the window
    // before Compose intercepts messages.
    // Returns true if the drag was initiated.
    static bool HandleCaptionDrag(HWND hwnd, LPARAM lParam, const CaptionDragOptions& options = {}) {
        float dpiScale = GetDpiScale(hwnd);

        int x = GET_X_LPARAM(lParam);
        int y = GET_Y_LPARAM(lParam);
        
        int titleBarHeight = (int)std::round(options.titleBarHeight * dpiScale);
        int rightReserved = (int)std::round(options.rightReservedWidth * dpiScale);
        int leftReserved = (int)std::round(options.leftReservedWidth * dpiScale);

        RECT rcWindow;
        GetClientRect(hwnd, &rcWindow);

        // If click is in the title bar area but NOT inside the reserved areas
        if (y >= 0 && y < titleBarHeight && x >= leftReserved && x < (rcWindow.right - rightReserved)) {
            ReleaseCapture();
            SendMessage(hwnd, WM_NCLBUTTONDOWN, HTCAPTION, 0);
            return true;
        }
        return false;
    }

    // Handles non-client caption button clicks (minimize, maximize, close) manually.
    // Call this inside your WM_NCLBUTTONDOWN and WM_NCLBUTTONUP blocks.
    // Returns true if the message was handled.
    static bool HandleCaptionButtonClick(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam, const CaptionButtonOptions& options = {}) {
        if (message == WM_NCLBUTTONDOWN) {
            POINT pt;
            pt.x = GET_X_LPARAM(lParam);
            pt.y = GET_Y_LPARAM(lParam);

            POINT ptClient = pt;
            ScreenToClient(hwnd, &ptClient);

            int customButton = DetectHoveredButton(hwnd, ptClient.x, ptClient.y, options);
            if (customButton != 0) {
                SetPropW(hwnd, L"ComposeCustomBtnDown", (HANDLE)(intptr_t)customButton);
                return true;
            }
        } else if (message == WM_NCLBUTTONUP) {
            int pressedButton = (int)(intptr_t)GetPropW(hwnd, L"ComposeCustomBtnDown");
            RemovePropW(hwnd, L"ComposeCustomBtnDown");

            if (pressedButton != 0) {
                POINT pt;
                pt.x = GET_X_LPARAM(lParam);
                pt.y = GET_Y_LPARAM(lParam);

                POINT ptClient = pt;
                ScreenToClient(hwnd, &ptClient);

                int customButton = DetectHoveredButton(hwnd, ptClient.x, ptClient.y, options);
                if (customButton == pressedButton) {
                    if (customButton == 1) PostMessageW(hwnd, WM_SYSCOMMAND, SC_MINIMIZE, 0);
                    if (customButton == 2) PostMessageW(hwnd, WM_SYSCOMMAND, IsZoomed(hwnd) ? SC_RESTORE : SC_MAXIMIZE, 0);
                    if (customButton == 3) PostMessageW(hwnd, WM_SYSCOMMAND, SC_CLOSE, 0);
                }
                return true;
            }
        }
        return false;
    }

    // Draws pixel-perfect geometric caption buttons matching Compose Desktop's default style.
    static void DrawCaptionButtons(HWND hwnd, HDC hdc, int hoveredButton = 0, const CaptionButtonOptions& options = {}) {
        RECT rcWindow;
        GetClientRect(hwnd, &rcWindow);

        float dpiScale = GetDpiScale(hwnd);

        int btnWidth = (int)std::round(options.buttonWidth * dpiScale);
        int btnHeight = (int)std::round(options.buttonHeight * dpiScale);
        int totalBtnWidth = btnWidth * 3;

        Gdiplus::Bitmap bmp(totalBtnWidth, btnHeight, PixelFormat32bppARGB);
        Gdiplus::Graphics graphics(&bmp);
        
        graphics.SetSmoothingMode(Gdiplus::SmoothingModeNone);
        graphics.SetPixelOffsetMode(Gdiplus::PixelOffsetModeNone);

        if (options.isDarkMode) {
            graphics.Clear(Gdiplus::Color(255, 32, 32, 32));
        } else {
            graphics.Clear(Gdiplus::Color(255, 255, 255, 255));
        }

        Gdiplus::SolidBrush bgBrushMinMax(options.isDarkMode ? Gdiplus::Color(25, 255, 255, 255) : Gdiplus::Color(25, 0, 0, 0));
        Gdiplus::SolidBrush bgBrushClose(Gdiplus::Color(255, 232, 17, 35));

        if (hoveredButton == 1) {
            graphics.FillRectangle(&bgBrushMinMax, 0, 0, btnWidth, btnHeight);
        } else if (hoveredButton == 2) {
            graphics.FillRectangle(&bgBrushMinMax, btnWidth, 0, btnWidth, btnHeight);
        } else if (hoveredButton == 3) {
            graphics.FillRectangle(&bgBrushClose, btnWidth * 2, 0, btnWidth, btnHeight);
        }

        int strokePx = (dpiScale >= 2.0f) ? 2 : 1;
        int w = (int)std::round(10.0f * dpiScale);
        int h = (int)std::round(10.0f * dpiScale);

        Gdiplus::Color fgColor = options.isDarkMode ? Gdiplus::Color(255, 255, 255, 255) : Gdiplus::Color(255, 0x17, 0x17, 0x17);
        Gdiplus::SolidBrush fgBrush(fgColor);

        int ox_base = (int)std::round((btnWidth - w) / 2.0f);
        int oy = (int)std::round((btnHeight - h) / 2.0f);

        // Minimize button (left-most)
        {
            int ox = ox_base;
            int topEdge = (int)std::round((h - strokePx) / 2.0f);
            graphics.FillRectangle(&fgBrush, ox, oy + topEdge, w, strokePx);
        }

        // Maximize button (middle)
        {
            int ox = btnWidth + ox_base;
            
            if (IsZoomed(hwnd)) {
                int rectSize = (int)std::round(w * 0.8f);
                int offset = (int)std::round(w * 0.2f);
                
                // Front rect
                graphics.FillRectangle(&fgBrush, ox, oy + offset, rectSize, strokePx); // Top
                graphics.FillRectangle(&fgBrush, ox, oy + offset + rectSize - strokePx, rectSize, strokePx); // Bottom
                graphics.FillRectangle(&fgBrush, ox, oy + offset, strokePx, rectSize); // Left
                graphics.FillRectangle(&fgBrush, ox + rectSize - strokePx, oy + offset, strokePx, rectSize); // Right
                
                // Back rect lines
                graphics.FillRectangle(&fgBrush, ox + offset, oy, w - offset, strokePx); // Top edge
                graphics.FillRectangle(&fgBrush, ox + w - strokePx, oy, strokePx, rectSize); // Right edge
                graphics.FillRectangle(&fgBrush, ox + offset, oy, strokePx, offset); // Left edge stub
                graphics.FillRectangle(&fgBrush, ox + rectSize, oy + rectSize - strokePx, w - rectSize, strokePx); // Bottom edge stub
            } else {
                // Normal rect
                graphics.FillRectangle(&fgBrush, ox, oy, w, strokePx); // Top
                graphics.FillRectangle(&fgBrush, ox, oy + h - strokePx, w, strokePx); // Bottom
                graphics.FillRectangle(&fgBrush, ox, oy, strokePx, h); // Left
                graphics.FillRectangle(&fgBrush, ox + w - strokePx, oy, strokePx, h); // Right
            }
        }

        // Close button (right-most)
        {
            int ox = btnWidth * 2 + ox_base;
            
            graphics.SetSmoothingMode(Gdiplus::SmoothingModeHighQuality);
            graphics.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHighQuality);
            
            float closeStroke = 1.2f * dpiScale;
            Gdiplus::Color closeFgColor = (hoveredButton == 3) ? Gdiplus::Color(255, 255, 255, 255) : fgColor;
            Gdiplus::Pen closePen(closeFgColor, closeStroke);
            
            graphics.DrawLine(&closePen, (float)ox, (float)oy, (float)(ox + w), (float)(oy + h));
            graphics.DrawLine(&closePen, (float)ox, (float)(oy + h), (float)(ox + w), (float)oy);
        }

        Gdiplus::Graphics screenGraphics(hdc);
        int destX = rcWindow.right - totalBtnWidth;
        int destY = 0;
        screenGraphics.DrawImage(&bmp, destX, destY, totalBtnWidth, btnHeight);
    }
};
