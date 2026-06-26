#ifndef COMPOSE_NATIVE_HOST_NATIVE_HOST_EXPORTS_H
#define COMPOSE_NATIVE_HOST_NATIVE_HOST_EXPORTS_H

#include <stdint.h>

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

EXPORT int64_t nativeHostGetWindowInfo(int64_t runtimeId);
EXPORT int64_t nativeHostD3DAdapterPtr(int64_t runtimeId);
EXPORT int64_t nativeHostD3DDevicePtr(int64_t runtimeId);
EXPORT int64_t nativeHostD3DQueuePtr(int64_t runtimeId);
EXPORT int64_t nativeHostAcquireDrawableTexturePtr(int64_t runtimeId);
EXPORT void nativeHostPresentDrawable(int64_t runtimeId);

EXPORT void nativeHostWindowDrag(int64_t runtimeId);
EXPORT void nativeHostWindowMinimize(int64_t runtimeId);
EXPORT void nativeHostWindowMaximize(int64_t runtimeId);
EXPORT void nativeHostWindowClose(int64_t runtimeId);

EXPORT void nativeHostRequestRenderTick(int64_t runtimeId);
EXPORT void nativeHostSetPointerIcon(int64_t runtimeId, int32_t cursorType);
EXPORT void nativeHostEmitAppEvent(int64_t runtimeId, const char *namePtr, const char *payloadPtr);
EXPORT void nativeHostLogPhaseTiming(const char *namePtr);
EXPORT void nativeHostEmitProfileFrameSample(
    int64_t runtimeId,
    int32_t refreshRate,
    int32_t rendered,
    int32_t dispatchDelayMicros,
    int32_t inputDrainMicros,
    int32_t acquireDrawableMicros,
    int32_t sceneRenderMicros,
    int32_t submitMicros
);
EXPORT int32_t nativeHostProfileRenderingEnabled(int64_t runtimeId);
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
);
EXPORT void nativeHostClearTextInputGeometry(int64_t runtimeId);
EXPORT int32_t nativeHostIsRunning(void);
EXPORT int32_t nativeHostUsesSharedLibraryRuntime(void);
EXPORT void nativeHostWaitForShutdown(void);
EXPORT int32_t nativeHostIsWindowAttached(int64_t runtimeId);
EXPORT int32_t nativeHostWaitForWindowAttached(int64_t runtimeId);
EXPORT int32_t nativeHostPollFrameStateData(
    int64_t runtimeId,
    int32_t maxCount,
    int64_t *packedWindowInfoOut,
    int64_t *recordsOut,
    char **textsOut
);

#ifdef __cplusplus
}
#endif

#endif
