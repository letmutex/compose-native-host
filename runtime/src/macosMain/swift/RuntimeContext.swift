import Cocoa
import Foundation

struct CachedWindowInfo: Equatable {
    var width: Int32 = 0
    var height: Int32 = 0
    var scaleFactor: Double = 1.0
    var refreshRate: Int32 = 60
    var isFocused: Bool = true
}

let textInputNotFound: Int32 = -1

struct TextInputGeometryState {
    public var focusedRectLeft: CGFloat = 0
    public var focusedRectTop: CGFloat = 0
    public var focusedRectRight: CGFloat = 0
    public var focusedRectBottom: CGFloat = 0
    public var selectionStart: Int32 = textInputNotFound
    public var selectionEnd: Int32 = textInputNotFound
    public var compositionStart: Int32 = textInputNotFound
    public var compositionEnd: Int32 = textInputNotFound
}

final class WindowMetricsStore {
    private let lock = NSLock()
    private var cachedInfo = CachedWindowInfo()

    func snapshot() -> CachedWindowInfo {
        lock.lock()
        defer { lock.unlock() }
        return cachedInfo
    }

    func update(_ info: CachedWindowInfo) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let changed = cachedInfo != info
        cachedInfo = info
        return changed
    }

    func reset() {
        lock.lock()
        cachedInfo = CachedWindowInfo()
        lock.unlock()
    }
}

final class WindowAttachmentState {
    private let condition = NSCondition()
    private var isAttached = false
    private var isClosing = false

    func setAttached(_ attached: Bool) {
        condition.lock()
        isAttached = attached
        condition.broadcast()
        condition.unlock()
    }

    func setClosing(_ closing: Bool) {
        condition.lock()
        isClosing = closing
        condition.broadcast()
        condition.unlock()
    }

    func waitForAttachment() -> Bool {
        condition.lock()
        while !isAttached && !isClosing {
            condition.wait()
        }
        let attached = isAttached
        condition.unlock()
        return attached
    }
}

final class HostRunState {
    private let condition = NSCondition()
    private var hasStarted = false
    private var isRunning = false

    func setRunning(_ running: Bool) {
        condition.lock()
        hasStarted = true
        isRunning = running
        condition.broadcast()
        condition.unlock()
    }

    func snapshotIsRunning() -> Bool {
        condition.lock()
        let running = isRunning
        condition.unlock()
        return running
    }

    func waitForStop() {
        condition.lock()
        while !hasStarted || isRunning {
            condition.wait()
        }
        condition.unlock()
    }
}

enum RuntimePreparationClaim {
    case perform
    case wait
    case ready
    case failed
}

final class RuntimePreparationState {
    private let condition = NSCondition()
    private var isPreparing = false
    private var isPrepared = false
    private var isTearingDown = false
    private var didFail = false
    private var isReleased = false

    func claim() -> RuntimePreparationClaim {
        condition.lock()
        defer { condition.unlock() }

        if isReleased {
            return .failed
        }
        if isPreparing || isTearingDown {
            return .wait
        }
        if isPrepared {
            return .ready
        }
        if didFail {
            return .failed
        }

        isPreparing = true
        return .perform
    }

    func finish(success: Bool) {
        condition.lock()
        isPreparing = false
        isPrepared = success
        isTearingDown = false
        if !success {
            didFail = true
        }
        condition.broadcast()
        condition.unlock()
    }

    func waitForPreparedRuntime() -> Bool {
        condition.lock()
        while (isPreparing || isTearingDown) && !isReleased {
            condition.wait()
        }
        let prepared = isPrepared && !isReleased
        condition.unlock()
        return prepared
    }

    func waitForPreparationToFinish() {
        condition.lock()
        while isPreparing {
            condition.wait()
        }
        condition.unlock()
    }

    func isPreparedRuntime() -> Bool {
        condition.lock()
        let prepared = isPrepared && !isTearingDown && !isReleased
        condition.unlock()
        return prepared
    }

    func beginTeardown() {
        condition.lock()
        guard !isReleased else {
            condition.unlock()
            return
        }
        isPrepared = false
        isTearingDown = true
        didFail = false
        condition.broadcast()
        condition.unlock()
    }

    func markReleased() {
        condition.lock()
        isReleased = true
        condition.broadcast()
        condition.unlock()
    }

    func reset() {
        condition.lock()
        isPreparing = false
        isPrepared = false
        isTearingDown = false
        didFail = false
        condition.broadcast()
        condition.unlock()
    }

    func released() -> Bool {
        condition.lock()
        let released = isReleased
        condition.unlock()
        return released
    }
}

final class ComposeHostRenderHandle {
    private let renderCoordinator: RenderCoordinator
    private let metalSurface: MetalSurface
    private let firstFrameLock = NSLock()
    private let onFirstFramePresented: () -> Void

    private var didPresentFirstFrame = false

    init(
        renderCoordinator: RenderCoordinator,
        metalSurface: MetalSurface,
        onFirstFramePresented: @escaping () -> Void
    ) {
        self.renderCoordinator = renderCoordinator
        self.metalSurface = metalSurface
        self.onFirstFramePresented = onFirstFramePresented
    }

    func resetFirstFrameState() {
        firstFrameLock.lock()
        didPresentFirstFrame = false
        firstFrameLock.unlock()
    }

    func requestRenderTick() {
        renderCoordinator.requestRenderTick()
    }

    func metalDevicePtr() -> Int64 {
        metalSurface.metalDevicePtr()
    }

    func metalQueuePtr() -> Int64 {
        metalSurface.metalQueuePtr()
    }

    func acquireDrawableTexturePtr() -> Int64 {
        metalSurface.acquireDrawableTexturePtr()
    }

    func presentCurrentDrawable() {
        metalSurface.presentCurrentDrawable()

        firstFrameLock.lock()
        let shouldNotify = !didPresentFirstFrame
        if shouldNotify {
            didPresentFirstFrame = true
        }
        firstFrameLock.unlock()

        if shouldNotify {
            onFirstFramePresented()
        }
    }
}

final class ComposeHostRuntimeState {
    let runtimeId: Int64
    let kotlinMainClass: String
    let profileRenderingEnabled: Bool
    let metrics = WindowMetricsStore()
    let attachment = WindowAttachmentState()
    private let preparation = RuntimePreparationState()
    private let inputEventsLock = NSLock()
    private var inputEventsStore: InputEventStore?
    var inputEvents: InputEventStore {
        inputEventsLock.lock()
        defer { inputEventsLock.unlock() }
        if let inputEventsStore {
            return inputEventsStore
        }
        let store = InputEventStore { [weak self] in
            self?.requestRenderTick()
        }
        inputEventsStore = store
        return store
    }

    private let lock = NSLock()
    private weak var coordinator: ComposeHostCoordinator?
    private var renderHandle: ComposeHostRenderHandle?
    private var jvmRuntimeRef: UnsafeMutableRawPointer?
    private var sharedLibraryRuntimeHandle: Int64?

    init(
        runtimeId: Int64,
        kotlinMainClass: String,
        profileRenderingEnabled: Bool
    ) {
        self.runtimeId = runtimeId
        self.kotlinMainClass = kotlinMainClass
        self.profileRenderingEnabled = profileRenderingEnabled
    }

    func setCoordinator(_ coordinator: ComposeHostCoordinator?) {
        lock.lock()
        self.coordinator = coordinator
        lock.unlock()
    }

    func currentCoordinator() -> ComposeHostCoordinator? {
        lock.lock()
        let coordinator = coordinator
        lock.unlock()
        return coordinator
    }

    func withCoordinator<T>(_ block: (ComposeHostCoordinator?) -> T) -> T {
        block(currentCoordinator())
    }

    func setRenderHandle(_ renderHandle: ComposeHostRenderHandle?) {
        lock.lock()
        self.renderHandle = renderHandle
        lock.unlock()
    }

    func currentRenderHandle() -> ComposeHostRenderHandle? {
        lock.lock()
        let renderHandle = renderHandle
        lock.unlock()
        return renderHandle
    }

    func currentJvmRuntimeRef() -> UnsafeMutableRawPointer? {
        lock.lock()
        let jvmRuntimeRef = jvmRuntimeRef
        lock.unlock()
        return jvmRuntimeRef
    }

    func installJvmRuntimeRef(_ runtimeRef: UnsafeMutableRawPointer) -> UnsafeMutableRawPointer? {
        lock.lock()
        if preparation.released() {
            lock.unlock()
            return nil
        }
        if let existingRuntimeRef = jvmRuntimeRef {
            lock.unlock()
            return existingRuntimeRef
        }
        jvmRuntimeRef = runtimeRef
        lock.unlock()
        return runtimeRef
    }

    func clearJvmRuntimeRef() -> UnsafeMutableRawPointer? {
        lock.lock()
        let runtimeRef = jvmRuntimeRef
        jvmRuntimeRef = nil
        lock.unlock()
        return runtimeRef
    }

    func currentSharedLibraryRuntimeHandle() -> Int64? {
        lock.lock()
        let runtimeHandle = sharedLibraryRuntimeHandle
        lock.unlock()
        return runtimeHandle
    }

    func installSharedLibraryRuntimeHandle(_ runtimeHandle: Int64) -> Bool {
        lock.lock()
        if preparation.released() || sharedLibraryRuntimeHandle != nil {
            lock.unlock()
            return false
        }
        sharedLibraryRuntimeHandle = runtimeHandle
        lock.unlock()
        return true
    }

    func clearSharedLibraryRuntimeHandle() -> Int64? {
        lock.lock()
        let runtimeHandle = sharedLibraryRuntimeHandle
        sharedLibraryRuntimeHandle = nil
        lock.unlock()
        return runtimeHandle
    }

    func requestRenderTick() {
        currentRenderHandle()?.requestRenderTick()
    }

    func claimRuntimePreparation() -> RuntimePreparationClaim {
        preparation.claim()
    }

    func finishRuntimePreparation(success: Bool) {
        preparation.finish(success: success)
    }

    func waitForPreparedRuntime() -> Bool {
        preparation.waitForPreparedRuntime()
    }

    func waitForPreparationToFinish() {
        preparation.waitForPreparationToFinish()
    }

    func isRuntimePrepared() -> Bool {
        preparation.isPreparedRuntime()
    }

    func beginRuntimeTeardown() {
        preparation.beginTeardown()
    }

    func markReleased() {
        preparation.markReleased()
    }

    func isReleased() -> Bool {
        preparation.released()
    }

    func resetRuntimePreparation() {
        preparation.reset()
    }
}

public final class ComposeHostEngine {
    private static let sharedLock = NSLock()
    private static var sharedInstance: ComposeHostEngine?

    static func shared(configuration: ComposeHostConfiguration) -> ComposeHostEngine {
        sharedLock.lock()
        defer { sharedLock.unlock() }
        if let sharedInstance {
            precondition(
                sharedInstance.configuredStartups == configuration.startups,
                "ComposeHostEngine is already initialized with a different startup configuration."
            )
            return sharedInstance
        }
        let engine = ComposeHostEngine(configuration: configuration)
        sharedInstance = engine
        return engine
    }

    static func activeIfInitialized() -> ComposeHostEngine? {
        sharedLock.lock()
        let engine = sharedInstance
        sharedLock.unlock()
        return engine
    }

    let runState = HostRunState()

    private let lock = NSLock()
    private var nextRuntimeId: Int64 = 1
    private var runtimeStates: [Int64: ComposeHostRuntimeState] = [:]
    private var started = false
    private var backendIdentity: ComposeRuntimeBackendIdentity?
    private var backend: ComposeHostRuntimeBackend?

    private let lifecycleListener: ComposeLifecycleListener = .none
    private let configuredStartups: [ComposeRuntimeStartup]

    private init(configuration: ComposeHostConfiguration) {
        configuredStartups = configuration.startups
        let startup = resolveComposeRuntimeStartup(from: configuration.startups)
        configureBackend(startup: startup)
    }

    func usesSharedLibraryBackend() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard case .sharedLibrary = backendIdentity else {
            return false
        }
        return true
    }

    func createRuntime(configuration: ComposeRuntimeConfiguration) -> ComposeHostRuntime {
        let runtimeState = allocateRuntimeState(configuration: configuration)
        return ComposeHostRuntime(
            engine: self,
            runtimeState: runtimeState,
            configuration: configuration
        )
    }

    func prepareRuntimeState(_ runtimeState: ComposeHostRuntimeState) {
        startIfNeeded()
        guard let backend = currentBackend(), backend.isReady() else {
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let prepared = backend.prepareRuntime(runtimeState)
            guard prepared, !runtimeState.isReleased() else {
                return
            }
            runtimeState.withCoordinator { coordinator in
                coordinator?.dispatchToMain {
                    coordinator?.runtimeDidBecomeReady()
                }
            }
        }
    }

    func runtimeState(for runtimeId: Int64) -> ComposeHostRuntimeState? {
        lock.lock()
        let runtimeState = runtimeStates[runtimeId]
        lock.unlock()
        return runtimeState
    }

    func startIfNeeded() {
        var backendToStart: ComposeHostRuntimeBackend?

        lock.lock()
        if !started {
            started = true
            runState.setRunning(true)
        }
        backendToStart = backend
        lock.unlock()

        backendToStart?.startIfNeeded()
    }

    func stop() {
        let coordinators = runtimeCoordinators()
        coordinators.forEach { coordinator in
            coordinator.stopForEngineShutdown()
        }
        runState.setRunning(false)
        currentBackend()?.stop()
    }

    func releaseRuntimeState(_ runtimeState: ComposeHostRuntimeState) {
        runtimeState.markReleased()
        lock.lock()
        runtimeStates.removeValue(forKey: runtimeState.runtimeId)
        let backend = self.backend
        lock.unlock()
        guard let backend else {
            runtimeState.resetRuntimePreparation()
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            runtimeState.waitForPreparationToFinish()
            backend.releaseRuntime(runtimeState)
        }
    }

    func runRenderLoop(
        runtimeState: ComposeHostRuntimeState,
        renderCoordinator: RenderCoordinator,
        isRuntimeRunning: @escaping () -> Bool
    ) {
        guard let backend = currentBackend() else {
            return
        }
        backend.runRenderLoop(
            runtimeState: runtimeState,
            renderCoordinator: renderCoordinator,
            isRuntimeRunning: isRuntimeRunning
        )
    }

    func isJvmReady() -> Bool {
        currentBackend()?.isReady() == true
    }

    func handleExternalDragEntered(
        runtimeId: Int64,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        guard let backend = currentBackend() else {
            return false
        }
        guard let runtimeState = runtimeState(for: runtimeId) else {
            return false
        }
        return backend.handleExternalDragEntered(
            runtimeState: runtimeState,
            snapshot: snapshot
        )
    }

    func handleExternalDragMoved(
        runtimeId: Int64,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        guard let backend = currentBackend() else {
            return false
        }
        guard let runtimeState = runtimeState(for: runtimeId) else {
            return false
        }
        return backend.handleExternalDragMoved(
            runtimeState: runtimeState,
            snapshot: snapshot
        )
    }

    func handleExternalDragExited(runtimeId: Int64) {
        guard let backend = currentBackend() else {
            return
        }
        guard let runtimeState = runtimeState(for: runtimeId) else {
            return
        }
        backend.handleExternalDragExited(runtimeState: runtimeState)
    }

    func handleExternalDragEnded(runtimeId: Int64) {
        guard let backend = currentBackend() else {
            return
        }
        guard let runtimeState = runtimeState(for: runtimeId) else {
            return
        }
        backend.handleExternalDragEnded(runtimeState: runtimeState)
    }

    func handleExternalDrop(
        runtimeId: Int64,
        snapshot: ExternalDropSnapshot
    ) -> Bool {
        guard let backend = currentBackend() else {
            return false
        }
        guard let runtimeState = runtimeState(for: runtimeId) else {
            return false
        }
        return backend.handleExternalDrop(
            runtimeState: runtimeState,
            snapshot: snapshot
        )
    }

    func allocateRuntimeState(
        configuration: ComposeRuntimeConfiguration,
    ) -> ComposeHostRuntimeState {
        lock.lock()
        let runtimeId = nextRuntimeId
        nextRuntimeId += 1
        let runtimeState = ComposeHostRuntimeState(
            runtimeId: runtimeId,
            kotlinMainClass: configuration.kotlinMainClass,
            profileRenderingEnabled: configuration.profileRenderingEnabled
        )
        runtimeStates[runtimeId] = runtimeState
        lock.unlock()
        return runtimeState
    }

    private func currentBackend() -> ComposeHostRuntimeBackend? {
        lock.lock()
        let backend = backend
        lock.unlock()
        return backend
    }

    private func configureBackend(startup: ComposeRuntimeStartup) {
        let identity = startup.backendIdentity
        let resolvedBackend: ComposeHostRuntimeBackend
        switch identity {
        case .jvm:
            resolvedBackend = ComposeJvmRuntimeBackend(
                lifecycleListener: lifecycleListener,
                isHostRunning: { [weak self] in
                    self?.runState.snapshotIsRunning() == true
                },
                onReady: { [weak self] in
                    self?.handleBackendReady()
                }
            )
        case .sharedLibrary(let libraryName, let libraryPath):
            resolvedBackend = ComposeSharedLibraryRuntimeBackend(
                lifecycleListener: lifecycleListener,
                libraryName: libraryName,
                libraryPath: libraryPath,
                onReady: { [weak self] in
                    self?.handleBackendReady()
                }
            )
        }

        lock.lock()
        precondition(backendIdentity == nil && backend == nil, "ComposeHostEngine backend already configured.")
        self.backendIdentity = identity
        backend = resolvedBackend
        lock.unlock()
    }

    private func handleBackendReady() {
        let runtimeStates: [ComposeHostRuntimeState]
        lock.lock()
        runtimeStates = Array(self.runtimeStates.values)
        lock.unlock()
        runtimeStates.forEach(prepareRuntimeState)

        let coordinators = runtimeCoordinators()
        DispatchQueue.main.async {
            coordinators.forEach { $0.engineDidBecomeReady() }
        }
    }

    private func runtimeCoordinators() -> [ComposeHostCoordinator] {
        lock.lock()
        let runtimeStates = Array(runtimeStates.values)
        lock.unlock()
        return runtimeStates.compactMap { runtimeState in
            runtimeState.withCoordinator { $0 }
        }
    }
}
