import Cocoa
import ComposeNativeHost
import SwiftUI

private let sampleMixedPageSelectedEventName = "sample.page.selected"
private let sampleMixedOpenWindowEventName = "sample.window.open"
private let sampleMixedBaseWindowTitle = "Compose Native Host Sample Mixed"

@objc(SampleMixedAppDelegate)
final class SampleMixedAppDelegate: ComposeAppDelegateBase {
    private let runtimeConfiguration = ComposeRuntimeConfiguration(
        kotlinMainClass: "letmutex.compose.nativehost.sample.HostedMainKt",
        profileRenderingEnabled: true
    )
    private var windowControllers: [UUID: SampleMixedWindowController] = [:]
    private var nextWindowIndex = 0

    override init() {
        super.init(configuration: ComposeHostConfiguration(logging: true, startups: [.jvm, .sharedLibrary()]))
    }

    override func applicationDidFinishLaunching(_ notification: Notification) {
        openSampleWindow()
        super.applicationDidFinishLaunching(notification)
    }

    override func applicationWillTerminate(_ notification: Notification) {
        windowControllers.values.forEach { $0.invalidate() }
        windowControllers.removeAll()
        super.applicationWillTerminate(notification)
    }

    private func openSampleWindow() {
        nextWindowIndex += 1
        let windowId = UUID()
        let windowController = SampleMixedWindowController(
            runtime: makeComposeRuntime(configuration: runtimeConfiguration),
            windowTitle: windowTitle(for: nextWindowIndex),
            onOpenWindowRequested: { [weak self] in
                self?.openSampleWindow()
            },
            onDidClose: { [weak self] in
                self?.windowControllers.removeValue(forKey: windowId)
            }
        )
        let previousWindow = Array(windowControllers.values).last?.window
        windowControllers[windowId] = windowController
        windowController.show(cascadeFrom: previousWindow)
    }

    private func windowTitle(for index: Int) -> String {
        index == 1 ? sampleMixedBaseWindowTitle : "\(sampleMixedBaseWindowTitle) \(index)"
    }
}

private final class SampleMixedWindowController: NSObject, ObservableObject, NSWindowDelegate {
    @Published var selectedPage = "Overview"

    let runtime: ComposeHostRuntime

    weak var window: NSWindow?

    private let windowTitle: String
    private let onOpenWindowRequested: () -> Void
    private let onDidClose: () -> Void

    private var eventTask: Task<Void, Never>?
    private var didClose = false
    private var didInvalidate = false

    init(
        runtime: ComposeHostRuntime,
        windowTitle: String,
        onOpenWindowRequested: @escaping () -> Void,
        onDidClose: @escaping () -> Void
    ) {
        self.runtime = runtime
        self.windowTitle = windowTitle
        self.onOpenWindowRequested = onOpenWindowRequested
        self.onDidClose = onDidClose
        super.init()
    }

    func show(cascadeFrom previousWindow: NSWindow?) {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1320, height: 920),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        // This sample owns programmatically-created windows explicitly.
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.setContentSize(NSSize(width: 1320, height: 920))
        window.title = windowTitle
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true

        if let previousWindow {
            window.setFrameOrigin(
                NSPoint(
                    x: previousWindow.frame.origin.x + 32,
                    y: previousWindow.frame.origin.y - 32,
                )
            )
        } else {
            window.center()
        }

        let hostingView = NSHostingView(rootView: SampleRootView(windowController: self, runtime: runtime))
        hostingView.frame = window.contentView?.bounds ?? NSRect(x: 0, y: 0, width: 1320, height: 920)
        hostingView.autoresizingMask = [.width, .height]
        window.contentView = hostingView
        self.window = window
        startEventTask()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func invalidate() {
        guard !didInvalidate else {
            return
        }
        didInvalidate = true
        eventTask?.cancel()
        eventTask = nil
        runtime.stop()
    }

    func windowWillClose(_ notification: Notification) {
        guard !didClose else {
            return
        }
        didClose = true
        eventTask?.cancel()
        eventTask = nil

        DispatchQueue.main.async { [weak self] in
            guard let self else {
                return
            }
            self.invalidate()
            self.onDidClose()
        }
    }

    private func startEventTask() {
        eventTask?.cancel()
        eventTask = Task { @MainActor [weak self] in
            guard let self else {
                return
            }
            for await event in runtime.events {
                handle(event)
            }
        }
    }

    private func handle(_ event: ComposeHostEvent) {
        guard case let .app(name, payload) = event else {
            return
        }
        if name == sampleMixedPageSelectedEventName {
            selectedPage = payload ?? selectedPage
            return
        }
        if name == sampleMixedOpenWindowEventName {
            onOpenWindowRequested()
        }
    }
}

private struct SampleRootView: View {
    @ObservedObject var windowController: SampleMixedWindowController
    @ObservedObject var runtime: ComposeHostRuntime
    @State private var isProfileRenderingShown = false

    var body: some View {
        ComposeView(runtime: runtime)
            .overlay {
                if runtime.phase != .firstFramePresented {
                    SampleLaunchOverlayView()
                }
            }
            .overlay {
                if isProfileRenderingShown {
                    ComposeProfileRenderingView(runtime: runtime)
                        .ignoresSafeArea()
                        .allowsHitTesting(false)
                }
            }
            .overlay(alignment: .topTrailing) {
                SampleStatusBadgeView(
                    pageTitle: windowController.selectedPage,
                    phaseTitle: runtime.phase.rawValue,
                    isProfileRenderingShown: $isProfileRenderingShown
                )
                .padding(16)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .ignoresSafeArea()
    }
}

private struct SampleLaunchOverlayView: View {
    var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
                .opacity(0.96)
            ProgressView()
                .controlSize(.regular)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
    }
}

private struct SampleStatusBadgeView: View {
    let pageTitle: String
    let phaseTitle: String
    @Binding var isProfileRenderingShown: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(phaseTitle.capitalized)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Color(red: 0.90, green: 0.55, blue: 0.26))

            Text(pageTitle)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(.primary)

            Toggle("Profile Rendering", isOn: $isProfileRenderingShown)
                .toggleStyle(.switch)
                .font(.system(size: 11, weight: .medium))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(minWidth: 200, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}
