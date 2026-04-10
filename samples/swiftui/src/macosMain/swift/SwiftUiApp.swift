import Cocoa
import ComposeNativeHost
import SwiftUI

private let sampleSwiftUiPageSelectedEventName = "sample.page.selected"
private let sampleSwiftUiOpenWindowEventName = "sample.window.open"
private let sampleSwiftUiBaseWindowTitle = "Compose Native Host Sample SwiftUI"
private let sampleSwiftUiAdditionalWindowGroupId = "compose-native-host-sample-window"
private let sampleSwiftUiPipSize = CGSize(width: 320, height: 220)

final class SampleSwiftUiAppDelegate: ComposeAppDelegateBase {
    private let runtimeConfiguration = ComposeRuntimeConfiguration(
        kotlinMainClass: "letmutex.compose.nativehost.sample.HostedMainKt",
        profileRenderingEnabled: true
    )
    private let pipRuntimeConfiguration = ComposeRuntimeConfiguration(
        kotlinMainClass: "letmutex.compose.nativehost.sample.PipHostedMainKt"
    )
    private var windowControllers: [Int: SampleSwiftUiWindowController] = [:]
    private var nextWindowIndex = 1

    override init() {
        super.init(configuration: ComposeHostConfiguration(logging: true, startups: [.jvm, .sharedLibrary()]))
    }

    override func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.activate(ignoringOtherApps: true)
        super.applicationDidFinishLaunching(notification)
    }

    override func applicationWillTerminate(_ notification: Notification) {
        windowControllers.values.forEach { $0.invalidate() }
        windowControllers.removeAll()
        super.applicationWillTerminate(notification)
    }

    fileprivate func windowController(for windowIndex: Int) -> SampleSwiftUiWindowController {
        if let windowController = windowControllers[windowIndex] {
            return windowController
        }
        let windowController = SampleSwiftUiWindowController(
            runtime: makeComposeRuntime(configuration: runtimeConfiguration),
            pipRuntime: makeComposeRuntime(configuration: pipRuntimeConfiguration)
        )
        windowControllers[windowIndex] = windowController
        nextWindowIndex = max(nextWindowIndex, windowIndex)
        return windowController
    }

    fileprivate func handleWindowClosed(_ windowIndex: Int) {
        guard let windowController = windowControllers.removeValue(forKey: windowIndex) else {
            return
        }
        windowController.invalidate()
    }

    fileprivate func windowTitle(for windowIndex: Int) -> String {
        windowIndex == 1 ? sampleSwiftUiBaseWindowTitle : "\(sampleSwiftUiBaseWindowTitle) \(windowIndex)"
    }

    fileprivate func makeNextWindowIndex() -> Int {
        nextWindowIndex += 1
        return nextWindowIndex
    }
}

private final class SampleSwiftUiWindowController: ObservableObject {
    @Published var selectedPage = "Overview"
    @Published var openWindowRequestToken = 0

    let runtime: ComposeHostRuntime
    let pipRuntime: ComposeHostRuntime

    private var eventTask: Task<Void, Never>?

    init(
        runtime: ComposeHostRuntime,
        pipRuntime: ComposeHostRuntime
    ) {
        self.runtime = runtime
        self.pipRuntime = pipRuntime
        startEventTask()
    }

    func invalidate() {
        eventTask?.cancel()
        eventTask = nil
        runtime.stop()
        pipRuntime.stop()
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
        if name == sampleSwiftUiPageSelectedEventName {
            selectedPage = payload ?? selectedPage
            return
        }
        if name == sampleSwiftUiOpenWindowEventName {
            openWindowRequestToken += 1
        }
    }
}

@main
struct SampleSwiftUiApp: App {
    @NSApplicationDelegateAdaptor(SampleSwiftUiAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            SampleSwiftUiWindowView(appDelegate: appDelegate, windowIndex: 1)
        }
        .defaultSize(width: 1320, height: 920)
        .windowStyle(.hiddenTitleBar)

        WindowGroup(id: sampleSwiftUiAdditionalWindowGroupId, for: Int.self) { windowIndex in
            if let windowIndex = windowIndex.wrappedValue {
                SampleSwiftUiWindowView(appDelegate: appDelegate, windowIndex: windowIndex)
            }
        }
        .defaultSize(width: 1320, height: 920)
        .windowStyle(.hiddenTitleBar)
    }
}

private struct SampleSwiftUiWindowView: View {
    private let appDelegate: SampleSwiftUiAppDelegate
    private let windowIndex: Int

    @ObservedObject private var windowController: SampleSwiftUiWindowController
    @ObservedObject private var runtime: ComposeHostRuntime
    @ObservedObject private var pipRuntime: ComposeHostRuntime
    @Environment(\.openWindow) private var openWindow
    @State private var isProfileRenderingShown = false

    init(appDelegate: SampleSwiftUiAppDelegate, windowIndex: Int) {
        self.appDelegate = appDelegate
        self.windowIndex = windowIndex
        let windowController = appDelegate.windowController(for: windowIndex)
        _windowController = ObservedObject(wrappedValue: windowController)
        _runtime = ObservedObject(wrappedValue: windowController.runtime)
        _pipRuntime = ObservedObject(wrappedValue: windowController.pipRuntime)
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            ComposeView(runtime: runtime)
                .overlay {
                    if runtime.phase != .firstFramePresented {
                        SampleSwiftUiLaunchOverlay()
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
                    SampleSwiftUiStatusBadge(
                        pageTitle: windowController.selectedPage,
                        phaseTitle: runtime.phase.rawValue,
                        isProfileRenderingShown: $isProfileRenderingShown
                    )
                    .padding(16)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .ignoresSafeArea()

            ComposeView(runtime: pipRuntime)
                .frame(width: sampleSwiftUiPipSize.width, height: sampleSwiftUiPipSize.height)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .strokeBorder(.white.opacity(0.3), lineWidth: 1)
                }
                .overlay {
                    if pipRuntime.phase != .firstFramePresented {
                        SampleSwiftUiLaunchOverlay()
                    }
                }
                .shadow(color: .black.opacity(0.22), radius: 24, x: 0, y: 16)
                .padding(.trailing, 24)
                .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
        .background {
            SampleSwiftUiWindowConfigurator(
                windowTitle: appDelegate.windowTitle(for: windowIndex),
                onWindowClosed: {
                    appDelegate.handleWindowClosed(windowIndex)
                }
            )
        }
        .onChange(of: windowController.openWindowRequestToken) { _, token in
            guard token > 0 else {
                return
            }
            let nextWindowIndex = appDelegate.makeNextWindowIndex()
            DispatchQueue.main.async {
                openWindow(id: sampleSwiftUiAdditionalWindowGroupId, value: nextWindowIndex)
            }
        }
    }
}

private struct SampleSwiftUiLaunchOverlay: View {
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

private struct SampleSwiftUiStatusBadge: View {
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

private struct SampleSwiftUiWindowConfigurator: NSViewRepresentable {
    let windowTitle: String
    let onWindowClosed: () -> Void

    func makeNSView(context: Context) -> NSView {
        WindowConfigView(windowTitle: windowTitle, onWindowClosed: onWindowClosed)
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        guard let view = nsView as? WindowConfigView else {
            return
        }
        view.update(windowTitle: windowTitle, onWindowClosed: onWindowClosed)
        view.configureAttachedWindow()
    }

    private final class WindowConfigView: NSView {
        private weak var configuredWindow: NSWindow?
        private var closeObserver: NSObjectProtocol?
        private var windowTitle: String
        private var onWindowClosed: () -> Void
        private var shouldBringWindowToFront = false

        init(windowTitle: String, onWindowClosed: @escaping () -> Void) {
            self.windowTitle = windowTitle
            self.onWindowClosed = onWindowClosed
            super.init(frame: .zero)
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }

        deinit {
            removeCloseObserver()
        }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            configureAttachedWindow()
        }

        func update(windowTitle: String, onWindowClosed: @escaping () -> Void) {
            self.windowTitle = windowTitle
            self.onWindowClosed = onWindowClosed
        }

        func configureAttachedWindow() {
            guard let window else {
                return
            }
            if configuredWindow !== window {
                configuredWindow = window
                installCloseObserver(for: window)
                shouldBringWindowToFront = true
            }
            window.title = windowTitle
            window.setContentSize(NSSize(width: 1320, height: 920))
            window.toolbar = nil
            window.titleVisibility = .hidden
            window.titlebarAppearsTransparent = true
            if shouldBringWindowToFront {
                shouldBringWindowToFront = false
                bringWindowToFront(window)
            }
        }

        private func installCloseObserver(for window: NSWindow) {
            removeCloseObserver()
            closeObserver = NotificationCenter.default.addObserver(
                forName: NSWindow.willCloseNotification,
                object: window,
                queue: .main
            ) { [weak self] _ in
                self?.onWindowClosed()
            }
        }

        private func removeCloseObserver() {
            guard let closeObserver else {
                return
            }
            NotificationCenter.default.removeObserver(closeObserver)
            self.closeObserver = nil
        }

        private func bringWindowToFront(_ window: NSWindow) {
            NSApp.activate(ignoringOtherApps: true)
            window.collectionBehavior.insert(.moveToActiveSpace)
            window.orderFrontRegardless()
            window.makeMain()
            window.makeKey()
            window.makeKeyAndOrderFront(nil)

            DispatchQueue.main.async { [weak self, weak window] in
                guard
                    let self,
                    let window,
                    self.configuredWindow === window
                else {
                    return
                }
                NSApp.activate(ignoringOtherApps: true)
                window.orderFrontRegardless()
                window.makeMain()
                window.makeKey()
                window.makeKeyAndOrderFront(nil)
            }
        }

        override func hitTest(_ point: NSPoint) -> NSView? {
            nil
        }
    }
}
