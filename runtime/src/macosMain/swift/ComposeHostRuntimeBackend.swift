import Darwin
import Foundation

protocol ComposeHostRuntimeBackend: AnyObject {
    func startIfNeeded()
    func stop()
    func prepareRuntime(_ runtimeState: ComposeHostRuntimeState) -> Bool
    func releaseRuntime(_ runtimeState: ComposeHostRuntimeState)
    func runRenderLoop(
        runtimeState: ComposeHostRuntimeState,
        renderCoordinator: RenderCoordinator,
        isRuntimeRunning: @escaping () -> Bool
    )
    func isReady() -> Bool
    func handleExternalDragEntered(
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool
    func handleExternalDragMoved(
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool
    func handleExternalDragExited(runtimeState: ComposeHostRuntimeState)
    func handleExternalDragEnded(runtimeState: ComposeHostRuntimeState)
    func handleExternalDrop(
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool
}

enum ComposeRuntimeBackendIdentity: Equatable {
    case jvm
    case sharedLibrary(libraryName: String, libraryPath: String?)
}

private let sharedLibraryBindMainSymbol = "composeNativeHostRuntimeBindMain"
private let sharedLibraryHandleExternalDragEnteredSymbol = "composeNativeHostRuntimeHandleExternalDragEntered"
private let sharedLibraryHandleExternalDragMovedSymbol = "composeNativeHostRuntimeHandleExternalDragMoved"
private let sharedLibraryHandleExternalDragExitedSymbol = "composeNativeHostRuntimeHandleExternalDragExited"
private let sharedLibraryHandleExternalDragEndedSymbol = "composeNativeHostRuntimeHandleExternalDragEnded"
private let sharedLibraryHandleExternalDropSymbol = "composeNativeHostRuntimeHandleExternalDrop"

extension ComposeRuntimeStartup {
    var backendIdentity: ComposeRuntimeBackendIdentity {
        switch self {
        case .jvm:
            return .jvm
        case .sharedLibrary(let libraryName, let libraryPath):
            return .sharedLibrary(libraryName: libraryName, libraryPath: libraryPath)
        }
    }
}

final class ComposeJvmRuntimeBackend: ComposeHostRuntimeBackend {
    private let lifecycleListener: ComposeLifecycleListener
    private let isHostRunning: () -> Bool
    private let onReady: () -> Void
    private let lock = NSLock()
    private var didBootstrapJvm = false
    private var jvmHandle: UnsafeMutableRawPointer?

    private lazy var jvmHost = ComposeJvmHost(
        lifecycleListener: lifecycleListener,
        onJvmCreated: { [weak self] jvmRaw in
            self?.handleJvmCreated(jvmRaw)
        },
        isHostRunning: { [weak self] in
            self?.isHostRunning() == true
        }
    )

    init(
        lifecycleListener: ComposeLifecycleListener,
        isHostRunning: @escaping () -> Bool,
        onReady: @escaping () -> Void
    ) {
        self.lifecycleListener = lifecycleListener
        self.isHostRunning = isHostRunning
        self.onReady = onReady
    }

    func startIfNeeded() {
        var shouldBootstrapJvm = false
        lock.lock()
        if !didBootstrapJvm {
            didBootstrapJvm = true
            shouldBootstrapJvm = true
        }
        lock.unlock()
        if shouldBootstrapJvm {
            DispatchQueue.global(qos: .userInitiated).async {
                logPhaseTiming("JVM Thread Start")
                self.jvmHost.bootstrapJvm()
            }
        }
    }

    func stop() {
    }

    func prepareRuntime(_ runtimeState: ComposeHostRuntimeState) -> Bool {
        guard let jvmHandle = currentJvmHandle() else {
            return false
        }
        return jvmHost.prepareRuntime(
            jvmRaw: jvmHandle,
            runtimeState: runtimeState
        )
    }

    func releaseRuntime(_ runtimeState: ComposeHostRuntimeState) {
        guard let jvmHandle = currentJvmHandle() else {
            return
        }
        jvmHost.disposeRuntime(
            jvmRaw: jvmHandle,
            runtimeState: runtimeState
        )
    }

    func runRenderLoop(
        runtimeState: ComposeHostRuntimeState,
        renderCoordinator: RenderCoordinator,
        isRuntimeRunning: @escaping () -> Bool
    ) {
        guard let jvmHandle = currentJvmHandle() else {
            return
        }
        jvmHost.runRenderLoop(
            jvmRaw: jvmHandle,
            runtimeState: runtimeState,
            waitForRenderSignal: { renderCoordinator.waitForRenderSignal() },
            nextRenderVsyncNanos: { renderCoordinator.consumeRenderTickVsyncNanos() },
            hasDisplaySync: { renderCoordinator.hasDisplaySyncDriver },
            isRuntimeRunning: isRuntimeRunning
        )
    }

    func isReady() -> Bool {
        currentJvmHandle() != nil
    }

    func handleExternalDragEntered(
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        guard let jvmHandle = currentJvmHandle() else {
            return false
        }
        return jvmHost.handleExternalDragEntered(
            jvmRaw: jvmHandle,
            runtimeState: runtimeState,
            snapshot: snapshot
        )
    }

    func handleExternalDragMoved(
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        guard let jvmHandle = currentJvmHandle() else {
            return false
        }
        return jvmHost.handleExternalDragMoved(
            jvmRaw: jvmHandle,
            runtimeState: runtimeState,
            snapshot: snapshot
        )
    }

    func handleExternalDragExited(runtimeState: ComposeHostRuntimeState) {
        guard let jvmHandle = currentJvmHandle() else {
            return
        }
        jvmHost.handleExternalDragExited(
            jvmRaw: jvmHandle,
            runtimeState: runtimeState
        )
    }

    func handleExternalDragEnded(runtimeState: ComposeHostRuntimeState) {
        guard let jvmHandle = currentJvmHandle() else {
            return
        }
        jvmHost.handleExternalDragEnded(
            jvmRaw: jvmHandle,
            runtimeState: runtimeState
        )
    }

    func handleExternalDrop(
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        guard let jvmHandle = currentJvmHandle() else {
            return false
        }
        return jvmHost.handleExternalDrop(
            jvmRaw: jvmHandle,
            runtimeState: runtimeState,
            snapshot: snapshot
        )
    }

    private func currentJvmHandle() -> UnsafeMutableRawPointer? {
        lock.lock()
        let jvmHandle = jvmHandle
        lock.unlock()
        return jvmHandle
    }

    private func handleJvmCreated(_ jvmRaw: UnsafeMutableRawPointer) {
        lock.lock()
        jvmHandle = jvmRaw
        lock.unlock()
        onReady()
    }
}

final class ComposeSharedLibraryRuntimeBackend: ComposeHostRuntimeBackend {
    private typealias GraalCreateIsolate = @convention(c) (
        UnsafeMutableRawPointer?,
        UnsafeMutablePointer<UnsafeMutableRawPointer?>?,
        UnsafeMutablePointer<UnsafeMutableRawPointer?>?
    ) -> Int32
    private typealias GraalAttachThread = @convention(c) (
        UnsafeMutableRawPointer?,
        UnsafeMutablePointer<UnsafeMutableRawPointer?>?
    ) -> Int32
    private typealias GraalGetCurrentThread = @convention(c) (UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer?
    private typealias GraalDetachThread = @convention(c) (UnsafeMutableRawPointer?) -> Int32
    private typealias ComposeRuntimeInitialize = @convention(c) (UnsafeMutableRawPointer?) -> Void
    private typealias ComposeRuntimeCreate = @convention(c) (UnsafeMutableRawPointer?, Int64, Int32) -> Int64
    private typealias ComposeRuntimeBindMain = @convention(c) (UnsafeMutableRawPointer?, Int64, UnsafePointer<CChar>?) -> Int32
    private typealias ComposeRuntimeStart = @convention(c) (UnsafeMutableRawPointer?, Int64) -> Int32
    private typealias ComposeRuntimeRequestFrame = @convention(c) (UnsafeMutableRawPointer?, Int64, Int64) -> Void
    private typealias ComposeRuntimeClose = @convention(c) (UnsafeMutableRawPointer?, Int64) -> Void
    private typealias ComposeRuntimeHandleExternalDrop = @convention(c) (
        UnsafeMutableRawPointer?,
        Int64,
        Int32,
        Int32,
        Int32,
        Int32,
        Int64,
        UnsafePointer<UnsafePointer<CChar>?>?,
        Int32,
        UnsafePointer<CChar>?,
        UnsafePointer<CChar>?,
        Int32,
        UnsafePointer<CChar>?
    ) -> Int32
    private typealias ComposeRuntimeHandleExternalDragState = @convention(c) (
        UnsafeMutableRawPointer?,
        Int64
    ) -> Void

    private struct SharedLibraryFunctions {
        let handle: UnsafeMutableRawPointer
        let graalCreateIsolate: GraalCreateIsolate
        let graalAttachThread: GraalAttachThread
        let graalGetCurrentThread: GraalGetCurrentThread
        let graalDetachThread: GraalDetachThread
        let initializeRuntime: ComposeRuntimeInitialize
        let createRuntime: ComposeRuntimeCreate
        let startRuntime: ComposeRuntimeStart
        let requestFrame: ComposeRuntimeRequestFrame
        let closeRuntime: ComposeRuntimeClose
        let bindMain: ComposeRuntimeBindMain
        let handleExternalDragEntered: ComposeRuntimeHandleExternalDrop
        let handleExternalDragMoved: ComposeRuntimeHandleExternalDrop
        let handleExternalDragExited: ComposeRuntimeHandleExternalDragState
        let handleExternalDragEnded: ComposeRuntimeHandleExternalDragState
        let handleExternalDrop: ComposeRuntimeHandleExternalDrop
    }

    private let lifecycleListener: ComposeLifecycleListener
    private let libraryName: String
    private let explicitLibraryPath: String?
    private let onReady: () -> Void
    private let lock = NSLock()
    private var functions: SharedLibraryFunctions?
    private var isolate: UnsafeMutableRawPointer?

    init(
        lifecycleListener: ComposeLifecycleListener,
        libraryName: String,
        libraryPath: String?,
        onReady: @escaping () -> Void
    ) {
        self.lifecycleListener = lifecycleListener
        self.libraryName = libraryName
        self.explicitLibraryPath = libraryPath
        self.onReady = onReady
    }

    func startIfNeeded() {
        lock.lock()
        let alreadyReady = isolate != nil
        lock.unlock()
        if alreadyReady {
            return
        }
        guard let loadedFunctions = loadFunctions() else {
            return
        }
        var isolateRef: UnsafeMutableRawPointer?
        var threadRef: UnsafeMutableRawPointer?
        let result = loadedFunctions.graalCreateIsolate(nil, &isolateRef, &threadRef)
        guard result == 0, let isolateRef else {
            lifecycleListener.jvmCreateFailed(result: jint(result))
            return
        }
        if let threadRef {
            _ = loadedFunctions.graalDetachThread(threadRef)
        }
        lock.lock()
        functions = loadedFunctions
        isolate = isolateRef
        lock.unlock()
        onReady()
    }

    func stop() {
    }

    func prepareRuntime(_ runtimeState: ComposeHostRuntimeState) -> Bool {
        while true {
            switch runtimeState.claimRuntimePreparation() {
            case .ready:
                return true
            case .failed:
                return false
            case .wait:
                let waitPrepared = runtimeState.waitForPreparedRuntime()
                if waitPrepared {
                    return true
                }
                if runtimeState.isReleased() {
                    return false
                }
            case .perform:
                var didPrepare = false
                var shouldFinishPreparation = true
                defer {
                    if shouldFinishPreparation {
                        runtimeState.finishRuntimePreparation(success: didPrepare)
                    }
                }

                guard !runtimeState.isReleased(),
                      withAttachedThread({ thread, loadedFunctions in
                          loadedFunctions.initializeRuntime(thread)
                          let runtimeHandle = loadedFunctions.createRuntime(
                              thread,
                              runtimeState.runtimeId,
                              runtimeState.profileRenderingEnabled ? 1 : 0
                          )
                          guard runtimeHandle != 0 else {
                              return false
                          }
                          guard runtimeState.installSharedLibraryRuntimeHandle(runtimeHandle) else {
                              loadedFunctions.closeRuntime(thread, runtimeHandle)
                              return false
                          }
                          let didBindContent = runtimeState.kotlinMainClass.withCString { kotlinMainClass in
                              loadedFunctions.bindMain(
                                  thread,
                                  runtimeHandle,
                                  kotlinMainClass
                              ) != 0
                          }
                          if !didBindContent {
                              _ = runtimeState.clearSharedLibraryRuntimeHandle()
                              loadedFunctions.closeRuntime(thread, runtimeHandle)
                          }
                          return didBindContent
                      }) == true else {
                    if runtimeState.isReleased() {
                        shouldFinishPreparation = false
                        releaseRuntime(runtimeState)
                    }
                    return false
                }
                lifecycleListener.renderRuntimeInitialized()
                didPrepare = true
                return true
            }
        }
    }

    func releaseRuntime(_ runtimeState: ComposeHostRuntimeState) {
        guard runtimeState.isRuntimePrepared() || runtimeState.isReleased() else {
            return
        }
        guard let runtimeHandle = runtimeState.clearSharedLibraryRuntimeHandle() else {
            runtimeState.resetRuntimePreparation()
            return
        }
        _ = withAttachedThread { thread, loadedFunctions in
            loadedFunctions.closeRuntime(thread, runtimeHandle)
            return true
        }
        runtimeState.resetRuntimePreparation()
    }

    func runRenderLoop(
        runtimeState: ComposeHostRuntimeState,
        renderCoordinator: RenderCoordinator,
        isRuntimeRunning: @escaping () -> Bool
    ) {
        lifecycleListener.renderLoopStarted()
        guard runtimeState.waitForPreparedRuntime() else {
            lifecycleListener.renderLoopInitializationFailed()
            return
        }
        guard let attachment = attachThread() else {
            lifecycleListener.renderLoopInitializationFailed()
            return
        }
        defer {
            releaseRuntime(runtimeState)
            if attachment.detachOnExit {
                _ = attachment.functions.graalDetachThread(attachment.thread)
            }
        }
        guard let runtimeHandle = runtimeState.currentSharedLibraryRuntimeHandle(),
              attachment.functions.startRuntime(attachment.thread, runtimeHandle) != 0 else {
            lifecycleListener.renderLoopInitializationFailed()
            return
        }
        while isRuntimeRunning() {
            if renderCoordinator.hasDisplaySyncDriver {
                renderCoordinator.waitForRenderSignal()
            } else {
                sleepNanos(idleWaitNanos)
            }
            guard isRuntimeRunning() else {
                break
            }
            let vsyncNanos = renderCoordinator.consumeRenderTickVsyncNanos()
            attachment.functions.requestFrame(
                attachment.thread,
                runtimeHandle,
                Int64(bitPattern: vsyncNanos)
            )
        }
    }

    func isReady() -> Bool {
        lock.lock()
        let ready = isolate != nil && functions != nil
        lock.unlock()
        return ready
    }

    func handleExternalDragEntered(
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        guard let runtimeHandle = runtimeState.currentSharedLibraryRuntimeHandle() else {
            return false
        }
        return invokeExternalDropHandler(
            snapshot: snapshot
        ) { thread, loadedFunctions, files, fileCount, text, imageBytes, imageBytesCount, imageFormat in
            loadedFunctions.handleExternalDragEntered(
                thread,
                runtimeHandle,
                snapshot.x,
                snapshot.y,
                snapshot.action,
                snapshot.payloadKind,
                snapshot.timestampMillis,
                files,
                fileCount,
                text,
                imageBytes,
                imageBytesCount,
                imageFormat
            )
        }
    }

    func handleExternalDragMoved(
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        guard let runtimeHandle = runtimeState.currentSharedLibraryRuntimeHandle() else {
            return false
        }
        return invokeExternalDropHandler(
            snapshot: snapshot
        ) { thread, loadedFunctions, files, fileCount, text, imageBytes, imageBytesCount, imageFormat in
            loadedFunctions.handleExternalDragMoved(
                thread,
                runtimeHandle,
                snapshot.x,
                snapshot.y,
                snapshot.action,
                snapshot.payloadKind,
                snapshot.timestampMillis,
                files,
                fileCount,
                text,
                imageBytes,
                imageBytesCount,
                imageFormat
            )
        }
    }

    func handleExternalDragExited(runtimeState: ComposeHostRuntimeState) {
        guard let runtimeHandle = runtimeState.currentSharedLibraryRuntimeHandle() else {
            return
        }
        _ = withAttachedThread { thread, loadedFunctions in
            loadedFunctions.handleExternalDragExited(thread, runtimeHandle)
            return true
        }
    }

    func handleExternalDragEnded(runtimeState: ComposeHostRuntimeState) {
        guard let runtimeHandle = runtimeState.currentSharedLibraryRuntimeHandle() else {
            return
        }
        _ = withAttachedThread { thread, loadedFunctions in
            loadedFunctions.handleExternalDragEnded(thread, runtimeHandle)
            return true
        }
    }

    func handleExternalDrop(
        runtimeState: ComposeHostRuntimeState,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        guard let runtimeHandle = runtimeState.currentSharedLibraryRuntimeHandle() else {
            return false
        }
        return invokeExternalDropHandler(
            snapshot: snapshot
        ) { thread, loadedFunctions, files, fileCount, text, imageBytes, imageBytesCount, imageFormat in
            loadedFunctions.handleExternalDrop(
                thread,
                runtimeHandle,
                snapshot.x,
                snapshot.y,
                snapshot.action,
                snapshot.payloadKind,
                snapshot.timestampMillis,
                files,
                fileCount,
                text,
                imageBytes,
                imageBytesCount,
                imageFormat
            )
        }
    }

    private func loadFunctions() -> SharedLibraryFunctions? {
        let libraryPath = resolvedLibraryPath()
        guard let handle = dlopen(libraryPath, RTLD_NOW | RTLD_GLOBAL) else {
            lifecycleListener.jvmLibraryLoadFailed(path: libraryPath)
            return nil
        }
        guard let graalCreateIsolate = resolveSymbol(
            handle: handle,
            name: "graal_create_isolate",
            as: GraalCreateIsolate.self
        ),
        let graalAttachThread = resolveSymbol(
            handle: handle,
            name: "graal_attach_thread",
            as: GraalAttachThread.self
        ),
        let graalGetCurrentThread = resolveSymbol(
            handle: handle,
            name: "graal_get_current_thread",
            as: GraalGetCurrentThread.self
        ),
        let graalDetachThread = resolveSymbol(
            handle: handle,
            name: "graal_detach_thread",
            as: GraalDetachThread.self
        ),
        let initializeRuntime = resolveSymbol(
            handle: handle,
            name: "composeNativeHostRuntimeInitialize",
            as: ComposeRuntimeInitialize.self
        ),
        let createRuntime = resolveSymbol(
            handle: handle,
            name: "composeNativeHostRuntimeCreate",
            as: ComposeRuntimeCreate.self
        ),
        let startRuntime = resolveSymbol(
            handle: handle,
            name: "composeNativeHostRuntimeStart",
            as: ComposeRuntimeStart.self
        ),
        let requestFrame = resolveSymbol(
            handle: handle,
            name: "composeNativeHostRuntimeRequestFrame",
            as: ComposeRuntimeRequestFrame.self
        ),
        let closeRuntime = resolveSymbol(
            handle: handle,
            name: "composeNativeHostRuntimeClose",
            as: ComposeRuntimeClose.self
        ),
        let handleExternalDragEntered = resolveSymbol(
            handle: handle,
            name: sharedLibraryHandleExternalDragEnteredSymbol,
            as: ComposeRuntimeHandleExternalDrop.self
        ),
        let handleExternalDragMoved = resolveSymbol(
            handle: handle,
            name: sharedLibraryHandleExternalDragMovedSymbol,
            as: ComposeRuntimeHandleExternalDrop.self
        ),
        let handleExternalDragExited = resolveSymbol(
            handle: handle,
            name: sharedLibraryHandleExternalDragExitedSymbol,
            as: ComposeRuntimeHandleExternalDragState.self
        ),
        let handleExternalDragEnded = resolveSymbol(
            handle: handle,
            name: sharedLibraryHandleExternalDragEndedSymbol,
            as: ComposeRuntimeHandleExternalDragState.self
        ),
        let handleExternalDrop = resolveSymbol(
            handle: handle,
            name: sharedLibraryHandleExternalDropSymbol,
            as: ComposeRuntimeHandleExternalDrop.self
        ),
        let bindMain = resolveSymbol(
            handle: handle,
            name: sharedLibraryBindMainSymbol,
            as: ComposeRuntimeBindMain.self
        ) else {
            dlclose(handle)
            lifecycleListener.jvmEntryPointResolutionFailed(symbol: "composeNativeHostRuntimeInitialize")
            return nil
        }
        return SharedLibraryFunctions(
            handle: handle,
            graalCreateIsolate: graalCreateIsolate,
            graalAttachThread: graalAttachThread,
            graalGetCurrentThread: graalGetCurrentThread,
            graalDetachThread: graalDetachThread,
            initializeRuntime: initializeRuntime,
            createRuntime: createRuntime,
            startRuntime: startRuntime,
            requestFrame: requestFrame,
            closeRuntime: closeRuntime,
            bindMain: bindMain,
            handleExternalDragEntered: handleExternalDragEntered,
            handleExternalDragMoved: handleExternalDragMoved,
            handleExternalDragExited: handleExternalDragExited,
            handleExternalDragEnded: handleExternalDragEnded,
            handleExternalDrop: handleExternalDrop
        )
    }

    private func resolvedLibraryPath() -> String {
        if let explicitLibraryPath, !explicitLibraryPath.isEmpty {
            return explicitLibraryPath
        }
        return Bundle.main.bundleURL
            .appendingPathComponent("Contents/Resources/native/")
            .appendingPathComponent(libraryName)
            .path
    }

    private func attachThread() -> (
        thread: UnsafeMutableRawPointer,
        functions: SharedLibraryFunctions,
        detachOnExit: Bool
    )? {
        lock.lock()
        guard let functions, let isolate else {
            lock.unlock()
            return nil
        }
        lock.unlock()
        if let currentThread = functions.graalGetCurrentThread(isolate) {
            return (currentThread, functions, false)
        }
        var attachedThread: UnsafeMutableRawPointer?
        guard functions.graalAttachThread(isolate, &attachedThread) == 0,
              let attachedThread else {
            return nil
        }
        return (attachedThread, functions, true)
    }

    private func withAttachedThread<T>(
        _ block: (UnsafeMutableRawPointer, SharedLibraryFunctions) -> T
    ) -> T? {
        guard let attachment = attachThread() else {
            return nil
        }
        defer {
            if attachment.detachOnExit {
                _ = attachment.functions.graalDetachThread(attachment.thread)
            }
        }
        return block(attachment.thread, attachment.functions)
    }

    private func invokeExternalDropHandler(
        snapshot: ExternalDropSnapshot,
        operation: (
            UnsafeMutableRawPointer,
            SharedLibraryFunctions,
            UnsafePointer<UnsafePointer<CChar>?>?,
            Int32,
            UnsafePointer<CChar>?,
            UnsafePointer<CChar>?,
            Int32,
            UnsafePointer<CChar>?
        ) -> Int32
    ) -> Bool {
        withCStringArray(snapshot.files) { files, fileCount in
            withOptionalCString(snapshot.text) { text in
                withOptionalDataPointer(snapshot.imageBytes) { imageBytes, imageBytesCount in
                    withOptionalCString(snapshot.imageFormat) { imageFormat in
                        withAttachedThread { thread, loadedFunctions in
                            operation(
                                thread,
                                loadedFunctions,
                                files,
                                fileCount,
                                text,
                                imageBytes,
                                imageBytesCount,
                                imageFormat
                            ) != 0
                        } ?? false
                    }
                }
            }
        }
    }

    private func withCStringArray<T>(
        _ strings: [String],
        _ body: (UnsafePointer<UnsafePointer<CChar>?>?, Int32) -> T
    ) -> T {
        let ownedPointers = strings.map { strdup($0) }
        defer {
            ownedPointers.forEach { pointer in
                free(pointer)
            }
        }
        let pointers = ownedPointers.map { pointer in
            pointer.map { UnsafePointer<CChar>($0) }
        }
        return pointers.withUnsafeBufferPointer { buffer in
            let baseAddress = buffer.baseAddress
            return body(baseAddress, Int32(buffer.count))
        }
    }

    private func withOptionalCString<T>(
        _ string: String?,
        _ body: (UnsafePointer<CChar>?) -> T
    ) -> T {
        guard let string else {
            return body(nil)
        }
        return string.withCString { pointer in
            body(pointer)
        }
    }

    private func withOptionalDataPointer<T>(
        _ data: Data?,
        _ body: (UnsafePointer<CChar>?, Int32) -> T
    ) -> T {
        guard let data, !data.isEmpty else {
            return body(nil, 0)
        }
        return data.withUnsafeBytes { buffer in
            let pointer = buffer.bindMemory(to: CChar.self).baseAddress
            return body(pointer, Int32(buffer.count))
        }
    }

    private func resolveSymbol<T>(
        handle: UnsafeMutableRawPointer,
        name: String,
        as _: T.Type
    ) -> T? {
        guard let symbol = dlsym(handle, name) else {
            return nil
        }
        return unsafeBitCast(symbol, to: T.self)
    }
}
