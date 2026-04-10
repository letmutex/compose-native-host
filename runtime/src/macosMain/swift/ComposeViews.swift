import Cocoa
import SwiftUI

/// SwiftUI entry point for the native host content.
public struct ComposeView: NSViewRepresentable {
    let runtime: ComposeHostRuntime

    public init(runtime: ComposeHostRuntime) {
        self.runtime = runtime
    }

    public func makeNSView(context: Context) -> ComposeNSView {
        logPhaseTiming("SwiftUI View Created")
        return ComposeNSView(runtime: runtime)
    }

    public func updateNSView(_ nsView: ComposeNSView, context: Context) {
        nsView.bindRuntime(runtime)
    }
}

/// AppKit view that hosts Compose content and first-draw logging.
public final class ComposeNSView: NSView {
    private let platformView: ComposePlatformView

    public init(runtime: ComposeHostRuntime) {
        self.platformView = ComposePlatformView(
            frame: .zero,
            runtimeState: runtime.runtimeState
        )
        super.init(frame: .zero)

        translatesAutoresizingMaskIntoConstraints = false
        platformView.translatesAutoresizingMaskIntoConstraints = false

        let firstDrawLogger = FirstDrawLoggingView()
        firstDrawLogger.translatesAutoresizingMaskIntoConstraints = false

        addSubview(platformView)
        addSubview(firstDrawLogger)

        NSLayoutConstraint.activate([
            platformView.leadingAnchor.constraint(equalTo: leadingAnchor),
            platformView.trailingAnchor.constraint(equalTo: trailingAnchor),
            platformView.topAnchor.constraint(equalTo: topAnchor),
            platformView.bottomAnchor.constraint(equalTo: bottomAnchor),
            firstDrawLogger.leadingAnchor.constraint(equalTo: leadingAnchor),
            firstDrawLogger.trailingAnchor.constraint(equalTo: trailingAnchor),
            firstDrawLogger.topAnchor.constraint(equalTo: topAnchor),
            firstDrawLogger.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])

        bindRuntime(runtime)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    fileprivate func bindRuntime(_ runtime: ComposeHostRuntime) {
        platformView.bindRuntime(runtime)
        runtime.bindPlatformView(platformView)
    }
}

private final class FirstDrawLoggingView: NSView {
    private var didDraw = false

    override func hitTest(_ point: NSPoint) -> NSView? {
        nil
    }

    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)
        if !didDraw {
            didDraw = true
            logPhaseTiming("Native Window First Draw")
        }
    }
}
