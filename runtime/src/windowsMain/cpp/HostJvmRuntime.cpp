#include "HostJvm.h"
#include <iostream>
#include <algorithm>

bool HostJvm::PrepareRuntime(int64_t runtimeId, const std::string& mainClassName, bool profileEnabled) {
    auto state = GetRuntime(runtimeId);
    if (!state) return false;

    bool prepared = false;
    WithAttachedEnv([&](JNIEnv* env) {
        {
            std::lock_guard<std::mutex> lock(lock_);
            if (runtimeClass_ == nullptr) {
                jclass runtimeClassLocal = env->FindClass("letmutex/compose/nativehost/ComposeRuntime");
                if (!runtimeClassLocal) {
                    std::cerr << "Could not find letmutex.compose.nativehost.ComposeRuntime class." << std::endl;
                    return;
                }

                runtimeClass_ = (jclass)env->NewGlobalRef(runtimeClassLocal);
                env->DeleteLocalRef(runtimeClassLocal);

                initializeMethod_ = env->GetStaticMethodID(runtimeClass_, "initialize", "()V");
                enterCurrentRuntimeMethod_ = env->GetStaticMethodID(runtimeClass_, "enterCurrentRuntime", "(Lletmutex/compose/nativehost/ComposeRuntime;)V");
                exitCurrentRuntimeMethod_ = env->GetStaticMethodID(runtimeClass_, "exitCurrentRuntime", "()V");
                constructorMethod_ = env->GetMethodID(runtimeClass_, "<init>", "(JZ)V");
                isContentBoundMethod_ = env->GetMethodID(runtimeClass_, "isContentBound", "()Z");
                startRuntimeMethod_ = env->GetMethodID(runtimeClass_, "startRuntime", "()V");
                requestFrameMethod_ = env->GetMethodID(runtimeClass_, "requestFrame", "(J)V");
                closeRuntimeMethod_ = env->GetMethodID(runtimeClass_, "closeRuntime", "()V");

                handleExternalDragEnteredMethod_ = env->GetMethodID(runtimeClass_, "handleExternalDragEntered", "(IIIIJ[Ljava/lang/String;Ljava/lang/String;[BLjava/lang/String;)Z");
                handleExternalDragMovedMethod_ = env->GetMethodID(runtimeClass_, "handleExternalDragMoved", "(IIIIJ[Ljava/lang/String;Ljava/lang/String;[BLjava/lang/String;)Z");
                handleExternalDragExitedMethod_ = env->GetMethodID(runtimeClass_, "handleExternalDragExited", "()V");
                handleExternalDragEndedMethod_ = env->GetMethodID(runtimeClass_, "handleExternalDragEnded", "()V");
                handleExternalDropMethod_ = env->GetMethodID(runtimeClass_, "handleExternalDrop", "(IIIIJ[Ljava/lang/String;Ljava/lang/String;[BLjava/lang/String;)Z");

                if (!initializeMethod_ || !enterCurrentRuntimeMethod_ || !exitCurrentRuntimeMethod_ ||
                    !constructorMethod_ || !isContentBoundMethod_ || !startRuntimeMethod_ ||
                    !requestFrameMethod_ || !closeRuntimeMethod_ ||
                    !handleExternalDragEnteredMethod_ || !handleExternalDragMovedMethod_ ||
                    !handleExternalDragExitedMethod_ || !handleExternalDragEndedMethod_ ||
                    !handleExternalDropMethod_) {
                    std::cerr << "Failed to resolve ComposeRuntime JNI methods." << std::endl;
                    env->DeleteGlobalRef(runtimeClass_);
                    runtimeClass_ = nullptr;
                    return;
                }
            }
        }

        env->CallStaticVoidMethod(runtimeClass_, initializeMethod_);

        jobject localRuntime = env->NewObject(runtimeClass_, constructorMethod_, (jlong)runtimeId, (jboolean)profileEnabled);
        if (!localRuntime) {
            std::cerr << "Failed to construct ComposeRuntime instance." << std::endl;
            return;
        }

        state->jvmRuntimeRef = env->NewGlobalRef(localRuntime);
        env->DeleteLocalRef(localRuntime);

        std::string mainClassPath = mainClassName;
        std::replace(mainClassPath.begin(), mainClassPath.end(), '.', '/');

        jclass appMainClass = env->FindClass(mainClassPath.c_str());
        if (!appMainClass) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            std::string fallback = mainClassPath + "Kt";
            appMainClass = env->FindClass(fallback.c_str());
            if (!appMainClass) {
                std::cerr << "Failed to find app main class: " << mainClassName << std::endl;
                return;
            }
        }

        jmethodID mainMethod = env->GetStaticMethodID(appMainClass, "main", "([Ljava/lang/String;)V");
        if (!mainMethod) {
            mainMethod = env->GetStaticMethodID(appMainClass, "main", "()V");
        }

        if (!mainMethod) {
            std::cerr << "Failed to find main method in: " << mainClassName << std::endl;
            return;
        }

        env->CallStaticVoidMethod(runtimeClass_, enterCurrentRuntimeMethod_, state->jvmRuntimeRef);

        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray args = env->NewObjectArray(0, stringClass, nullptr);
        env->CallStaticVoidMethod(appMainClass, mainMethod, args);

        if (env->ExceptionCheck()) {
            std::cerr << "Exception in app main execution" << std::endl;
            env->ExceptionDescribe();
            env->ExceptionClear();
        }

        env->CallStaticVoidMethod(runtimeClass_, exitCurrentRuntimeMethod_);

        prepared = true;
    });

    return prepared;
}

#include <dwmapi.h>
#pragma comment(lib, "dwmapi.lib")

void HostJvm::RunRenderLoop(int64_t runtimeId, std::atomic<bool>* isHostRunning) {
    auto state = GetRuntime(runtimeId);
    if (!state || !state->jvmRuntimeRef) return;

    WithAttachedEnv([&](JNIEnv* env) {
        env->CallVoidMethod(state->jvmRuntimeRef, startRuntimeMethod_);

        LARGE_INTEGER qpf;
        QueryPerformanceFrequency(&qpf);
        double frequency = (double)qpf.QuadPart;

        while (isHostRunning->load() && state->jvmRuntimeRef) {
            {
                std::unique_lock<std::mutex> lock(state->lock);
                state->cv.wait(lock, [&] { return state->requestRenderTick || !isHostRunning->load() || !state->jvmRuntimeRef; });
                state->requestRenderTick = false;
            }

            if (!isHostRunning->load() || !state->jvmRuntimeRef) {
                break;
            }

            // This tick is not guaranteed to produce a Present: Compose may find no
            // invalidations after processing input. Do not replace this with a DXGI frame-
            // latency waitable object here; its auto-reset signal can be consumed by such a
            // no-op tick and then never re-signaled. See D3D12Renderer::Initialize.
            DwmFlush(); // Block natively until the Desktop Window Manager VBlank

            if (!isHostRunning->load() || !state->jvmRuntimeRef) {
                break;
            }

            LARGE_INTEGER qpc;
            QueryPerformanceCounter(&qpc);
            int64_t vsyncNanos = (int64_t)((double)qpc.QuadPart / frequency * 1000000000.0);

            env->CallVoidMethod(state->jvmRuntimeRef, requestFrameMethod_, vsyncNanos);
        }

        if (state->jvmRuntimeRef) {
            env->CallVoidMethod(state->jvmRuntimeRef, closeRuntimeMethod_);
            env->DeleteGlobalRef(state->jvmRuntimeRef);
            state->jvmRuntimeRef = nullptr;
        }
    });
}
