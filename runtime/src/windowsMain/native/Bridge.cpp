#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include <mutex>
#include "NativeHostExports.h"
#include <windows.h>
#include <uxtheme.h>

#pragma comment(lib, "uxtheme.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "user32.lib")

struct NativeFrameStateJniCache {
    jclass frameStateClass;
    jfieldID windowInfoField;
    jfieldID eventCountField;
    jfieldID recordsField;
    jfieldID textsField;
};

static NativeFrameStateJniCache nativeFrameStateJniCache = {0};
static std::mutex jniCacheMutex;

static jboolean ensureNativeFrameStateJniCache(
    JNIEnv *env,
    jobject frameState
) {
    if (nativeFrameStateJniCache.frameStateClass != nullptr) {
        return JNI_TRUE;
    }

    std::lock_guard<std::mutex> lock(jniCacheMutex);
    if (nativeFrameStateJniCache.frameStateClass != nullptr) {
        return JNI_TRUE;
    }

    jclass localClass = env->GetObjectClass(frameState);
    if (localClass == nullptr) {
        return JNI_FALSE;
    }

    jclass globalClass = (jclass)env->NewGlobalRef(localClass);
    env->DeleteLocalRef(localClass);
    if (globalClass == nullptr) {
        return JNI_FALSE;
    }

    jfieldID windowInfoField = env->GetFieldID(globalClass, "windowInfo", "J");
    jfieldID eventCountField = env->GetFieldID(globalClass, "eventCount", "I");
    jfieldID recordsField = env->GetFieldID(globalClass, "records", "[J");
    jfieldID textsField = env->GetFieldID(globalClass, "texts", "[Ljava/lang/String;");
    if (windowInfoField == nullptr || eventCountField == nullptr || recordsField == nullptr || textsField == nullptr) {
        env->DeleteGlobalRef(globalClass);
        return JNI_FALSE;
    }

    nativeFrameStateJniCache.frameStateClass = globalClass;
    nativeFrameStateJniCache.windowInfoField = windowInfoField;
    nativeFrameStateJniCache.eventCountField = eventCountField;
    nativeFrameStateJniCache.recordsField = recordsField;
    nativeFrameStateJniCache.textsField = textsField;

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostWindowDrag(
    JNIEnv* env, jobject thiz, jlong runtimeId) {
    nativeHostWindowDrag((int64_t)runtimeId);
}

extern "C" JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostWindowMinimize(
    JNIEnv* env, jobject thiz, jlong runtimeId) {
    nativeHostWindowMinimize((int64_t)runtimeId);
}

extern "C" JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostWindowMaximize(
    JNIEnv* env, jobject thiz, jlong runtimeId) {
    nativeHostWindowMaximize((int64_t)runtimeId);
}

extern "C" JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostWindowClose(
    JNIEnv* env, jobject thiz, jlong runtimeId) {
    nativeHostWindowClose((int64_t)runtimeId);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostBridgeAvailable(
    JNIEnv *env,
    jobject self
) {
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostGetWindowInfo(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return (jlong)nativeHostGetWindowInfo((int64_t)runtimeId);
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostIsRunning(
    JNIEnv *env,
    jobject self
) {
    return nativeHostIsRunning() != 0;
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostUsesSharedLibraryRuntime(
    JNIEnv *env,
    jobject self
) {
    return nativeHostUsesSharedLibraryRuntime() != 0;
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostWaitForShutdown(
    JNIEnv *env,
    jobject self
) {
    nativeHostWaitForShutdown();
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostIsWindowAttached(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return nativeHostIsWindowAttached((int64_t)runtimeId) != 0;
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostWaitForWindowAttached(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return nativeHostWaitForWindowAttached((int64_t)runtimeId) != 0;
}

JNIEXPORT jlong JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostD3DAdapterPtr(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return (jlong)nativeHostD3DAdapterPtr((int64_t)runtimeId);
}

JNIEXPORT jlong JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostD3DDevicePtr(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return (jlong)nativeHostD3DDevicePtr((int64_t)runtimeId);
}

JNIEXPORT jlong JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostD3DQueuePtr(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return (jlong)nativeHostD3DQueuePtr((int64_t)runtimeId);
}

JNIEXPORT jlong JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostAcquireDrawableTexturePtr(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return (jlong)nativeHostAcquireDrawableTexturePtr((int64_t)runtimeId);
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostPresentDrawable(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    nativeHostPresentDrawable((int64_t)runtimeId);
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostRequestRenderTick(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    nativeHostRequestRenderTick((int64_t)runtimeId);
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostSetPointerIcon(
    JNIEnv *env,
    jobject self,
    jlong runtimeId,
    jint cursorType
) {
    nativeHostSetPointerIcon((int64_t)runtimeId, (int32_t)cursorType);
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostEmitAppEvent(
    JNIEnv *env,
    jobject self,
    jlong runtimeId,
    jstring name,
    jstring payload
) {
    if (name == nullptr) {
        return;
    }

    const char *nameChars = env->GetStringUTFChars(name, nullptr);
    const char *payloadChars = nullptr;
    if (payload != nullptr) {
        payloadChars = env->GetStringUTFChars(payload, nullptr);
    }

    nativeHostEmitAppEvent((int64_t)runtimeId, nameChars, payloadChars);

    if (payloadChars != nullptr) {
        env->ReleaseStringUTFChars(payload, payloadChars);
    }
    env->ReleaseStringUTFChars(name, nameChars);
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostLogPhaseTiming(
    JNIEnv *env,
    jobject self,
    jstring name
) {
    if (name == nullptr) {
        return;
    }

    const char *nameChars = env->GetStringUTFChars(name, nullptr);
    nativeHostLogPhaseTiming(nameChars);
    env->ReleaseStringUTFChars(name, nameChars);
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostEmitProfileFrameSample(
    JNIEnv *env,
    jobject self,
    jlong runtimeId,
    jint refreshRate,
    jboolean rendered,
    jint dispatchDelayMicros,
    jint inputDrainMicros,
    jint acquireDrawableMicros,
    jint sceneRenderMicros,
    jint submitMicros
) {
    nativeHostEmitProfileFrameSample(
        (int64_t)runtimeId,
        refreshRate,
        rendered ? 1 : 0,
        dispatchDelayMicros,
        inputDrainMicros,
        acquireDrawableMicros,
        sceneRenderMicros,
        submitMicros
    );
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostProfileRenderingEnabled(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return nativeHostProfileRenderingEnabled((int64_t)runtimeId) != 0;
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostUpdateTextInputGeometry(
    JNIEnv *env,
    jobject self,
    jlong runtimeId,
    jfloat focusedRectLeft,
    jfloat focusedRectTop,
    jfloat focusedRectRight,
    jfloat focusedRectBottom,
    jint selectionStart,
    jint selectionEnd,
    jint compositionStart,
    jint compositionEnd
) {
    nativeHostUpdateTextInputGeometry(
        (int64_t)runtimeId,
        focusedRectLeft,
        focusedRectTop,
        focusedRectRight,
        focusedRectBottom,
        selectionStart,
        selectionEnd,
        compositionStart,
        compositionEnd
    );
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostClearTextInputGeometry(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    nativeHostClearTextInputGeometry((int64_t)runtimeId);
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostPollFrameState(
    JNIEnv *env,
    jobject self,
    jlong runtimeId,
    jint maxCount,
    jobject frameState
) {
    if (maxCount <= 0 || frameState == nullptr) {
        return JNI_FALSE;
    }

    if (!ensureNativeFrameStateJniCache(env, frameState)) {
        return JNI_FALSE;
    }

    const NativeFrameStateJniCache *cache = &nativeFrameStateJniCache;
    const jint recordStride = 10;
    jsize capacity = maxCount;
    jlongArray recordsArray = (jlongArray)env->GetObjectField(frameState, cache->recordsField);
    jobjectArray texts = (jobjectArray)env->GetObjectField(frameState, cache->textsField);
    if (recordsArray == nullptr || texts == nullptr) {
        return JNI_FALSE;
    }
    if (env->GetArrayLength(recordsArray) < capacity * recordStride ||
        env->GetArrayLength(texts) < capacity) {
        return JNI_FALSE;
    }
    jlong *records = env->GetLongArrayElements(recordsArray, nullptr);
    if (records == nullptr) {
        return JNI_FALSE;
    }

    char **textChars = (char **)calloc((size_t)capacity, sizeof(char *));
    if (textChars == nullptr) {
        env->ReleaseLongArrayElements(recordsArray, records, JNI_ABORT);
        return JNI_FALSE;
    }

    jlong packedWindowInfo = 0L;
    jint count = nativeHostPollFrameStateData(
        (int64_t)runtimeId,
        capacity,
        (int64_t *)&packedWindowInfo,
        (int64_t *)records,
        textChars
    );
    if (count < 0) {
        free(textChars);
        env->ReleaseLongArrayElements(recordsArray, records, JNI_ABORT);
        return JNI_FALSE;
    }

    jint previousCount = env->GetIntField(frameState, cache->eventCountField);
    jint clearCount = previousCount > count ? previousCount : count;
    for (jint index = 0; index < clearCount; index++) {
        env->SetObjectArrayElement(texts, index, nullptr);
    }

    for (jint index = 0; index < count; index++) {
        if (textChars[index] != nullptr) {
            jstring javaText = env->NewStringUTF(textChars[index]);
            free(textChars[index]);
            if (javaText != nullptr) {
                env->SetObjectArrayElement(texts, index, javaText);
                env->DeleteLocalRef(javaText);
            }
        }
    }
    free(textChars);

    env->ReleaseLongArrayElements(recordsArray, records, 0);
    env->SetLongField(frameState, cache->windowInfoField, packedWindowInfo);
    env->SetIntField(frameState, cache->eventCountField, count);
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_letmutex_compose_nativehost_internal_WindowsComposeBridgeBindings_nativeHostGetThemeButtonPixels(
    JNIEnv *env,
    jobject self,
    jint partId,
    jint stateId,
    jint width,
    jint height
) {
    if (width <= 0 || height <= 0) {
        return nullptr;
    }
    // Guard against integer overflow in `width * height`.
    if ((UINT32)width > 0x7FFFFFFF || (UINT32)height > 0x7FFFFFFF ||
        (UINT64)((UINT32)width) * ((UINT32)height) > 0x7FFFFFFF) {
        return nullptr;
    }

    HTHEME hTheme = OpenThemeData(NULL, L"WINDOW");
    if (!hTheme) return nullptr;

    // Use BufferedPaint for correct alpha channel handling on all Windows versions.
    // Without this, DrawThemeBackground may produce zero-alpha pixels on Win10+.
    HRESULT hrBP = BufferedPaintInit();

    HDC hdcScreen = GetDC(NULL);
    if (!hdcScreen) {
        CloseThemeData(hTheme);
        if (SUCCEEDED(hrBP)) {
            BufferedPaintUnInit();
        }
        return nullptr;
    }
    HDC hdcMem = CreateCompatibleDC(hdcScreen);
    if (!hdcMem) {
        ReleaseDC(NULL, hdcScreen);
        CloseThemeData(hTheme);
        if (SUCCEEDED(hrBP)) {
            BufferedPaintUnInit();
        }
        return nullptr;
    }

    BITMAPINFO bmi = {0};
    bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth = width;
    bmi.bmiHeader.biHeight = -height; // top-down
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 32;
    bmi.bmiHeader.biCompression = BI_RGB;

    void* pixels = nullptr;
    HBITMAP hBitmap = CreateDIBSection(hdcMem, &bmi, DIB_RGB_COLORS, &pixels, NULL, 0);
    if (!hBitmap || !pixels) {
        if (hBitmap) {
            DeleteObject(hBitmap);
        }
        DeleteDC(hdcMem);
        ReleaseDC(NULL, hdcScreen);
        CloseThemeData(hTheme);
        if (SUCCEEDED(hrBP)) {
            BufferedPaintUnInit();
        }
        return nullptr;
    }
    HBITMAP hOldBitmap = (HBITMAP)SelectObject(hdcMem, hBitmap);

    RECT rect = {0, 0, width, height};

    if (SUCCEEDED(hrBP)) {
        BP_PAINTPARAMS params = { sizeof(BP_PAINTPARAMS) };
        params.dwFlags = BPPF_ERASE; // Erase the buffer to zero before painting
        HDC hdcBuffer = NULL;
        HPAINTBUFFER hPaintBuffer = BeginBufferedPaint(hdcMem, &rect, BPBF_DIB, &params, &hdcBuffer);
        if (hPaintBuffer) {
            DrawThemeBackground(hTheme, hdcBuffer, partId, stateId, &rect, NULL);
            // Force alpha to opaque for all painted pixels
            BufferedPaintSetAlpha(hPaintBuffer, &rect, 255);
            EndBufferedPaint(hPaintBuffer, TRUE);
        } else {
            // Fallback: draw directly
            DrawThemeBackground(hTheme, hdcMem, partId, stateId, &rect, NULL);
            int pixelCount = width * height;
            for (int i = 0; i < pixelCount; i++) {
                ((int*)pixels)[i] |= 0xFF000000;
            }
        }
    } else {
        // Fallback: draw directly and force alpha
        DrawThemeBackground(hTheme, hdcMem, partId, stateId, &rect, NULL);
        int pixelCount = width * height;
        for (int i = 0; i < pixelCount; i++) {
            ((int*)pixels)[i] |= 0xFF000000;
        }
    }

    int pixelCount = width * height;
    jintArray result = env->NewIntArray(pixelCount);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, pixelCount, (const jint*)pixels);
    }

    SelectObject(hdcMem, hOldBitmap);
    DeleteObject(hBitmap);
    DeleteDC(hdcMem);
    ReleaseDC(NULL, hdcScreen);
    CloseThemeData(hTheme);

    if (SUCCEEDED(hrBP)) {
        BufferedPaintUnInit();
    }

    return result;
}

}
