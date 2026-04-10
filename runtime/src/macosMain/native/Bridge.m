#import <AppKit/AppKit.h>
#import <jni.h>
#import <stdlib.h>
#import <stdint.h>
#import "NativeHostExports.h"

typedef struct {
    jclass frameStateClass;
    jfieldID windowInfoField;
    jfieldID eventCountField;
    jfieldID recordsField;
    jfieldID textsField;
} NativeFrameStateJniCache;

static NativeFrameStateJniCache nativeFrameStateJniCache = {0};

static jboolean ensureNativeFrameStateJniCache(
    JNIEnv *env,
    jobject frameState
) {
    if (nativeFrameStateJniCache.frameStateClass != NULL) {
        return JNI_TRUE;
    }

    @synchronized([NSValue class]) {
        if (nativeFrameStateJniCache.frameStateClass != NULL) {
            return JNI_TRUE;
        }

        jclass localClass = (*env)->GetObjectClass(env, frameState);
        if (localClass == NULL) {
            return JNI_FALSE;
        }

        jclass globalClass = (jclass)(*env)->NewGlobalRef(env, localClass);
        (*env)->DeleteLocalRef(env, localClass);
        if (globalClass == NULL) {
            return JNI_FALSE;
        }

        jfieldID windowInfoField = (*env)->GetFieldID(env, globalClass, "windowInfo", "J");
        jfieldID eventCountField = (*env)->GetFieldID(env, globalClass, "eventCount", "I");
        jfieldID recordsField = (*env)->GetFieldID(env, globalClass, "records", "[J");
        jfieldID textsField = (*env)->GetFieldID(env, globalClass, "texts", "[Ljava/lang/String;");
        if (windowInfoField == NULL || eventCountField == NULL || recordsField == NULL || textsField == NULL) {
            (*env)->DeleteGlobalRef(env, globalClass);
            return JNI_FALSE;
        }

        nativeFrameStateJniCache.frameStateClass = globalClass;
        nativeFrameStateJniCache.windowInfoField = windowInfoField;
        nativeFrameStateJniCache.eventCountField = eventCountField;
        nativeFrameStateJniCache.recordsField = recordsField;
        nativeFrameStateJniCache.textsField = textsField;
    }

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostBridgeAvailable(
    JNIEnv *env,
    jobject self
) {
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostGetWindowInfo(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return (jlong)nativeHostGetWindowInfo((int64_t)runtimeId);
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostIsRunning(
    JNIEnv *env,
    jobject self
) {
    return nativeHostIsRunning() != 0;
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostUsesSharedLibraryRuntime(
    JNIEnv *env,
    jobject self
) {
    return nativeHostUsesSharedLibraryRuntime() != 0;
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostWaitForShutdown(
    JNIEnv *env,
    jobject self
) {
    nativeHostWaitForShutdown();
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostIsWindowAttached(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return nativeHostIsWindowAttached((int64_t)runtimeId) != 0;
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostWaitForWindowAttached(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return nativeHostWaitForWindowAttached((int64_t)runtimeId) != 0;
}

JNIEXPORT jlong JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostMetalDevicePtr(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return (jlong)nativeHostMetalDevicePtr((int64_t)runtimeId);
}

JNIEXPORT jlong JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostMetalQueuePtr(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return (jlong)nativeHostMetalQueuePtr((int64_t)runtimeId);
}

JNIEXPORT jlong JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostAcquireDrawableTexturePtr(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return (jlong)nativeHostAcquireDrawableTexturePtr((int64_t)runtimeId);
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostPresentDrawable(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    nativeHostPresentDrawable((int64_t)runtimeId);
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostRequestRenderTick(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    nativeHostRequestRenderTick((int64_t)runtimeId);
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostEmitAppEvent(
    JNIEnv *env,
    jobject self,
    jlong runtimeId,
    jstring name,
    jstring payload
) {
    if (name == NULL) {
        return;
    }

    const char *nameChars = (*env)->GetStringUTFChars(env, name, NULL);
    const char *payloadChars = NULL;
    if (payload != NULL) {
        payloadChars = (*env)->GetStringUTFChars(env, payload, NULL);
    }

    nativeHostEmitAppEvent((int64_t)runtimeId, nameChars, payloadChars);

    if (payloadChars != NULL) {
        (*env)->ReleaseStringUTFChars(env, payload, payloadChars);
    }
    (*env)->ReleaseStringUTFChars(env, name, nameChars);
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostLogPhaseTiming(
    JNIEnv *env,
    jobject self,
    jstring name
) {
    if (name == NULL) {
        return;
    }

    const char *nameChars = (*env)->GetStringUTFChars(env, name, NULL);
    nativeHostLogPhaseTiming(nameChars);
    (*env)->ReleaseStringUTFChars(env, name, nameChars);
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostEmitProfileFrameSample(
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
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostProfileRenderingEnabled(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    return nativeHostProfileRenderingEnabled((int64_t)runtimeId) != 0;
}

JNIEXPORT void JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostUpdateTextInputGeometry(
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
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostClearTextInputGeometry(
    JNIEnv *env,
    jobject self,
    jlong runtimeId
) {
    nativeHostClearTextInputGeometry((int64_t)runtimeId);
}

JNIEXPORT jboolean JNICALL
Java_letmutex_compose_nativehost_internal_MacOsComposeBridgeBindings_nativeHostPollFrameState(
    JNIEnv *env,
    jobject self,
    jlong runtimeId,
    jint maxCount,
    jobject frameState
) {
    if (maxCount <= 0 || frameState == NULL) {
        return JNI_FALSE;
    }

    if (!ensureNativeFrameStateJniCache(env, frameState)) {
        return JNI_FALSE;
    }

    const NativeFrameStateJniCache *cache = &nativeFrameStateJniCache;
    const jint recordStride = 10;
    jsize capacity = maxCount;
    jlongArray recordsArray = (jlongArray)(*env)->GetObjectField(env, frameState, cache->recordsField);
    jobjectArray texts = (jobjectArray)(*env)->GetObjectField(env, frameState, cache->textsField);
    if (recordsArray == NULL || texts == NULL) {
        return JNI_FALSE;
    }
    if ((*env)->GetArrayLength(env, recordsArray) < capacity * recordStride ||
        (*env)->GetArrayLength(env, texts) < capacity) {
        return JNI_FALSE;
    }
    jlong *records = (*env)->GetLongArrayElements(env, recordsArray, NULL);
    if (records == NULL) {
        return JNI_FALSE;
    }

    char **textChars = calloc((size_t)capacity, sizeof(char *));
    if (textChars == NULL) {
        (*env)->ReleaseLongArrayElements(env, recordsArray, records, JNI_ABORT);
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
        (*env)->ReleaseLongArrayElements(env, recordsArray, records, JNI_ABORT);
        return JNI_FALSE;
    }

    jint previousCount = (*env)->GetIntField(env, frameState, cache->eventCountField);
    jint clearCount = previousCount > count ? previousCount : count;
    for (jint index = 0; index < clearCount; index++) {
        (*env)->SetObjectArrayElement(env, texts, index, NULL);
    }

    for (jint index = 0; index < count; index++) {
        if (textChars[index] != NULL) {
            jstring javaText = (*env)->NewStringUTF(env, textChars[index]);
            free(textChars[index]);
            if (javaText != NULL) {
                (*env)->SetObjectArrayElement(env, texts, index, javaText);
                (*env)->DeleteLocalRef(env, javaText);
            }
        }
    }
    free(textChars);

    (*env)->ReleaseLongArrayElements(env, recordsArray, records, 0);
    (*env)->SetLongField(env, frameState, cache->windowInfoField, packedWindowInfo);
    (*env)->SetIntField(env, frameState, cache->eventCountField, count);
    return JNI_TRUE;
}
