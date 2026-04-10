#ifndef COMPOSE_NATIVE_HOST_NATIVE_HOST_EXPORTS_H
#define COMPOSE_NATIVE_HOST_NATIVE_HOST_EXPORTS_H

#include <stdint.h>

int64_t nativeHostGetWindowInfo(int64_t runtimeId);
int64_t nativeHostMetalDevicePtr(int64_t runtimeId);
int64_t nativeHostMetalQueuePtr(int64_t runtimeId);
int64_t nativeHostAcquireDrawableTexturePtr(int64_t runtimeId);
void nativeHostPresentDrawable(int64_t runtimeId);
void nativeHostRequestRenderTick(int64_t runtimeId);
void nativeHostEmitAppEvent(int64_t runtimeId, const char *namePtr, const char *payloadPtr);
void nativeHostLogPhaseTiming(const char *namePtr);
void nativeHostEmitProfileFrameSample(
    int64_t runtimeId,
    int32_t refreshRate,
    int32_t rendered,
    int32_t dispatchDelayMicros,
    int32_t inputDrainMicros,
    int32_t acquireDrawableMicros,
    int32_t sceneRenderMicros,
    int32_t submitMicros
);
int32_t nativeHostProfileRenderingEnabled(int64_t runtimeId);
void nativeHostUpdateTextInputGeometry(
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
void nativeHostClearTextInputGeometry(int64_t runtimeId);
int32_t nativeHostIsRunning(void);
int32_t nativeHostUsesSharedLibraryRuntime(void);
void nativeHostWaitForShutdown(void);
int32_t nativeHostIsWindowAttached(int64_t runtimeId);
int32_t nativeHostWaitForWindowAttached(int64_t runtimeId);
int32_t nativeHostPollFrameStateData(
    int64_t runtimeId,
    int32_t maxCount,
    int64_t *packedWindowInfoOut,
    int64_t *recordsOut,
    char **textsOut
);

#endif
