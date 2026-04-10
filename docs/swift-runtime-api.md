# Swift Runtime API

Public app-facing Swift API surface for the native host runtime.

This snapshot intentionally excludes low-level bridge exports from `Exports.swift` and other internal host plumbing that is not meant to be used directly from app code.

## Startup and configuration

```swift
public enum ComposeHostLaunchPhase: String {
    /// Host process startup has begun.
    case launching
    /// The native window has attached.
    case windowAttached
    /// The hosted Kotlin entry point is ready.
    case jvmReady
    /// The first Compose frame has been presented.
    case firstFramePresented
    /// Host shutdown is in progress.
    case closing
}

public enum ComposeHostEvent {
    /// Launch phase changed.
    case phaseChanged(ComposeHostLaunchPhase)
    /// Window focus changed.
    case windowFocused(Bool)
    /// Window occlusion changed.
    case windowOccluded(Bool)
    /// App-defined event emitted from hosted Compose code.
    case app(name: String, payload: String?)
}

public enum ComposeRuntimeStartup: Equatable {
    /// Launch the hosted runtime through the bundled JVM.
    case jvm
    /// Launch the hosted runtime from a bundled GraalVM shared library.
    case sharedLibrary(
        libraryName: String = "libcompose-native-host-runtime.dylib",
        libraryPath: String? = nil
    )
}

public struct ComposeRuntimeConfiguration {
    /// Kotlin entry point invoked for this runtime.
    public let kotlinMainClass: String
    /// Enables triple buffering for the hosted surface.
    public let tripleBuffering: Bool
    /// Requests an opaque Metal surface.
    public let opaqueSurface: Bool
    /// Enables profile frame sample emission.
    public let profileRenderingEnabled: Bool

    /// Creates a hosted runtime configuration.
    public init(
        kotlinMainClass: String,
        tripleBuffering: Bool = true,
        opaqueSurface: Bool = true,
        profileRenderingEnabled: Bool = false
    )
}

public struct ComposeHostConfiguration {
    /// Enables host-side logging.
    public let logging: Bool
    /// Allowed startup modes for runtimes created by the host engine.
    public let startups: [ComposeRuntimeStartup]

    /// Creates a shared host configuration.
    public init(
        logging: Bool = false,
        startups: [ComposeRuntimeStartup] = [.jvm]
    )
}
```

## App integration

```swift
open class ComposeAppDelegateBase: NSObject, NSApplicationDelegate {
    /// Creates a host app delegate with the default configuration.
    public override init()
    /// Creates a host app delegate with an explicit configuration.
    public init(configuration: ComposeHostConfiguration)

    /// AppKit launch hook for subclasses.
    open func applicationDidFinishLaunching(_ notification: Notification)
    /// Stops the shared host engine before termination.
    open func applicationWillTerminate(_ notification: Notification)

    /// Creates a runtime handle backed by the shared host engine.
    public func makeComposeRuntime(
        configuration: ComposeRuntimeConfiguration
    ) -> ComposeHostRuntime
}

public final class ComposeHostRuntime: NSObject, ObservableObject {
    /// Runtime configuration for this hosted instance.
    public let configuration: ComposeRuntimeConfiguration
    /// Async stream of host lifecycle and app events.
    public let events: AsyncStream<ComposeHostEvent>

    /// Current host launch phase.
    @Published public private(set) var phase: ComposeHostLaunchPhase
    /// Whether the native window is focused.
    @Published public private(set) var isWindowFocused: Bool
    /// Whether the native window is occluded.
    @Published public private(set) var isWindowOccluded: Bool
    /// Whether the profile rendering overlay is visible.
    @Published public private(set) var isProfileRenderingVisible: Bool

    /// Starts the native host for this runtime.
    public func start()
    /// Stops the native host for this runtime.
    public func stop()
    /// Creates a stream of rendered frame samples.
    public func makeFrameSampleStream() -> AsyncStream<ComposeRenderFrameSample>
    /// Toggles the profile rendering overlay state.
    public func setProfileRenderingVisible(_ visible: Bool)
}
```

## Hosted views

```swift
public struct ComposeView: NSViewRepresentable {
    /// Creates a SwiftUI wrapper around a hosted runtime.
    public init(runtime: ComposeHostRuntime)
    /// Creates the backing AppKit view.
    public func makeNSView(context: Context) -> ComposeNSView
    /// Rebinds the view to the current runtime.
    public func updateNSView(_ nsView: ComposeNSView, context: Context)
}

public final class ComposeNSView: NSView {
    /// Creates an AppKit host view for a runtime.
    public init(runtime: ComposeHostRuntime)
}
```

## Lifecycle and logging

```swift
/// Emits a phase timing log line when host logging is enabled.
public func logPhaseTiming(_ name: String)

open class ComposeLifecycleListener: NSObject {
    /// Shared no-op listener.
    public static let none: ComposeLifecycleListener

    /// Called when AppKit finishes launching.
    open func applicationDidFinishLaunching()
    /// Called after JVM configuration has been resolved.
    open func jvmConfigurationResolved(
        javaHome: String,
        classpathEntryCount: Int,
        javaOptionCount: Int,
        bridgePath: String
    )
    /// Called when JVM configuration resolution fails.
    open func jvmConfigurationFailed(stage: String)
    /// Called when the JVM library fails to load.
    open func jvmLibraryLoadFailed(path: String)
    /// Called when the Kotlin entry point cannot be resolved.
    open func jvmEntryPointResolutionFailed(symbol: String)
    /// Called when the JVM is created.
    open func jvmCreated()
    /// Called when JVM creation fails.
    open func jvmCreateFailed(result: Int32)
    /// Called before the hosted Kotlin main function runs.
    open func mainInvocationStarted(mainClassName: String)
    /// Called when the hosted Kotlin main function fails.
    open func mainInvocationFailed(mainClassName: String)
    /// Called when the hosted Kotlin main function finishes.
    open func mainInvocationFinished(mainClassName: String)
    /// Called when the render loop starts.
    open func renderLoopStarted()
    /// Called when render loop initialization fails.
    open func renderLoopInitializationFailed()
    /// Called when the render runtime is initialized.
    open func renderRuntimeInitialized()
}
```
