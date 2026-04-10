import Cocoa
import Foundation

struct ExternalDropSnapshot {
    let x: Int32
    let y: Int32
    let action: Int32
    let payloadKind: Int32
    let timestampMillis: Int64
    let files: [String]
    let text: String?
    let imageBytes: Data?
    let imageFormat: String?

    var hasPayload: Bool {
        !files.isEmpty || text != nil || imageBytes != nil
    }
}

final class ComposeHostCoordinator: NSObject {
    let lifecycleListener: ComposeLifecycleListener

    weak var window: NSWindow?
    weak var platformView: ComposePlatformView?

    private let engine: ComposeHostEngine
    private let runtimeState: ComposeHostRuntimeState
    private let metalSurface: MetalSurface
    private let renderCoordinator = RenderCoordinator()
    private lazy var renderHandle = ComposeHostRenderHandle(
        renderCoordinator: renderCoordinator,
        metalSurface: metalSurface,
        onFirstFramePresented: { [weak self] in
            self?.notifyFirstFramePresented()
        }
    )
    private let windowObserver = WindowObserver()
    private let runtimeRunningLock = NSLock()

    private var emitEventSink: (ComposeHostEvent) -> Void = { _ in }
    private var emitFrameSampleSink: (ComposeRenderFrameSample) -> Void = { _ in }

    private var hasStarted = false
    private var isRuntimeRunning = false
    private var renderLoopToken: UInt64 = 0
    private var didStartRenderLoop = false
    private var didEmitJvmReady = false

    init(
        engine: ComposeHostEngine,
        runtimeState: ComposeHostRuntimeState,
        configuration: ComposeRuntimeConfiguration
    ) {
        self.engine = engine
        self.runtimeState = runtimeState
        self.lifecycleListener = .none
        self.metalSurface = MetalSurface(
            tripleBufferingEnabled: configuration.tripleBuffering,
            opaqueSurface: configuration.opaqueSurface
        )
        super.init()
        runtimeState.setCoordinator(self)
        runtimeState.setRenderHandle(renderHandle)
    }

    func start() {
        guard !hasStarted else {
            return
        }
        runtimeState.setCoordinator(self)
        hasStarted = true
        runtimeState.attachment.setClosing(false)
        engine.startIfNeeded()
        engine.prepareRuntimeState(runtimeState)
        if runtimeState.isRuntimePrepared() {
            runtimeDidBecomeReady()
        }
        startRenderLoopIfPossible()
    }

    func stop() {
        guard hasStarted else {
            return
        }
        hasStarted = false
        runtimeState.attachment.setClosing(true)
        didEmitJvmReady = false
        renderHandle.resetFirstFrameState()
        if didStartRenderLoop {
            runtimeState.beginRuntimeTeardown()
        }
        invalidateRenderLoop()
        renderCoordinator.wakeRenderLoop()
        detachWindow()
        emitEvent(.phaseChanged(.closing))
    }

    func stopForEngineShutdown() {
        stop()
    }

    func runtimeDidBecomeReady() {
        guard hasStarted, !didEmitJvmReady else {
            return
        }
        didEmitJvmReady = true
        emitEvent(.phaseChanged(.jvmReady))
    }

    func engineDidBecomeReady() {
        guard hasStarted else {
            return
        }
        startRenderLoopIfPossible()
    }

    func bindHostedView(_ platformView: ComposePlatformView) {
        self.platformView = platformView
        if let window = platformView.window {
            attachWindow(window, platformView: platformView)
        } else if self.window != nil {
            detachWindow()
        }
    }

    func attachWindow(_ window: NSWindow, platformView: ComposePlatformView) {
        let isSameWindow = self.window === window && self.platformView === platformView
        self.platformView = platformView

        if !isSameWindow {
            detachWindow(emitPhase: false)
            self.window = window
            runtimeState.attachment.setAttached(true)
            observeWindow(window)
            metalSurface.prepare(for: platformView)
            logPhaseTiming("Window Attached")
            emitEvent(.phaseChanged(.windowAttached))
        }

        window.acceptsMouseMovedEvents = true
        syncSurfaceMetricsAndDisplayLink(updateDisplayLink: true)
        emitEvent(.windowFocused(window.isKeyWindow))
        emitEvent(.windowOccluded(!window.occlusionState.contains(.visible)))
        startRenderLoopIfPossible()
    }

    func isWindowAttached() -> Bool {
        window != nil
    }

    func emitAppEvent(name: String, payload: String?) {
        dispatchToMain {
            self.emitEvent(.app(name: name, payload: payload))
        }
    }

    func emitFrameSample(_ sample: ComposeRenderFrameSample) {
        emitFrameSampleSink(sample)
    }

    func notifyFirstFramePresented() {
        if Thread.isMainThread {
            emitEvent(.phaseChanged(.firstFramePresented))
        } else {
            DispatchQueue.main.sync {
                self.emitEvent(.phaseChanged(.firstFramePresented))
            }
        }
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
        renderHandle.presentCurrentDrawable()
    }

    func updateTextInputGeometry(_ geometry: TextInputGeometryState?) {
        platformView?.updateTextInputGeometry(geometry)
    }

    func setPointerIcon(_ cursorType: Int32) {
        platformView?.setPointerIcon(cursorType)
    }

    func handleExternalDragEntered(_ snapshot: ExternalDropSnapshot) -> Bool {
        guard snapshot.hasPayload || snapshot.payloadKind != 0 else {
            return false
        }
        return engine.handleExternalDragEntered(
            runtimeId: runtimeState.runtimeId,
            snapshot: snapshot
        )
    }

    func handleExternalDragMoved(_ snapshot: ExternalDropSnapshot) -> Bool {
        engine.handleExternalDragMoved(
            runtimeId: runtimeState.runtimeId,
            snapshot: snapshot
        )
    }

    func handleExternalDragExited() {
        engine.handleExternalDragExited(runtimeId: runtimeState.runtimeId)
    }

    func handleExternalDragEnded() {
        engine.handleExternalDragEnded(runtimeId: runtimeState.runtimeId)
    }

    func handleExternalDrop(_ snapshot: ExternalDropSnapshot) -> Bool {
        engine.handleExternalDrop(
            runtimeId: runtimeState.runtimeId,
            snapshot: snapshot
        )
    }

    func setEventSink(_ sink: @escaping (ComposeHostEvent) -> Void) {
        emitEventSink = sink
    }

    func setFrameSampleSink(_ sink: @escaping (ComposeRenderFrameSample) -> Void) {
        emitFrameSampleSink = sink
    }

    func dispatchToMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }

    private func beginRenderLoop() -> UInt64 {
        runtimeRunningLock.lock()
        renderLoopToken &+= 1
        isRuntimeRunning = true
        let token = renderLoopToken
        runtimeRunningLock.unlock()
        didStartRenderLoop = true
        return token
    }

    private func invalidateRenderLoop() {
        runtimeRunningLock.lock()
        renderLoopToken &+= 1
        isRuntimeRunning = false
        runtimeRunningLock.unlock()
        didStartRenderLoop = false
    }

    private func finishRenderLoop(token: UInt64) {
        runtimeRunningLock.lock()
        let isCurrentToken = renderLoopToken == token
        if isCurrentToken {
            isRuntimeRunning = false
        }
        runtimeRunningLock.unlock()
        if isCurrentToken {
            didStartRenderLoop = false
        }
    }

    private func runtimeRunningSnapshot(for token: UInt64) -> Bool {
        runtimeRunningLock.lock()
        let running = isRuntimeRunning && renderLoopToken == token
        runtimeRunningLock.unlock()
        return running
    }

    private func detachWindow() {
        detachWindow(emitPhase: false)
    }

    private func detachWindow(emitPhase: Bool) {
        windowObserver.removeObservers()
        renderCoordinator.stopDisplayLink()
        window = nil
        runtimeState.attachment.setAttached(false)
        runtimeState.metrics.reset()
        if emitPhase {
            emitEvent(.windowFocused(false))
            emitEvent(.windowOccluded(true))
        }
    }

    private func startRenderLoopIfPossible() {
        guard hasStarted,
              !didStartRenderLoop,
              engine.isJvmReady(),
              window != nil else {
            return
        }
        let renderLoopToken = beginRenderLoop()
        DispatchQueue.global(qos: .userInteractive).async {
            self.engine.runRenderLoop(
                runtimeState: self.runtimeState,
                renderCoordinator: self.renderCoordinator,
                isRuntimeRunning: { [weak self] in
                    self?.runtimeRunningSnapshot(for: renderLoopToken) == true
                }
            )
            self.dispatchToMain {
                self.finishRenderLoop(token: renderLoopToken)
            }
        }
    }

    @discardableResult
    private func refreshMetrics() -> Bool {
        metalSurface.refreshMetrics(
            window: window,
            view: platformView,
            metricsStore: runtimeState.metrics
        )
    }

    private func observeWindow(_ window: NSWindow) {
        windowObserver.observe(
            window,
            onResize: { [weak self] in
                self?.handleWindowDidResize()
            },
            onBackingPropertiesChanged: { [weak self] in
                self?.handleWindowDidChangeBackingProperties()
            },
            onWillClose: { [weak self] in
                self?.handleWindowWillClose()
            },
            onBecomeKey: { [weak self] in
                self?.handleWindowDidBecomeKey()
            },
            onResignKey: { [weak self] in
                self?.handleWindowDidResignKey()
            },
            onOcclusionStateChanged: { [weak self] in
                self?.handleWindowDidChangeOcclusionState()
            }
        )
    }

    private func emitEvent(_ event: ComposeHostEvent) {
        dispatchToMain {
            self.emitEventSink(event)
        }
    }

    private func syncSurfaceMetricsAndDisplayLink(updateDisplayLink: Bool = false) {
        metalSurface.syncLayer(window: window, view: platformView)
        let metricsChanged = refreshMetrics()
        if updateDisplayLink {
            renderCoordinator.setupDisplayLink(window: window, screen: window?.screen)
        }
        requestRenderTickIfNeeded(metricsChanged)
    }

    private func refreshMetricsAndRequestRenderIfNeeded() {
        requestRenderTickIfNeeded(refreshMetrics())
    }

    private func requestRenderTickIfNeeded(_ metricsChanged: Bool) {
        if metricsChanged {
            requestRenderTick()
        }
    }

    private func handleWindowDidResize() {
        syncSurfaceMetricsAndDisplayLink()
    }

    private func handleWindowDidChangeBackingProperties() {
        syncSurfaceMetricsAndDisplayLink(updateDisplayLink: true)
    }

    private func handleWindowWillClose() {
        // Defer detach until the close notification unwinds. Tearing down the
        // window-backed display link during NSWindow.willClose can over-release
        // the closing window on the AppKit thread.
        DispatchQueue.main.async { [weak self] in
            self?.detachWindow()
        }
    }

    private func handleWindowDidBecomeKey() {
        refreshMetricsAndRequestRenderIfNeeded()
        emitEvent(.windowFocused(true))
    }

    private func handleWindowDidResignKey() {
        refreshMetricsAndRequestRenderIfNeeded()
        emitEvent(.windowFocused(false))
    }

    private func handleWindowDidChangeOcclusionState() {
        guard let window else {
            return
        }
        emitEvent(.windowOccluded(!window.occlusionState.contains(.visible)))
    }
}
