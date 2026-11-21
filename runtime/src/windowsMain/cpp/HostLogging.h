#pragma once
#include <atomic>

extern std::atomic<bool> g_hostLoggingEnabled;

void SetHostLoggingEnabled(bool enabled);
bool IsHostLoggingEnabled();
void logPhaseTiming(const char* namePtr);
