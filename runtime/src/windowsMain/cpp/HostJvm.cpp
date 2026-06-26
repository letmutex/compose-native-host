#include "HostJvm.h"
#include <windowsx.h>
#include <iostream>
#include <fstream>
#include <sstream>
#include <algorithm>
#include <thread>

static std::string WideToUtf8(const std::wstring& wstr) {
    if (wstr.empty()) return "";
    int size_needed = WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), NULL, 0, NULL, NULL);
    std::string strUtf8(size_needed, 0);
    WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), &strUtf8[0], size_needed, NULL, NULL);
    return strUtf8;
}

HostJvm& HostJvm::Get() {
    static HostJvm instance;
    return instance;
}

HostJvm::HostJvm() {}

HostJvm::~HostJvm() {
    Shutdown();
}

std::wstring HostJvm::ResolveJavaHome() {
    // Check environment variable JAVA_HOME
    wchar_t envBuf[32767];
    DWORD len = GetEnvironmentVariableW(L"JAVA_HOME", envBuf, 32767);
    if (len > 0 && len < 32767) {
        return std::wstring(envBuf, len);
    }

    // Check environment variable GRAALVM_HOME
    len = GetEnvironmentVariableW(L"GRAALVM_HOME", envBuf, 32767);
    if (len > 0 && len < 32767) {
        return std::wstring(envBuf, len);
    }

    // Default bundled JRE path (relative to executable directory)
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    std::wstring exeDir = exePath;
    size_t lastSlash = exeDir.find_last_of(L"\\/");
    if (lastSlash != std::wstring::npos) {
        exeDir = exeDir.substr(0, lastSlash);
    }

    std::wstring bundledJava = exeDir + L"\\runtime";
    DWORD attribs = GetFileAttributesW(bundledJava.c_str());
    if (attribs != INVALID_FILE_ATTRIBUTES && (attribs & FILE_ATTRIBUTE_DIRECTORY)) {
        return bundledJava;
    }

    return L"";
}

std::vector<std::string> HostJvm::LoadBundledJvmConfig(const std::wstring& appDir, std::string& classpathOut) {
    std::vector<std::string> javaOptions;
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    std::wstring exeName = exePath;
    size_t lastSlash = exeName.find_last_of(L"\\/");
    if (lastSlash != std::wstring::npos) {
        exeName = exeName.substr(lastSlash + 1);
    }
    size_t lastDot = exeName.find_last_of(L".");
    if (lastDot != std::wstring::npos) {
        exeName = exeName.substr(0, lastDot);
    }

    std::wstring cfgAppDir = appDir + L"\\app";
    std::wstring configFile = cfgAppDir + L"\\" + exeName + L".cfg";
    std::ifstream file(configFile);
    if (!file.is_open()) {
        cfgAppDir = appDir;
        configFile = cfgAppDir + L"\\" + exeName + L".cfg";
        file.open(configFile);
        if (!file.is_open()) {
            return javaOptions;
        }
    }

    std::string line;
    std::vector<std::string> classpathEntries;
    std::string appDirUtf8 = WideToUtf8(cfgAppDir);

    while (std::getline(file, line)) {
        // Strip carriage returns or spaces
        line.erase(std::remove(line.begin(), line.end(), '\r'), line.end());
        if (line.empty() || line[0] == '[') continue;

        size_t eq = line.find('=');
        if (eq == std::string::npos) continue;

        std::string key = line.substr(0, eq);
        std::string val = line.substr(eq + 1);

        // Replace $APPDIR with actual app directory
        size_t appdirPos;
        while ((appdirPos = val.find("$APPDIR")) != std::string::npos) {
            val.replace(appdirPos, 7, appDirUtf8);
        }

        if (key == "app.classpath") {
            classpathEntries.push_back(val);
        } else if (key == "java-options") {
            javaOptions.push_back(val);
        }
    }

    if (!classpathEntries.empty()) {
        std::string cp;
        for (size_t i = 0; i < classpathEntries.size(); ++i) {
            if (i > 0) cp += ";";
            cp += classpathEntries[i];
        }
        classpathOut = cp;
    }

    return javaOptions;
}

std::string HostJvm::ResolveClasspath() {
    char envBuf[32767];
    DWORD len = GetEnvironmentVariableA("APP_CLASSPATH", envBuf, 32767);
    if (len > 0 && len < 32767) {
        return std::string(envBuf, len);
    }

    // Scan for jars in app/lib or next to executable
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    std::wstring exeDir = exePath;
    size_t lastSlash = exeDir.find_last_of(L"\\/");
    if (lastSlash != std::wstring::npos) {
        exeDir = exeDir.substr(0, lastSlash);
    }

    std::wstring libDir = exeDir + L"\\app\\lib";
    DWORD attribs = GetFileAttributesW(libDir.c_str());
    if (attribs == INVALID_FILE_ATTRIBUTES || !(attribs & FILE_ATTRIBUTE_DIRECTORY)) {
        libDir = exeDir + L"\\app";
        attribs = GetFileAttributesW(libDir.c_str());
        if (attribs == INVALID_FILE_ATTRIBUTES || !(attribs & FILE_ATTRIBUTE_DIRECTORY)) {
            libDir = exeDir;
        }
    }

    std::string classpath;
    std::wstring searchPath = libDir + L"\\*.jar";
    WIN32_FIND_DATAW findData;
    HANDLE hFind = FindFirstFileW(searchPath.c_str(), &findData);
    if (hFind != INVALID_HANDLE_VALUE) {
        do {
            std::wstring jarPath = libDir + L"\\" + findData.cFileName;
            std::string jarPathUtf8 = WideToUtf8(jarPath);
            if (!classpath.empty()) classpath += ";";
            classpath += jarPathUtf8;
        } while (FindNextFileW(hFind, &findData));
        FindClose(hFind);
    }

    return classpath;
}

std::string HostJvm::ResolveBridgePath() {
    char envBuf[32767];
    DWORD len = GetEnvironmentVariableA("COMPOSE_NATIVE_HOST_BRIDGE_PATH", envBuf, 32767);
    if (len > 0 && len < 32767) {
        return std::string(envBuf, len);
    }

    // Default next to executable or under native folder
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    std::wstring exeDir = exePath;
    size_t lastSlash = exeDir.find_last_of(L"\\/");
    if (lastSlash != std::wstring::npos) {
        exeDir = exeDir.substr(0, lastSlash);
    }

    std::wstring dllPath = exeDir + L"\\native\\bridge.dll";
    DWORD attribs = GetFileAttributesW(dllPath.c_str());
    if (attribs != INVALID_FILE_ATTRIBUTES) {
        return WideToUtf8(dllPath);
    }

    dllPath = exeDir + L"\\bridge.dll";
    attribs = GetFileAttributesW(dllPath.c_str());
    if (attribs != INVALID_FILE_ATTRIBUTES) {
        return WideToUtf8(dllPath);
    }

    return "";
}

bool HostJvm::BootstrapJvm() {
    std::lock_guard<std::mutex> lock(lock_);
    if (jvm_) return true;

    std::wstring javaHome = ResolveJavaHome();
    if (javaHome.empty()) {
        std::cerr << "Unable to resolve JAVA_HOME." << std::endl;
        return false;
    }

    // Find jvm.dll
    std::wstring jvmDllPath = javaHome + L"\\bin\\server\\jvm.dll";
    if (GetFileAttributesW(jvmDllPath.c_str()) == INVALID_FILE_ATTRIBUTES) {
        jvmDllPath = javaHome + L"\\lib\\server\\jvm.dll";
        if (GetFileAttributesW(jvmDllPath.c_str()) == INVALID_FILE_ATTRIBUTES) {
            std::cerr << "Unable to find jvm.dll in JAVA_HOME." << std::endl;
            return false;
        }
    }

    jvmDll_ = LoadLibraryW(jvmDllPath.c_str());
    if (!jvmDll_) {
        std::cerr << "Failed to load jvm.dll." << std::endl;
        return false;
    }

    typedef jint(JNICALL *JNI_CreateJavaVM_Type)(JavaVM**, void**, void*);
    auto createVM = (JNI_CreateJavaVM_Type)GetProcAddress(jvmDll_, "JNI_CreateJavaVM");
    if (!createVM) {
        std::cerr << "Failed to find JNI_CreateJavaVM entry point." << std::endl;
        return false;
    }

    std::wstring appDir = javaHome; // Use javaHome or exe parent as a fallback appDir
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    std::wstring exeDir = exePath;
    size_t lastSlash = exeDir.find_last_of(L"\\/");
    if (lastSlash != std::wstring::npos) {
        appDir = exeDir.substr(0, lastSlash);
    }

    std::string classpath;
    std::vector<std::string> optionsList = LoadBundledJvmConfig(appDir, classpath);
    if (classpath.empty()) {
        classpath = ResolveClasspath();
    }
    std::string bridgePath = ResolveBridgePath();

    std::vector<std::string> jvmOptions;
    // Add default classpath and options
    jvmOptions.push_back("-Djava.class.path=" + classpath);
    if (!bridgePath.empty()) {
        jvmOptions.push_back("-Dcompose.native.host.bridge.path=" + bridgePath);
    }
    // Append options from config file
    jvmOptions.insert(jvmOptions.end(), optionsList.begin(), optionsList.end());

    std::vector<JavaVMOption> options(jvmOptions.size());
    for (size_t i = 0; i < jvmOptions.size(); ++i) {
        options[i].optionString = _strdup(jvmOptions[i].c_str());
        options[i].extraInfo = nullptr;
    }

    JavaVMInitArgs vmArgs = {};
    vmArgs.version = JNI_VERSION_1_8; // Use JVM 1.8 JNI version
    vmArgs.nOptions = (jint)options.size();
    vmArgs.options = options.data();
    vmArgs.ignoreUnrecognized = JNI_TRUE;

    JNIEnv* env = nullptr;
    jint res = createVM(&jvm_, (void**)&env, &vmArgs);

    for (size_t i = 0; i < options.size(); ++i) {
        free(options[i].optionString);
    }

    if (res != JNI_OK || !jvm_) {
        std::cerr << "Failed to create Java VM: " << res << std::endl;
        return false;
    }

    return true;
}

void HostJvm::RegisterRuntime(int64_t runtimeId, std::shared_ptr<RuntimeState> state) {
    std::lock_guard<std::mutex> lock(lock_);
    runtimes_[runtimeId] = state;
    if (state->hwnd) {
        hwndRuntimes_[state->hwnd] = state;
    }
}

void HostJvm::UnregisterRuntime(int64_t runtimeId) {
    std::lock_guard<std::mutex> lock(lock_);
    auto it = runtimes_.find(runtimeId);
    if (it != runtimes_.end()) {
        if (it->second->hwnd) {
            hwndRuntimes_.erase(it->second->hwnd);
        }
        runtimes_.erase(it);
    }
}

std::shared_ptr<RuntimeState> HostJvm::GetRuntime(int64_t runtimeId) {
    std::lock_guard<std::mutex> lock(lock_);
    auto it = runtimes_.find(runtimeId);
    if (it != runtimes_.end()) {
        return it->second;
    }
    return nullptr;
}

std::shared_ptr<RuntimeState> HostJvm::GetRuntimeByHwnd(HWND hwnd) {
    std::lock_guard<std::mutex> lock(lock_);
    auto it = hwndRuntimes_.find(hwnd);
    if (it != hwndRuntimes_.end()) {
        return it->second;
    }
    return nullptr;
}

void HostJvm::WithAttachedEnv(const std::function<void(JNIEnv*)>& block) {
    if (!jvm_) return;

    JNIEnv* env = nullptr;
    jint getEnvResult = jvm_->GetEnv((void**)&env, JNI_VERSION_1_8);
    if (getEnvResult == JNI_EDETACHED) {
        JavaVMAttachArgs attachArgs = {};
        attachArgs.version = JNI_VERSION_1_8;
        attachArgs.name = (char*)"ComposeHostThread";
        attachArgs.group = nullptr;
        if (jvm_->AttachCurrentThread((void**)&env, &attachArgs) == JNI_OK) {
            block(env);
            jvm_->DetachCurrentThread();
        }
    } else if (getEnvResult == JNI_OK) {
        block(env);
    }
}

void HostJvm::Shutdown() {
    std::lock_guard<std::mutex> lock(lock_);
    WithAttachedEnv([&](JNIEnv* env) {
        for (auto& pair : runtimes_) {
            if (pair.second->jvmRuntimeRef) {
                env->DeleteGlobalRef(pair.second->jvmRuntimeRef);
                pair.second->jvmRuntimeRef = nullptr;
            }
        }
    });

    if (jvm_) {
        jvm_->DestroyJavaVM();
        jvm_ = nullptr;
    }

    if (jvmDll_) {
        FreeLibrary(jvmDll_);
        jvmDll_ = nullptr;
    }
}


