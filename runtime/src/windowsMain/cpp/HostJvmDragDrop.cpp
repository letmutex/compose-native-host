#include "HostJvm.h"
#include <iostream>

static std::string WideToUtf8(const std::wstring& wstr) {
    if (wstr.empty()) return "";
    int size_needed = WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), NULL, 0, NULL, NULL);
    std::string strUtf8(size_needed, 0);
    WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), &strUtf8[0], size_needed, NULL, NULL);
    return strUtf8;
}

static bool InvokeGraalDragDrop(
    int64_t runtimeId,
    composeNativeHostRuntimeHandleExternalDrop_t fn,
    int x,
    int y,
    int action,
    const DragDropPayload& payload,
    int64_t timestampMillis
) {
    auto state = HostJvm::Get().GetRuntime(runtimeId);
    if (!state || !state->graalRuntimeHandle || !fn) return false;

    auto attachment = GetOrAttachThread();
    if (!attachment.thread) return false;

    std::vector<std::string> utf8Files;
    for (const auto& wfile : payload.files) {
        utf8Files.push_back(WideToUtf8(wfile));
    }

    std::vector<const char*> filePtrs;
    for (const auto& s : utf8Files) {
        filePtrs.push_back(s.c_str());
    }
    const char** filesArr = filePtrs.empty() ? nullptr : filePtrs.data();

    std::string utf8Text = WideToUtf8(payload.text);
    const char* textPtr = payload.payloadKind == 2 ? utf8Text.c_str() : nullptr;

    int32_t res = fn(
        attachment.thread,
        state->graalRuntimeHandle,
        x,
        y,
        action,
        payload.payloadKind,
        timestampMillis,
        filesArr,
        (int32_t)filePtrs.size(),
        textPtr,
        nullptr, // imageBytes
        0,       // imageBytesCount
        nullptr  // imageFormat
    );

    DetachThread(attachment);
    return res != 0;
}

bool HostJvm::DragEnter(int64_t runtimeId, int x, int y, int action, const DragDropPayload& payload, int64_t timestampMillis) {
    if (g_useSharedLibraryRuntime) {
        return InvokeGraalDragDrop(runtimeId, fn_composeNativeHostRuntimeHandleExternalDragEntered, x, y, action, payload, timestampMillis);
    }
    return InvokeDragDropMethod(runtimeId, handleExternalDragEnteredMethod_, x, y, action, payload, timestampMillis);
}

bool HostJvm::DragOver(int64_t runtimeId, int x, int y, int action, const DragDropPayload& payload, int64_t timestampMillis) {
    if (g_useSharedLibraryRuntime) {
        return InvokeGraalDragDrop(runtimeId, fn_composeNativeHostRuntimeHandleExternalDragMoved, x, y, action, payload, timestampMillis);
    }
    return InvokeDragDropMethod(runtimeId, handleExternalDragMovedMethod_, x, y, action, payload, timestampMillis);
}

void HostJvm::DragLeave(int64_t runtimeId) {
    if (g_useSharedLibraryRuntime) {
        auto state = GetRuntime(runtimeId);
        if (!state || !state->graalRuntimeHandle || !fn_composeNativeHostRuntimeHandleExternalDragExited) return;
        auto attachment = GetOrAttachThread();
        if (attachment.thread) {
            fn_composeNativeHostRuntimeHandleExternalDragExited(attachment.thread, state->graalRuntimeHandle);
            DetachThread(attachment);
        }
        return;
    }
    auto state = GetRuntime(runtimeId);
    if (!state || !state->jvmRuntimeRef || !handleExternalDragExitedMethod_) return;
    WithAttachedEnv([&](JNIEnv* env) {
        env->CallVoidMethod(state->jvmRuntimeRef, handleExternalDragExitedMethod_);
    });
}

void HostJvm::DragEnded(int64_t runtimeId) {
    if (g_useSharedLibraryRuntime) {
        auto state = GetRuntime(runtimeId);
        if (!state || !state->graalRuntimeHandle || !fn_composeNativeHostRuntimeHandleExternalDragEnded) return;
        auto attachment = GetOrAttachThread();
        if (attachment.thread) {
            fn_composeNativeHostRuntimeHandleExternalDragEnded(attachment.thread, state->graalRuntimeHandle);
            DetachThread(attachment);
        }
        return;
    }
    auto state = GetRuntime(runtimeId);
    if (!state || !state->jvmRuntimeRef || !handleExternalDragEndedMethod_) return;
    WithAttachedEnv([&](JNIEnv* env) {
        env->CallVoidMethod(state->jvmRuntimeRef, handleExternalDragEndedMethod_);
    });
}

bool HostJvm::Drop(int64_t runtimeId, int x, int y, int action, const DragDropPayload& payload, int64_t timestampMillis) {
    if (g_useSharedLibraryRuntime) {
        return InvokeGraalDragDrop(runtimeId, fn_composeNativeHostRuntimeHandleExternalDrop, x, y, action, payload, timestampMillis);
    }
    return InvokeDragDropMethod(runtimeId, handleExternalDropMethod_, x, y, action, payload, timestampMillis);
}

bool HostJvm::InvokeDragDropMethod(
    int64_t runtimeId,
    jmethodID methodId,
    int x,
    int y,
    int action,
    const DragDropPayload& payload,
    int64_t timestampMillis
) {
    auto state = GetRuntime(runtimeId);
    if (!state || !state->jvmRuntimeRef || !methodId) return false;

    bool accepted = false;
    WithAttachedEnv([&](JNIEnv* env) {
        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray fileArray = env->NewObjectArray((jsize)payload.files.size(), stringClass, nullptr);
        for (size_t i = 0; i < payload.files.size(); ++i) {
            jstring strVal = env->NewString((const jchar*)payload.files[i].c_str(), (jsize)payload.files[i].size());
            env->SetObjectArrayElement(fileArray, (jsize)i, strVal);
            env->DeleteLocalRef(strVal);
        }

        jstring textVal = nullptr;
        if (payload.payloadKind == 2) {
            textVal = env->NewString((const jchar*)payload.text.c_str(), (jsize)payload.text.size());
        }

        jboolean res = env->CallBooleanMethod(
            state->jvmRuntimeRef,
            methodId,
            (jint)x,
            (jint)y,
            (jint)action,
            (jint)payload.payloadKind,
            (jlong)timestampMillis,
            fileArray,
            textVal,
            nullptr, // imageBytes
            nullptr  // imageFormat
        );

        accepted = (res == JNI_TRUE);

        env->DeleteLocalRef(fileArray);
        if (textVal) env->DeleteLocalRef(textVal);
        env->DeleteLocalRef(stringClass);
    });

    return accepted;
}
