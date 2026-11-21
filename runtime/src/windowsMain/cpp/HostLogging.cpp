#include "HostLogging.h"
#include "NativeHostExports.h"
#include <windows.h>
#include <cstdio>

std::atomic<bool> g_hostLoggingEnabled{false};

void SetHostLoggingEnabled(bool enabled) {
    g_hostLoggingEnabled.store(enabled);
}

bool IsHostLoggingEnabled() {
    return g_hostLoggingEnabled.load();
}

static ULARGE_INTEGER GetAppProcessStartTime() {
    static ULARGE_INTEGER startTime = []() {
        FILETIME ftCreation, ftExit, ftKernel, ftUser;
        ULARGE_INTEGER ul = { 0 };
        if (GetProcessTimes(GetCurrentProcess(), &ftCreation, &ftExit, &ftKernel, &ftUser)) {
            ul.LowPart = ftCreation.dwLowDateTime;
            ul.HighPart = ftCreation.dwHighDateTime;
            return ul;
        }
        FILETIME ftNow;
        GetSystemTimePreciseAsFileTime(&ftNow);
        ul.LowPart = ftNow.dwLowDateTime;
        ul.HighPart = ftNow.dwHighDateTime;
        return ul;
    }();
    return startTime;
}

void logPhaseTiming(const char* namePtr) {
    if (!namePtr || !IsHostLoggingEnabled()) return;

    FILETIME ftNow;
    GetSystemTimePreciseAsFileTime(&ftNow);
    ULARGE_INTEGER ulNow;
    ulNow.LowPart = ftNow.dwLowDateTime;
    ulNow.HighPart = ftNow.dwHighDateTime;

    ULARGE_INTEGER ulStart = GetAppProcessStartTime();
    double duration = (ulNow.QuadPart > ulStart.QuadPart)
        ? static_cast<double>(ulNow.QuadPart - ulStart.QuadPart) / 10000.0
        : 0.0;

    char buffer[512];
    snprintf(buffer, sizeof(buffer), "[NativeHost] [%.2f ms] Phase: %s\n", duration, namePtr);
    std::fputs(buffer, stdout);
    std::fflush(stdout);
}
