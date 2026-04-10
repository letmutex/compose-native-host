import Cocoa
import Foundation

public enum ComposeHostLaunchPhase: String {
    /// Host is starting up.
    case launching
    /// Native window has attached.
    case windowAttached
    /// Hosted Kotlin entry point is ready.
    case jvmReady
    /// First frame reached the screen.
    case firstFramePresented
    /// Host is shutting down.
    case closing
}

public enum ComposeHostEvent {
    /// Launch phase changed.
    case phaseChanged(ComposeHostLaunchPhase)
    /// Window focus changed.
    case windowFocused(Bool)
    /// Window occlusion changed.
    case windowOccluded(Bool)
    /// App-specific payload arrived.
    case app(name: String, payload: String?)
}

/// Selects how a hosted Compose runtime is started inside the native app.
public enum ComposeRuntimeStartup: Equatable {
    /// Starts the runtime by loading the bundled JVM and invoking the Kotlin main class through JNI.
    case jvm
    /// Starts the runtime from a GraalVM shared library bundled with the app.
    case sharedLibrary(libraryName: String = "libcompose-native-host-runtime.dylib", libraryPath: String? = nil)
}

// Resolves the runtime backend from the staged app bundle's `Info.plist`.
// The selected mode must also be present in `startups`.
func resolveComposeRuntimeStartup(
    from startups: [ComposeRuntimeStartup],
    bundle: Bundle = .main,
) -> ComposeRuntimeStartup {
    guard !startups.isEmpty else {
        fatalError("Compose runtime startup list is empty for bundle \(bundle.bundlePath)")
    }
    guard let runtimeMode = bundle.object(forInfoDictionaryKey: "ComposeNativeHostRuntimeMode") as? String else {
        fatalError("Missing ComposeNativeHostRuntimeMode in Info.plist for bundle \(bundle.bundlePath)")
    }
    switch runtimeMode {
    case "sharedLibrary":
        guard let startup = startups.first(where: isSharedLibraryStartup) else {
            fatalError("ComposeNativeHostRuntimeMode 'sharedLibrary' is not configured in allowed startups for bundle \(bundle.bundlePath)")
        }
        return startup
    case "jvm":
        guard let startup = startups.first(where: isJvmStartup) else {
            fatalError("ComposeNativeHostRuntimeMode 'jvm' is not configured in allowed startups for bundle \(bundle.bundlePath)")
        }
        return startup
    default:
        fatalError("Unsupported ComposeNativeHostRuntimeMode '\(runtimeMode)' in Info.plist for bundle \(bundle.bundlePath)")
    }
}

private func isJvmStartup(_ startup: ComposeRuntimeStartup) -> Bool {
    if case .jvm = startup {
        return true
    }
    return false
}

private func isSharedLibraryStartup(_ startup: ComposeRuntimeStartup) -> Bool {
    if case .sharedLibrary = startup {
        return true
    }
    return false
}

public struct ComposeRuntimeConfiguration {
    /// Kotlin entry point invoked for this runtime.
    public let kotlinMainClass: String
    /// Enables triple buffering.
    public let tripleBuffering: Bool
    /// Requests an opaque Metal surface.
    public let opaqueSurface: Bool
    /// Enables profile rendering frame samples.
    public let profileRenderingEnabled: Bool

    public init(
        kotlinMainClass: String,
        tripleBuffering: Bool = true,
        opaqueSurface: Bool = true,
        profileRenderingEnabled: Bool = false
    ) {
        self.kotlinMainClass = kotlinMainClass
        self.tripleBuffering = tripleBuffering
        self.opaqueSurface = opaqueSurface
        self.profileRenderingEnabled = profileRenderingEnabled
    }
}

public struct ComposeHostConfiguration {
    /// Enables host logging.
    public let logging: Bool
    /// Allowed startup modes for all runtimes created by this host engine.
    public let startups: [ComposeRuntimeStartup]

    public init(
        logging: Bool = false,
        startups: [ComposeRuntimeStartup] = [.jvm]
    ) {
        precondition(!startups.isEmpty, "ComposeHostConfiguration.startups must not be empty.")
        self.logging = logging
        self.startups = startups
    }
}

/// Base app delegate that owns the shared native host engine.
open class ComposeAppDelegateBase: NSObject, NSApplicationDelegate {
    private let composeEngine: ComposeHostEngine

    public override init() {
        let configuration = ComposeHostConfiguration()
        setHostLoggingEnabled(configuration.logging)
        logPhaseTiming("ComposeAppDelegateBase init()")
        composeEngine = ComposeHostEngine.shared(configuration: configuration)
        super.init()
        composeEngine.startIfNeeded()
    }

    public init(configuration: ComposeHostConfiguration) {
        setHostLoggingEnabled(configuration.logging)
        logPhaseTiming("ComposeAppDelegateBase init()")
        composeEngine = ComposeHostEngine.shared(configuration: configuration)
        super.init()
        composeEngine.startIfNeeded()
    }

    /// AppKit launch hook for subclasses.
    open func applicationDidFinishLaunching(_ notification: Notification) {
        logPhaseTiming("ComposeAppDelegateBase applicationDidFinishLaunching()")
    }

    /// Stops the shared engine before AppKit terminates.
    open func applicationWillTerminate(_ notification: Notification) {
        composeEngine.stop()
    }

    /// Creates a runtime handle backed by the shared host engine.
    public func makeComposeRuntime(
        configuration: ComposeRuntimeConfiguration
    ) -> ComposeHostRuntime {
        composeEngine.createRuntime(configuration: configuration)
    }
}

/// Runtime object that coordinates startup, window state, and render control.
public final class ComposeHostRuntime: NSObject, ObservableObject {
    /// Runtime configuration.
    public let configuration: ComposeRuntimeConfiguration
    /// Host event stream.
    public let events: AsyncStream<ComposeHostEvent>

    /// Current launch phase.
    @Published public private(set) var phase: ComposeHostLaunchPhase = .launching
    /// Whether the host window is focused.
    @Published public private(set) var isWindowFocused = false
    /// Whether the host window is occluded.
    @Published public private(set) var isWindowOccluded = false
    /// Whether the profile rendering overlay is visible.
    @Published public private(set) var isProfileRenderingVisible = false

    private let engine: ComposeHostEngine
    let runtimeState: ComposeHostRuntimeState
    private var eventContinuation: AsyncStream<ComposeHostEvent>.Continuation?
    private let hostCoordinator: ComposeHostCoordinator
    private let frameSampleDistributor = ComposeRenderFrameSampleDistributor()
    private let firstFrameSampleLock = NSLock()
    private var firstRenderedFrameSampleLogged = false

    init(
        engine: ComposeHostEngine,
        runtimeState: ComposeHostRuntimeState,
        configuration: ComposeRuntimeConfiguration
    ) {
        self.engine = engine
        self.runtimeState = runtimeState
        self.configuration = configuration

        var continuation: AsyncStream<ComposeHostEvent>.Continuation?
        self.events = AsyncStream { streamContinuation in
            continuation = streamContinuation
        }

        self.hostCoordinator = ComposeHostCoordinator(
            engine: engine,
            runtimeState: runtimeState,
            configuration: configuration
        )
        super.init()

        self.eventContinuation = continuation
        self.hostCoordinator.setEventSink { [weak self] event in
            self?.emit(event)
        }
        self.hostCoordinator.setFrameSampleSink { [weak self] sample in
            self?.emitFrameSample(sample)
        }
        self.engine.prepareRuntimeState(runtimeState)
    }

    deinit {
        hostCoordinator.stop()
        eventContinuation?.finish()
        frameSampleDistributor.finish()
        engine.releaseRuntimeState(runtimeState)
    }

    /// Starts the native host.
    public func start() {
        hostCoordinator.start()
    }

    /// Stops the native host.
    public func stop() {
        hostCoordinator.stop()
    }

    /// Creates a stream of rendered frame samples.
    public func makeFrameSampleStream() -> AsyncStream<ComposeRenderFrameSample> {
        frameSampleDistributor.makeStream()
    }

    func bindPlatformView(_ platformView: ComposePlatformView) {
        platformView.hostRuntime = self
        start()
        hostCoordinator.bindHostedView(platformView)
    }

    func updateTextInputGeometry(_ geometry: TextInputGeometryState?) {
        hostCoordinator.updateTextInputGeometry(geometry)
    }

    func handleExternalDragEntered(_ snapshot: ExternalDropSnapshot) -> Bool {
        hostCoordinator.handleExternalDragEntered(snapshot)
    }

    func handleExternalDragMoved(_ snapshot: ExternalDropSnapshot) -> Bool {
        hostCoordinator.handleExternalDragMoved(snapshot)
    }

    func handleExternalDragExited() {
        hostCoordinator.handleExternalDragExited()
    }

    func handleExternalDragEnded() {
        hostCoordinator.handleExternalDragEnded()
    }

    func handleExternalDrop(_ snapshot: ExternalDropSnapshot) -> Bool {
        hostCoordinator.handleExternalDrop(snapshot)
    }

    func emit(_ event: ComposeHostEvent) {
        switch event {
        case .phaseChanged(let newPhase):
            guard phase != newPhase else {
                return
            }
            phase = newPhase
        case .windowFocused(let focused):
            isWindowFocused = focused
        case .windowOccluded(let occluded):
            isWindowOccluded = occluded
        case .app:
            break
        }
        eventContinuation?.yield(event)
    }

    func emitFrameSample(_ sample: ComposeRenderFrameSample) {
        firstFrameSampleLock.lock()
        let shouldLogFirstRenderedFrame = sample.rendered && !firstRenderedFrameSampleLogged
        if shouldLogFirstRenderedFrame {
            firstRenderedFrameSampleLogged = true
        }
        firstFrameSampleLock.unlock()

        if shouldLogFirstRenderedFrame {
            logPhaseTiming(
                "First Compose Frame rendered total=\(formatFrameMicros(sample.totalMicros))ms dispatch=\(formatFrameMicros(sample.dispatchDelayMicros))ms inputDrain=\(formatFrameMicros(sample.inputDrainMicros))ms acquire=\(formatFrameMicros(sample.acquireDrawableMicros))ms sceneRender=\(formatFrameMicros(sample.sceneRenderMicros))ms submit=\(formatFrameMicros(sample.submitMicros))ms"
            )
        }
        frameSampleDistributor.publish(sample)
    }

    public func setProfileRenderingVisible(_ visible: Bool) {
        guard isProfileRenderingVisible != visible else {
            return
        }
        isProfileRenderingVisible = visible
    }
}

private func formatFrameMicros(_ micros: Int32) -> String {
    String(format: "%.3f", Double(micros) / 1000.0)
}

/// Optional callback target for native host lifecycle events.
open class ComposeLifecycleListener: NSObject {
    /// No-op listener.
    public static let none = ComposeLifecycleListener()

    /// Called when AppKit finishes launching.
    open func applicationDidFinishLaunching() {
    }

    /// Called after JVM config is resolved.
    open func jvmConfigurationResolved(
        javaHome: String,
        classpathEntryCount: Int,
        javaOptionCount: Int,
        bridgePath: String
    ) {
    }

    /// Called when JVM config resolution fails.
    open func jvmConfigurationFailed(stage: String) {
    }

    /// Called when the JVM library fails to load.
    open func jvmLibraryLoadFailed(path: String) {
    }

    /// Called when the Kotlin entry point cannot be resolved.
    open func jvmEntryPointResolutionFailed(symbol: String) {
    }

    /// Called when the JVM is created.
    open func jvmCreated() {
    }

    /// Called when JVM creation fails.
    open func jvmCreateFailed(result: Int32) {
    }

    /// Called before the hosted Kotlin main function runs.
    open func mainInvocationStarted(mainClassName: String) {
    }

    /// Called when the hosted Kotlin main function fails.
    open func mainInvocationFailed(mainClassName: String) {
    }

    /// Called when the hosted Kotlin main function finishes.
    open func mainInvocationFinished(mainClassName: String) {
    }

    /// Called when the render loop starts.
    open func renderLoopStarted() {
    }

    /// Called when render loop initialization fails.
    open func renderLoopInitializationFailed() {
    }

    /// Called when the render runtime is initialized.
    open func renderRuntimeInitialized() {
    }
}
