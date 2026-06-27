import Darwin
import Foundation

final class ComposeJvmHost {
    private let lifecycleListener: ComposeLifecycleListener
    private let onJvmCreated: (UnsafeMutableRawPointer) -> Void
    private let isHostRunning: () -> Bool
    private let runtimeHandlesLock = NSLock()

    private var runtimeHandles: ComposeRuntimeHandles?

    private struct ComposeRuntimeHandles {
        let runtimeClass: UnsafeMutableRawPointer
        let initializeMethod: UnsafeMutableRawPointer
        let enterCurrentRuntimeMethod: UnsafeMutableRawPointer
        let exitCurrentRuntimeMethod: UnsafeMutableRawPointer
        let constructorMethod: UnsafeMutableRawPointer
        let isContentBoundMethod: UnsafeMutableRawPointer
        let startRuntimeMethod: UnsafeMutableRawPointer
        let requestFrameMethod: UnsafeMutableRawPointer
        let handleExternalDragEnteredMethod: UnsafeMutableRawPointer
        let handleExternalDragMovedMethod: UnsafeMutableRawPointer
        let handleExternalDragExitedMethod: UnsafeMutableRawPointer
        let handleExternalDragEndedMethod: UnsafeMutableRawPointer
        let handleExternalDropMethod: UnsafeMutableRawPointer
        let closeRuntimeMethod: UnsafeMutableRawPointer
    }

    private enum HostClassResolutionPath: String {
        case findClass = "FindClass"
        case systemClassLoader = "ClassLoader.loadClass"
    }

    init(
        lifecycleListener: ComposeLifecycleListener,
        onJvmCreated: @escaping (UnsafeMutableRawPointer) -> Void,
        isHostRunning: @escaping () -> Bool
    ) {
        self.lifecycleListener = lifecycleListener
        self.onJvmCreated = onJvmCreated
        self.isHostRunning = isHostRunning
    }

    func bootstrapJvm() {
        logPhaseTiming("bootstrapJvm() Enter")

        guard let javaHome = resolveJavaHome() else {
            lifecycleListener.jvmConfigurationFailed(stage: "resolveJavaHome")
            fputs("Unable to resolve JAVA_HOME\n", stderr)
            return
        }
        let bundledJvmConfig = loadBundledJvmConfig()
        guard let classpath = resolveClasspath(bundledJvmConfig: bundledJvmConfig) else {
            lifecycleListener.jvmConfigurationFailed(stage: "resolveClasspath")
            fputs("Unable to resolve application classpath\n", stderr)
            return
        }
        guard let bridgePath = resolveBridgePath() else {
            lifecycleListener.jvmConfigurationFailed(stage: "resolveBridgePath")
            fputs("Unable to resolve native host bridge path\n", stderr)
            return
        }
        lifecycleListener.jvmConfigurationResolved(
            javaHome: javaHome,
            classpathEntryCount: classpath.split(separator: ":").count,
            javaOptionCount: resolvedNativeHostJvmArgs().count + bundledJvmConfig.javaOptions.count,
            bridgePath: bridgePath
        )

        logPhaseTiming("bootstrapJvm() Done Path/Configs Resolving")

        let dylibPath = "\(javaHome)/lib/server/libjvm.dylib"
        guard let handle = dlopen(dylibPath, RTLD_NOW) else {
            lifecycleListener.jvmLibraryLoadFailed(path: dylibPath)
            fputs("Failed to load libjvm.dylib\n", stderr)
            return
        }

        guard let createVMPoint = dlsym(handle, "JNI_CreateJavaVM") else {
            lifecycleListener.jvmEntryPointResolutionFailed(symbol: "JNI_CreateJavaVM")
            fputs("Failed to find JNI_CreateJavaVM\n", stderr)
            return
        }

        let createVM = unsafeBitCast(createVMPoint, to: JNI_CreateJavaVM_Type.self)
        let optionStrings: [String] =
            resolvedNativeHostJvmArgs() +
            bundledJvmConfig.javaOptions +
            [
                "-Djava.class.path=\(classpath)",
                "-D\(bridgeJvmProperty)=\(bridgePath)",
                "-XstartOnFirstThread"
            ]
        let options = optionStrings.map { option in
            JavaVMOption(optionString: strdup(option), extraInfo: nil)
        }

        var args = JavaVMInitArgs()
        args.version = 0x00010008
        args.nOptions = jint(options.count)
        args.options = UnsafeMutablePointer<JavaVMOption>.allocate(capacity: options.count)
        args.ignoreUnrecognized = 1
        defer {
            for option in options {
                free(option.optionString)
            }
            args.options?.deallocate()
        }
        for index in 0..<options.count {
            args.options![index] = options[index]
        }

        var jvm: UnsafeMutableRawPointer?
        var envRaw: UnsafeMutableRawPointer?

        logPhaseTiming("bootstrapJvm() Calling createVM()")

        withUnsafeMutablePointer(to: &args) { argsPtr in
            let result = createVM(&jvm, &envRaw, UnsafeMutableRawPointer(argsPtr))
            if result == 0, let jvmRaw = jvm {
                DispatchQueue.global(qos: .userInitiated).async {
                    self.prewarmComposeRuntime(jvmRaw: jvmRaw)
                }
                lifecycleListener.jvmCreated()
                onJvmCreated(jvmRaw)
            } else {
                lifecycleListener.jvmCreateFailed(result: result)
                fputs("Failed to create JVM: \(result)\n", stderr)
            }

            logPhaseTiming("bootstrapJvm() Done createVM()")
        }
    }

    func runRenderLoop(
        jvmRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState,
        waitForRenderSignal: @escaping () -> Void,
        nextRenderVsyncNanos: @escaping () -> UInt64,
        hasDisplaySync: @escaping () -> Bool,
        isRuntimeRunning: @escaping () -> Bool
    ) {
        withAttachedEnv(jvmRaw: jvmRaw) { envRaw in
            self.runRenderLoopWithEnv(
                envRaw: envRaw,
                runtimeState: runtimeState,
                waitForRenderSignal: waitForRenderSignal,
                nextRenderVsyncNanos: nextRenderVsyncNanos,
                hasDisplaySync: hasDisplaySync,
                isRuntimeRunning: isRuntimeRunning
            )
        }
    }

    func prepareRuntime(
        jvmRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState
    ) -> Bool {
        var prepared = false
        withAttachedEnv(jvmRaw: jvmRaw) { envRaw in
            while true {
                switch runtimeState.claimRuntimePreparation() {
                case .ready:
                    prepared = runtimeState.currentJvmRuntimeRef() != nil
                    return
                case .failed:
                    prepared = false
                    return
                case .wait:
                    let waitPrepared = runtimeState.waitForPreparedRuntime()
                    if waitPrepared {
                        prepared = runtimeState.currentJvmRuntimeRef() != nil
                        return
                    }
                    if runtimeState.isReleased() {
                        prepared = false
                        return
                    }
                case .perform:
                    var didPrepare = false
                    var shouldFinishPreparation = true
                    defer {
                        if shouldFinishPreparation {
                            runtimeState.finishRuntimePreparation(success: didPrepare)
                        }
                    }

                    guard let runtimeHandles = self.resolveComposeRuntimeHandles(envRaw: envRaw),
                          let runtimeRef = self.ensureComposeRuntimeRef(
                        envRaw: envRaw,
                        runtimeState: runtimeState,
                        runtimeHandles: runtimeHandles
                          ),
                          self.bindRuntimeContentWithEnv(
                            envRaw: envRaw,
                            runtimeState: runtimeState,
                            runtimeHandles: runtimeHandles,
                            runtimeRef: runtimeRef
                          ),
                          !runtimeState.isReleased() else {
                        shouldFinishPreparation = false
                        self.disposeRuntimeWithEnv(
                            envRaw: envRaw,
                            runtimeState: runtimeState,
                            runtimeHandles: self.resolveComposeRuntimeHandles(envRaw: envRaw)
                        )
                        prepared = false
                        return
                    }
                    logPhaseTiming("prepareRuntime() Done")

                    self.lifecycleListener.renderRuntimeInitialized()
                    didPrepare = true
                    prepared = true
                    return
                }
            }
        }
        return prepared
    }

    func disposeRuntime(
        jvmRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState
    ) {
        withAttachedEnv(jvmRaw: jvmRaw) { envRaw in
            self.disposeRuntimeWithEnv(
                envRaw: envRaw,
                runtimeState: runtimeState
            )
        }
    }

    private func resolveJavaHome() -> String? {
        let environment = ProcessInfo.processInfo.environment
        if let value = environment["JAVA_HOME"], !value.isEmpty {
            return value
        }
        let bundledJavaHome = Bundle.main.bundleURL
            .appendingPathComponent("Contents/runtime/Contents/Home")
            .path
        if FileManager.default.fileExists(atPath: bundledJavaHome) {
            return bundledJavaHome
        }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/libexec/java_home")
        let output = Pipe()
        process.standardOutput = output
        process.standardError = Pipe()
        do {
            try process.run()
            process.waitUntilExit()
            guard process.terminationStatus == 0 else {
                return nil
            }
            let data = output.fileHandleForReading.readDataToEndOfFile()
            let value = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            return value.isEmpty ? nil : value
        } catch {
            return nil
        }
    }

    private func resolveClasspath(bundledJvmConfig: BundledJvmConfig) -> String? {
        let environment = ProcessInfo.processInfo.environment
        if let value = environment["APP_CLASSPATH"], !value.isEmpty {
            return value
        }
        if let classpath = bundledJvmConfig.classpath, !classpath.isEmpty {
            return classpath
        }
        let candidateDirectories: [URL] = {
            var directories: [URL] = []
            if let resourceURL = Bundle.main.resourceURL {
                directories.append(resourceURL.appendingPathComponent("app/lib", isDirectory: true))
            }
            directories.append(Bundle.main.bundleURL.appendingPathComponent("Contents/app", isDirectory: true))
            return directories
        }()
        for libURL in candidateDirectories {
            guard let jarURLs = try? FileManager.default.contentsOfDirectory(
                at: libURL,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            ) else {
                continue
            }
            let classpathEntries =
                jarURLs
                    .filter { $0.pathExtension == "jar" }
                    .sorted { $0.lastPathComponent < $1.lastPathComponent }
                    .map(\.path)
            if !classpathEntries.isEmpty {
                return classpathEntries.joined(separator: ":")
            }
        }
        return nil
    }

    private func loadBundledJvmConfig() -> BundledJvmConfig {
        guard let appDir = resolveBundledAppDirectory() else {
            return BundledJvmConfig(classpath: nil, javaOptions: [])
        }
        let processName = ProcessInfo.processInfo.processName
        let configFile = appDir.appendingPathComponent("\(processName).cfg")
        guard FileManager.default.fileExists(atPath: configFile.path) else {
            return BundledJvmConfig(classpath: nil, javaOptions: [])
        }
        guard let contents = try? String(contentsOf: configFile, encoding: .utf8) else {
            return BundledJvmConfig(classpath: nil, javaOptions: [])
        }

        let appDirPath = appDir.path
        var classpathEntries: [String] = []
        var javaOptions: [String] = []

        for rawLine in contents.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty || line.hasPrefix("[") {
                continue
            }
            if let value = line.removingPrefix("app.classpath=") {
                classpathEntries.append(value.replacingOccurrences(of: "$APPDIR", with: appDirPath))
            } else if let value = line.removingPrefix("java-options=") {
                javaOptions.append(value.replacingOccurrences(of: "$APPDIR", with: appDirPath))
            }
        }

        return BundledJvmConfig(
            classpath: classpathEntries.isEmpty ? nil : classpathEntries.joined(separator: ":"),
            javaOptions: javaOptions
        )
    }

    private func resolvedNativeHostJvmArgs() -> [String] {
        guard let appDir = resolveBundledAppDirectory() else {
            return ComposeNativeHostJvmArgs.values
        }
        let appDirPath = appDir.path
        return ComposeNativeHostJvmArgs.values.map { value in
            value.replacingOccurrences(of: "$APPDIR", with: appDirPath)
        }
    }

    private func resolveBundledAppDirectory() -> URL? {
        let candidatePaths: [URL?] = [
            Bundle.main.resourceURL?.deletingLastPathComponent().appendingPathComponent("app", isDirectory: true),
            Bundle.main.bundleURL.appendingPathComponent("Contents/app", isDirectory: true)
        ]
        return candidatePaths.first {
            guard let path = $0?.path else {
                return false
            }
            return FileManager.default.fileExists(atPath: path)
        } ?? nil
    }

    private func resolveBridgePath() -> String? {
        let environment = ProcessInfo.processInfo.environment
        if let value = environment["COMPOSE_NATIVE_HOST_BRIDGE_PATH"], !value.isEmpty {
            return value
        }
        let candidatePaths: [String?] = [
            Bundle.main.resourceURL?
                .appendingPathComponent("native/libcompose-native-host.dylib")
                .path,
            Bundle.main.bundleURL
                .appendingPathComponent("Contents/Resources/native/libcompose-native-host.dylib")
                .path,
        ]
        return candidatePaths.first {
            guard let path = $0 else {
                return false
            }
            return FileManager.default.fileExists(atPath: path)
        } ?? nil
    }

    private func withAttachedEnv(
        jvmRaw: UnsafeMutableRawPointer,
        block: (UnsafeMutableRawPointer) -> Void
    ) {
        let attachCurrentThreadIndex = 4
        let detachCurrentThreadIndex = 5

        typealias AttachCurrentThread = @convention(c) (
            UnsafeMutableRawPointer,
            UnsafeMutablePointer<UnsafeMutableRawPointer?>?,
            UnsafeMutableRawPointer?
        ) -> jint
        typealias DetachCurrentThread = @convention(c) (UnsafeMutableRawPointer) -> jint

        let invokePtr = jvmRaw.assumingMemoryBound(to: UnsafePointer<UnsafeRawPointer?>.self).pointee
        let attach = unsafeBitCast(invokePtr[attachCurrentThreadIndex], to: AttachCurrentThread.self)
        let detach = unsafeBitCast(invokePtr[detachCurrentThreadIndex], to: DetachCurrentThread.self)

        var envRaw: UnsafeMutableRawPointer?
        let attachResult = attach(jvmRaw, &envRaw, nil)
        if attachResult != 0 || envRaw == nil {
            fputs("AttachCurrentThread failed: \(attachResult)\n", stderr)
            return
        }
        defer { _ = detach(jvmRaw) }

        block(envRaw!)
    }

    private func prewarmComposeRuntime(jvmRaw: UnsafeMutableRawPointer) {
        withAttachedEnv(jvmRaw: jvmRaw) { envRaw in
            let envPtr = envRaw.assumingMemoryBound(to: UnsafePointer<UnsafeRawPointer?>.self).pointee
            let callStaticVoidMethodIndex = 143
            let callStaticVoidMethodA = unsafeBitCast(envPtr[callStaticVoidMethodIndex], to: JniCallStaticVoidMethodA.self)

            guard let runtimeHandles = resolveComposeRuntimeHandles(envRaw: envRaw) else {
                return
            }

            callStaticVoidMethodA(envRaw, runtimeHandles.runtimeClass, runtimeHandles.initializeMethod, nil)
        }
    }

    func handleExternalDragEntered(
        jvmRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        invokeExternalDropMethod(
            jvmRaw: jvmRaw,
            runtimeState: runtimeState,
            snapshot: snapshot,
            methodSelector: \.handleExternalDragEnteredMethod
        )
    }

    func handleExternalDragMoved(
        jvmRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        invokeExternalDropMethod(
            jvmRaw: jvmRaw,
            runtimeState: runtimeState,
            snapshot: snapshot,
            methodSelector: \.handleExternalDragMovedMethod
        )
    }

    func handleExternalDragExited(
        jvmRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState
    ) {
        withAttachedEnv(jvmRaw: jvmRaw) { envRaw in
            let envPtr = envRaw.assumingMemoryBound(to: UnsafePointer<UnsafeRawPointer?>.self).pointee
            let callVoidMethodIndex = 63
            let callVoidMethodA = unsafeBitCast(envPtr[callVoidMethodIndex], to: JniCallVoidMethodA.self)

            guard let runtimeHandles = resolveComposeRuntimeHandles(envRaw: envRaw),
                  let runtimeRef = runtimeState.currentJvmRuntimeRef() else {
                return
            }

            callVoidMethodA(envRaw, runtimeRef, runtimeHandles.handleExternalDragExitedMethod, nil)
        }
    }

    func handleExternalDragEnded(
        jvmRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState
    ) {
        withAttachedEnv(jvmRaw: jvmRaw) { envRaw in
            let envPtr = envRaw.assumingMemoryBound(to: UnsafePointer<UnsafeRawPointer?>.self).pointee
            let callVoidMethodIndex = 63
            let callVoidMethodA = unsafeBitCast(envPtr[callVoidMethodIndex], to: JniCallVoidMethodA.self)

            guard let runtimeHandles = resolveComposeRuntimeHandles(envRaw: envRaw),
                  let runtimeRef = runtimeState.currentJvmRuntimeRef() else {
                return
            }

            callVoidMethodA(envRaw, runtimeRef, runtimeHandles.handleExternalDragEndedMethod, nil)
        }
    }

    func handleExternalDrop(
        jvmRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        invokeExternalDropMethod(
            jvmRaw: jvmRaw,
            runtimeState: runtimeState,
            snapshot: snapshot,
            methodSelector: \.handleExternalDropMethod
        )
    }

    private func bindRuntimeContentWithEnv(
        envRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState,
        runtimeHandles: ComposeRuntimeHandles,
        runtimeRef: UnsafeMutableRawPointer
    ) -> Bool {
        let mainClassName = runtimeState.kotlinMainClass
        lifecycleListener.mainInvocationStarted(
            mainClassName: mainClassName
        )
        let envPtr = envRaw.assumingMemoryBound(to: UnsafePointer<UnsafeRawPointer?>.self).pointee
        let findClassIndex = 6
        let exceptionOccurredIndex = 15
        let exceptionClearIndex = 17
        let deleteLocalRefIndex = 23
        let getMethodIdIndex = 33
        let getStaticMethodIdIndex = 113
        let callObjectMethodIndex = 36
        let callBooleanMethodIndex = 39
        let callStaticObjectMethodIndex = 116
        let newObjectArrayIndex = 172
        let callStaticVoidMethodIndex = 143
        let newStringUTFIndex = 167

        let findClass = unsafeBitCast(envPtr[findClassIndex], to: JniFindClass.self)
        let exceptionOccurred = unsafeBitCast(envPtr[exceptionOccurredIndex], to: JniExceptionOccurred.self)
        let exceptionClear = unsafeBitCast(envPtr[exceptionClearIndex], to: JniExceptionClear.self)
        let deleteLocalRef = unsafeBitCast(envPtr[deleteLocalRefIndex], to: JniDeleteLocalRef.self)
        let getMethodID = unsafeBitCast(envPtr[getMethodIdIndex], to: JniGetMethodID.self)
        let getStaticMethodID = unsafeBitCast(envPtr[getStaticMethodIdIndex], to: JniGetStaticMethodID.self)
        let callObjectMethodA = unsafeBitCast(envPtr[callObjectMethodIndex], to: JniCallObjectMethodA.self)
        let callBooleanMethodA = unsafeBitCast(envPtr[callBooleanMethodIndex], to: JniCallBooleanMethodA.self)
        let callStaticObjectMethodA = unsafeBitCast(envPtr[callStaticObjectMethodIndex], to: JniCallStaticObjectMethodA.self)
        let newObjectArray = unsafeBitCast(envPtr[newObjectArrayIndex], to: JniNewObjectArray.self)
        let callStaticVoidMethodA = unsafeBitCast(envPtr[callStaticVoidMethodIndex], to: JniCallStaticVoidMethodA.self)
        let newStringUTF = unsafeBitCast(envPtr[newStringUTFIndex], to: JniNewStringUTF.self)

        guard let mainClassResolution = resolveHostClass(
            envRaw: envRaw,
            findClass: findClass,
            getMethodID: getMethodID,
            getStaticMethodID: getStaticMethodID,
            callObjectMethodA: callObjectMethodA,
            callStaticObjectMethodA: callStaticObjectMethodA,
            newStringUTF: newStringUTF,
            exceptionOccurred: exceptionOccurred,
            exceptionClear: exceptionClear,
            deleteLocalRef: deleteLocalRef,
            className: mainClassName
        ),
        let stringClass = findClass(envRaw, "java/lang/String"),
        let mainMethod = getStaticMethodID(envRaw, mainClassResolution.classRef, "main", "([Ljava/lang/String;)V") else {
            lifecycleListener.mainInvocationFailed(
                mainClassName: mainClassName
            )
            return false
        }
        var currentRuntimeArgs = jvalue(l: runtimeRef)
        callStaticVoidMethodA(
            envRaw,
            runtimeHandles.runtimeClass,
            runtimeHandles.enterCurrentRuntimeMethod,
            &currentRuntimeArgs
        )
        let emptyArgs = newObjectArray(envRaw, 0, stringClass, nil)
        var mainArgs = jvalue(l: emptyArgs)
        callStaticVoidMethodA(
            envRaw,
            mainClassResolution.classRef,
            mainMethod,
            &mainArgs
        )
        callStaticVoidMethodA(envRaw, runtimeHandles.runtimeClass, runtimeHandles.exitCurrentRuntimeMethod, nil)
        let isContentBound = callBooleanMethodA(
            envRaw,
            runtimeRef,
            runtimeHandles.isContentBoundMethod,
            nil
        ) != 0
        guard isContentBound else {
            lifecycleListener.mainInvocationFailed(mainClassName: mainClassName)
            return false
        }
        lifecycleListener.mainInvocationFinished(
            mainClassName: mainClassName
        )
        return true
    }

    private func runRenderLoopWithEnv(
        envRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState,
        waitForRenderSignal: () -> Void,
        nextRenderVsyncNanos: () -> UInt64,
        hasDisplaySync: () -> Bool,
        isRuntimeRunning: () -> Bool
    ) {
        lifecycleListener.renderLoopStarted()
        let envPtr = envRaw.assumingMemoryBound(to: UnsafePointer<UnsafeRawPointer?>.self).pointee
        let callVoidMethodIndex = 63

        let callVoidMethodA = unsafeBitCast(envPtr[callVoidMethodIndex], to: JniCallVoidMethodA.self)

        guard let runtimeHandles = resolveComposeRuntimeHandles(envRaw: envRaw) else {
            lifecycleListener.renderLoopInitializationFailed()
            return
        }
        guard runtimeState.waitForPreparedRuntime(),
              let runtimeRef = runtimeState.currentJvmRuntimeRef() else {
            lifecycleListener.renderLoopInitializationFailed()
            return
        }

        callVoidMethodA(envRaw, runtimeRef, runtimeHandles.startRuntimeMethod, nil)

        while isHostRunning() && isRuntimeRunning() {
            if hasDisplaySync() {
                waitForRenderSignal()
            } else {
                sleepNanos(idleWaitNanos)
            }
            guard isHostRunning() && isRuntimeRunning() else {
                break
            }
            let vsyncNanos = nextRenderVsyncNanos()
            var renderArgs = jvalue(j: Int64(bitPattern: vsyncNanos))
            callVoidMethodA(
                envRaw,
                runtimeRef,
                runtimeHandles.requestFrameMethod,
                &renderArgs,
            )
        }

        disposeRuntimeWithEnv(
            envRaw: envRaw,
            runtimeState: runtimeState,
            runtimeHandles: runtimeHandles
        )
    }

    private func resolveComposeRuntimeHandles(envRaw: UnsafeMutableRawPointer) -> ComposeRuntimeHandles? {
        runtimeHandlesLock.lock()
        defer { runtimeHandlesLock.unlock() }

        if let runtimeHandles {
            return runtimeHandles
        }

        let envPtr = envRaw.assumingMemoryBound(to: UnsafePointer<UnsafeRawPointer?>.self).pointee
        let findClassIndex = 6
        let exceptionOccurredIndex = 15
        let exceptionClearIndex = 17
        let newGlobalRefIndex = 21
        let deleteGlobalRefIndex = 22
        let deleteLocalRefIndex = 23
        let getMethodIdIndex = 33
        let getStaticMethodIdIndex = 113
        let callObjectMethodIndex = 36
        let callStaticObjectMethodIndex = 116
        let newStringUTFIndex = 167

        let findClass = unsafeBitCast(envPtr[findClassIndex], to: JniFindClass.self)
        let exceptionOccurred = unsafeBitCast(envPtr[exceptionOccurredIndex], to: JniExceptionOccurred.self)
        let exceptionClear = unsafeBitCast(envPtr[exceptionClearIndex], to: JniExceptionClear.self)
        let newGlobalRef = unsafeBitCast(envPtr[newGlobalRefIndex], to: JniNewGlobalRef.self)
        let deleteGlobalRef = unsafeBitCast(envPtr[deleteGlobalRefIndex], to: JniDeleteGlobalRef.self)
        let deleteLocalRef = unsafeBitCast(envPtr[deleteLocalRefIndex], to: JniDeleteLocalRef.self)
        let getMethodID = unsafeBitCast(envPtr[getMethodIdIndex], to: JniGetMethodID.self)
        let getStaticMethodID = unsafeBitCast(envPtr[getStaticMethodIdIndex], to: JniGetStaticMethodID.self)
        let callObjectMethodA = unsafeBitCast(envPtr[callObjectMethodIndex], to: JniCallObjectMethodA.self)
        let callStaticObjectMethodA = unsafeBitCast(envPtr[callStaticObjectMethodIndex], to: JniCallStaticObjectMethodA.self)
        let newStringUTF = unsafeBitCast(envPtr[newStringUTFIndex], to: JniNewStringUTF.self)

        let classLookupStart = DispatchTime.now().uptimeNanoseconds / 1_000_000
        guard let classResolution = resolveHostClass(
            envRaw: envRaw,
            findClass: findClass,
            getMethodID: getMethodID,
            getStaticMethodID: getStaticMethodID,
            callObjectMethodA: callObjectMethodA,
            callStaticObjectMethodA: callStaticObjectMethodA,
            newStringUTF: newStringUTF,
            exceptionOccurred: exceptionOccurred,
            exceptionClear: exceptionClear,
            deleteLocalRef: deleteLocalRef,
            className: runtimeClassName
        ) else {
            return nil
        }
        let classLookupEnd = DispatchTime.now().uptimeNanoseconds / 1_000_000
        logPhaseTiming("ComposeRuntime class lookup via \(classResolution.path.rawValue) done \(classLookupEnd - classLookupStart)ms")

        let runtimeClassLocal = classResolution.classRef
        defer {
            deleteLocalRef(envRaw, runtimeClassLocal)
        }

        guard let runtimeClass = newGlobalRef(envRaw, runtimeClassLocal) else {
            return nil
        }

        let methodLookupStart = DispatchTime.now().uptimeNanoseconds / 1_000_000
        guard let initializeMethod = getStaticMethodID(envRaw, runtimeClass, "initialize", "()V"),
              let enterCurrentRuntimeMethod = getStaticMethodID(
                envRaw,
                runtimeClass,
                "enterCurrentRuntime",
                "(Lletmutex/compose/nativehost/ComposeRuntime;)V"
              ),
              let exitCurrentRuntimeMethod = getStaticMethodID(
                envRaw,
                runtimeClass,
                "exitCurrentRuntime",
                "()V"
              ),
              let constructorMethod = getMethodID(envRaw, runtimeClass, "<init>", "(JZ)V"),
              let isContentBoundMethod = getMethodID(envRaw, runtimeClass, "isContentBound", "()Z"),
              let startRuntimeMethod = getMethodID(envRaw, runtimeClass, "startRuntime", "()V"),
              let requestFrameMethod = getMethodID(envRaw, runtimeClass, "requestFrame", "(J)V"),
              let handleExternalDragEnteredMethod = getMethodID(
                envRaw,
                runtimeClass,
                "handleExternalDragEntered",
                "(IIIIJ[Ljava/lang/String;Ljava/lang/String;[BLjava/lang/String;)Z"
              ),
              let handleExternalDragMovedMethod = getMethodID(
                envRaw,
                runtimeClass,
                "handleExternalDragMoved",
                "(IIIIJ[Ljava/lang/String;Ljava/lang/String;[BLjava/lang/String;)Z"
              ),
              let handleExternalDragExitedMethod = getMethodID(
                envRaw,
                runtimeClass,
                "handleExternalDragExited",
                "()V"
              ),
              let handleExternalDragEndedMethod = getMethodID(
                envRaw,
                runtimeClass,
                "handleExternalDragEnded",
                "()V"
              ),
              let handleExternalDropMethod = getMethodID(
                envRaw,
                runtimeClass,
                "handleExternalDrop",
                "(IIIIJ[Ljava/lang/String;Ljava/lang/String;[BLjava/lang/String;)Z"
              ),
              let closeRuntimeMethod = getMethodID(envRaw, runtimeClass, "closeRuntime", "()V") else {
            deleteGlobalRef(envRaw, runtimeClass)
            return nil
        }
        let methodLookupEnd = DispatchTime.now().uptimeNanoseconds / 1_000_000
        logPhaseTiming("ComposeRuntime method lookup done \(methodLookupEnd - methodLookupStart)ms")

        let resolvedRuntimeHandles = ComposeRuntimeHandles(
            runtimeClass: runtimeClass,
            initializeMethod: initializeMethod,
            enterCurrentRuntimeMethod: enterCurrentRuntimeMethod,
            exitCurrentRuntimeMethod: exitCurrentRuntimeMethod,
            constructorMethod: constructorMethod,
            isContentBoundMethod: isContentBoundMethod,
            startRuntimeMethod: startRuntimeMethod,
            requestFrameMethod: requestFrameMethod,
            handleExternalDragEnteredMethod: handleExternalDragEnteredMethod,
            handleExternalDragMovedMethod: handleExternalDragMovedMethod,
            handleExternalDragExitedMethod: handleExternalDragExitedMethod,
            handleExternalDragEndedMethod: handleExternalDragEndedMethod,
            handleExternalDropMethod: handleExternalDropMethod,
            closeRuntimeMethod: closeRuntimeMethod
        )
        runtimeHandles = resolvedRuntimeHandles

        logPhaseTiming("ComposeRuntime handles cached")

        return resolvedRuntimeHandles
    }

    private func ensureComposeRuntimeRef(
        envRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState,
        runtimeHandles: ComposeRuntimeHandles
    ) -> UnsafeMutableRawPointer? {
        if let existingRuntimeRef = runtimeState.currentJvmRuntimeRef() {
            return existingRuntimeRef
        }

        let envPtr = envRaw.assumingMemoryBound(to: UnsafePointer<UnsafeRawPointer?>.self).pointee
        let newGlobalRefIndex = 21
        let deleteGlobalRefIndex = 22
        let deleteLocalRefIndex = 23
        let newObjectIndex = 30
        let callStaticVoidMethodIndex = 143

        let newGlobalRef = unsafeBitCast(envPtr[newGlobalRefIndex], to: JniNewGlobalRef.self)
        let deleteGlobalRef = unsafeBitCast(envPtr[deleteGlobalRefIndex], to: JniDeleteGlobalRef.self)
        let deleteLocalRef = unsafeBitCast(envPtr[deleteLocalRefIndex], to: JniDeleteLocalRef.self)
        let newObjectA = unsafeBitCast(envPtr[newObjectIndex], to: JniNewObjectA.self)
        let callStaticVoidMethodA = unsafeBitCast(envPtr[callStaticVoidMethodIndex], to: JniCallStaticVoidMethodA.self)

        callStaticVoidMethodA(envRaw, runtimeHandles.runtimeClass, runtimeHandles.initializeMethod, nil)
        let constructorArgs = [
            jvalue(j: runtimeState.runtimeId),
            jvalue(z: runtimeState.profileRenderingEnabled),
        ]
        guard let runtimeLocalRef = constructorArgs.withUnsafeBufferPointer({ buffer in
            newObjectA(
                envRaw,
                runtimeHandles.runtimeClass,
                runtimeHandles.constructorMethod,
                UnsafeRawPointer(buffer.baseAddress)
            )
        }) else {
            return nil
        }
        defer {
            deleteLocalRef(envRaw, runtimeLocalRef)
        }

        guard let runtimeGlobalRef = newGlobalRef(envRaw, runtimeLocalRef) else {
            return nil
        }
        guard let installedRuntimeRef = runtimeState.installJvmRuntimeRef(runtimeGlobalRef) else {
            deleteGlobalRef(envRaw, runtimeGlobalRef)
            return nil
        }
        if installedRuntimeRef != runtimeGlobalRef {
            deleteGlobalRef(envRaw, runtimeGlobalRef)
        }
        return installedRuntimeRef
    }

    private func disposeRuntimeWithEnv(
        envRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState,
        runtimeHandles: ComposeRuntimeHandles? = nil
    ) {
        guard let runtimeRef = runtimeState.clearJvmRuntimeRef() else {
            runtimeState.resetRuntimePreparation()
            return
        }

        let envPtr = envRaw.assumingMemoryBound(to: UnsafePointer<UnsafeRawPointer?>.self).pointee
        let callVoidMethodIndex = 63
        let deleteGlobalRefIndex = 22
        let callVoidMethodA = unsafeBitCast(envPtr[callVoidMethodIndex], to: JniCallVoidMethodA.self)
        let deleteGlobalRef = unsafeBitCast(envPtr[deleteGlobalRefIndex], to: JniDeleteGlobalRef.self)

        if let runtimeHandles = runtimeHandles ?? resolveComposeRuntimeHandles(envRaw: envRaw) {
            callVoidMethodA(envRaw, runtimeRef, runtimeHandles.closeRuntimeMethod, nil)
        }
        deleteGlobalRef(envRaw, runtimeRef)
        runtimeState.resetRuntimePreparation()
    }

    private func resolveHostClass(
        envRaw: UnsafeMutableRawPointer,
        findClass: JniFindClass,
        getMethodID: JniGetMethodID,
        getStaticMethodID: JniGetStaticMethodID,
        callObjectMethodA: JniCallObjectMethodA,
        callStaticObjectMethodA: JniCallStaticObjectMethodA,
        newStringUTF: JniNewStringUTF,
        exceptionOccurred: JniExceptionOccurred,
        exceptionClear: JniExceptionClear,
        deleteLocalRef: JniDeleteLocalRef,
        className: String
    ) -> (classRef: UnsafeMutableRawPointer, path: HostClassResolutionPath)? {
        if let fastClass = findClass(envRaw, jniClassName(className)) {
            return (fastClass, .findClass)
        }
        if let pendingException = exceptionOccurred(envRaw) {
            exceptionClear(envRaw)
            deleteLocalRef(envRaw, pendingException)
        }
        guard let fallbackClass = loadSystemClass(
            envRaw: envRaw,
            findClass: findClass,
            getMethodID: getMethodID,
            getStaticMethodID: getStaticMethodID,
            callObjectMethodA: callObjectMethodA,
            callStaticObjectMethodA: callStaticObjectMethodA,
            newStringUTF: newStringUTF,
            className: className
        ) else {
            return nil
        }
        return (fallbackClass, .systemClassLoader)
    }

    private func invokeExternalDropMethod(
        jvmRaw: UnsafeMutableRawPointer,
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot,
        methodSelector: KeyPath<ComposeRuntimeHandles, UnsafeMutableRawPointer>
    ) -> Bool {
        var accepted = false
        withAttachedEnv(jvmRaw: jvmRaw) { envRaw in
            let envPtr = envRaw.assumingMemoryBound(to: UnsafePointer<UnsafeRawPointer?>.self).pointee
            let findClassIndex = 6
            let deleteLocalRefIndex = 23
            let callBooleanMethodIndex = 39
            let newStringUTFIndex = 167
            let newObjectArrayIndex = 172
            let setObjectArrayElementIndex = 174
            let newByteArrayIndex = 176
            let setByteArrayRegionIndex = 208

            let findClass = unsafeBitCast(envPtr[findClassIndex], to: JniFindClass.self)
            let deleteLocalRef = unsafeBitCast(envPtr[deleteLocalRefIndex], to: JniDeleteLocalRef.self)
            let callBooleanMethodA = unsafeBitCast(envPtr[callBooleanMethodIndex], to: JniCallBooleanMethodA.self)
            let newStringUTF = unsafeBitCast(envPtr[newStringUTFIndex], to: JniNewStringUTF.self)
            let newObjectArray = unsafeBitCast(envPtr[newObjectArrayIndex], to: JniNewObjectArray.self)
            let setObjectArrayElement = unsafeBitCast(envPtr[setObjectArrayElementIndex], to: JniSetObjectArrayElement.self)
            let newByteArray = unsafeBitCast(envPtr[newByteArrayIndex], to: JniNewByteArray.self)
            let setByteArrayRegion = unsafeBitCast(envPtr[setByteArrayRegionIndex], to: JniSetByteArrayRegion.self)

            guard let runtimeHandles = resolveComposeRuntimeHandles(envRaw: envRaw),
                  let runtimeRef = runtimeState.currentJvmRuntimeRef(),
                  let stringClass = findClass(envRaw, "java/lang/String"),
                  let fileArray = newObjectArray(envRaw, Int32(snapshot.files.count), stringClass, nil) else {
                accepted = false
                return
            }
            defer {
                deleteLocalRef(envRaw, stringClass)
                deleteLocalRef(envRaw, fileArray)
            }

            for (index, file) in snapshot.files.enumerated() {
                guard let fileValue = newStringUTF(envRaw, file) else {
                    continue
                }
                setObjectArrayElement(envRaw, fileArray, Int32(index), fileValue)
                deleteLocalRef(envRaw, fileValue)
            }

            let textValue = snapshot.text.flatMap { newStringUTF(envRaw, $0) }
            let imageFormatValue = snapshot.imageFormat.flatMap { newStringUTF(envRaw, $0) }
            let imageBytesValue: UnsafeMutableRawPointer? = snapshot.imageBytes.flatMap { data in
                guard let byteArray = newByteArray(envRaw, Int32(data.count)) else {
                    return nil
                }
                data.withUnsafeBytes { buffer in
                    let bytePointer = buffer.bindMemory(to: jbyte.self).baseAddress
                    setByteArrayRegion(envRaw, byteArray, 0, Int32(data.count), bytePointer)
                }
                return byteArray
            }
            defer {
                deleteLocalRef(envRaw, textValue)
                deleteLocalRef(envRaw, imageFormatValue)
                deleteLocalRef(envRaw, imageBytesValue)
            }

            let args = [
                jvalue(i: snapshot.x),
                jvalue(i: snapshot.y),
                jvalue(i: snapshot.action),
                jvalue(i: snapshot.payloadKind),
                jvalue(j: snapshot.timestampMillis),
                jvalue(l: fileArray),
                jvalue(l: textValue),
                jvalue(l: imageBytesValue),
                jvalue(l: imageFormatValue),
            ]
            accepted = args.withUnsafeBufferPointer { buffer in
                callBooleanMethodA(
                    envRaw,
                    runtimeRef,
                    runtimeHandles[keyPath: methodSelector],
                    UnsafeRawPointer(buffer.baseAddress)
                ) != 0
            }
        }
        return accepted
    }
}
