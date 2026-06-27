import Cocoa
import SwiftUI

/// Colors used for the profile rendering timing segments.
public struct ComposeProfileBarColors {
    public let dispatchDelay: NSColor
    public let inputDrain: NSColor
    public let sceneRender: NSColor
    public let acquireDrawable: NSColor
    public let submit: NSColor

    public init(
        dispatchDelay: NSColor = .systemPurple,
        inputDrain: NSColor = .systemBlue,
        sceneRender: NSColor = .systemGreen,
        acquireDrawable: NSColor = .systemTeal,
        submit: NSColor = .systemOrange
    ) {
        self.dispatchDelay = dispatchDelay
        self.inputDrain = inputDrain
        self.sceneRender = sceneRender
        self.acquireDrawable = acquireDrawable
        self.submit = submit
    }

    public static let `default` = ComposeProfileBarColors()
}

/// AppKit profile rendering view for a compose host runtime.
public final class ComposeProfileRenderingNSView: NSView {
    private static let barWidth: CGFloat = 10
    private static let barSpacing: CGFloat = 1
    private static let jankLineWidth: CGFloat = 2
    private static let jankLineHeightFraction: CGFloat = 1.0 / 5.0

    private var runtime: ComposeHostRuntime
    private var barColors: ComposeProfileBarColors
    private var jankLineColor: NSColor
    private var jankThresholdMillisOverride: Double?
    private var windowOverride: Int?
    private var frameSampleTask: Task<Void, Never>?
    private var bars: [ComposeRenderFrameSample] = []
    private var pendingWindowSamples: [ComposeRenderFrameSample] = []
    private var latestRefreshRate = 60

    public override var isOpaque: Bool { false }

    public override func hitTest(_ point: NSPoint) -> NSView? {
        nil
    }

    /// Creates a profile rendering view.
    /// - Parameters:
    ///   - runtime: Frame sample source.
    ///   - barColors: Colors for the stacked timing bars.
    ///   - jankLineColor: Color of the jank threshold line.
    ///   - jankThresholdMillis: Jank threshold in milliseconds. Defaults to 1000 / refreshRate.
    ///   - window: Number of frame samples collected before drawing one longest-frame bar. 
    //      Defaults to refreshRate / 20.
    public init(
        runtime: ComposeHostRuntime,
        barColors: ComposeProfileBarColors = .default,
        jankLineColor: NSColor = .systemRed,
        jankThresholdMillis: Double? = nil,
        window: Int? = nil
    ) {
        self.runtime = runtime
        self.barColors = barColors
        self.jankLineColor = jankLineColor
        self.jankThresholdMillisOverride = jankThresholdMillis
        self.windowOverride = window
        super.init(frame: .zero)
        startFrameSampleTask()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        frameSampleTask?.cancel()
    }

    public func bind(
        runtime: ComposeHostRuntime,
        barColors: ComposeProfileBarColors = .default,
        jankLineColor: NSColor = .systemRed,
        jankThresholdMillis: Double? = nil,
        window: Int? = nil
    ) {
        let runtimeChanged = ObjectIdentifier(self.runtime) != ObjectIdentifier(runtime)
        let windowChanged = windowOverride != window
        self.runtime = runtime
        self.barColors = barColors
        self.jankLineColor = jankLineColor
        self.jankThresholdMillisOverride = jankThresholdMillis
        self.windowOverride = window
        if runtimeChanged || windowChanged {
            bars.removeAll(keepingCapacity: true)
            pendingWindowSamples.removeAll(keepingCapacity: true)
            latestRefreshRate = 60
        }
        if runtimeChanged {
            startFrameSampleTask()
        }
        needsDisplay = true
    }

    public override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)

        guard let context = NSGraphicsContext.current?.cgContext else {
            return
        }

        let chartRect = bounds
        guard chartRect.width > 0, chartRect.height > 0 else {
            return
        }

        let visibleBarCount = max(1, Int((chartRect.width + Self.barSpacing) / (Self.barWidth + Self.barSpacing)))
        let visibleBars = Array(bars.suffix(visibleBarCount))
        guard !visibleBars.isEmpty else {
            return
        }

        let jankThresholdMicros = Int((effectiveJankThresholdMillis() * 1000.0).rounded())

        drawBars(
            in: context,
            chartRect: chartRect,
            visibleBars: visibleBars,
            jankThresholdMicros: jankThresholdMicros
        )
        drawJankLine(
            in: context,
            chartRect: chartRect
        )
    }
}

private extension ComposeProfileRenderingNSView {
    func startFrameSampleTask() {
        frameSampleTask?.cancel()
        let currentRuntime = runtime
        frameSampleTask = Task { [weak self] in
            for await sample in currentRuntime.makeFrameSampleStream() {
                guard !Task.isCancelled else {
                    return
                }
                await MainActor.run {
                    self?.appendSample(sample)
                }
            }
        }
    }

    @MainActor
    func appendSample(_ sample: ComposeRenderFrameSample) {
        latestRefreshRate = max(1, Int(sample.refreshRate))
        pendingWindowSamples.append(sample)

        let windowSize = effectiveWindowSize()
        if pendingWindowSamples.count < windowSize {
            needsDisplay = true
            return
        }

        bars.append(longestFrame(in: pendingWindowSamples))
        pendingWindowSamples.removeAll(keepingCapacity: true)

        let maxBars = 600
        if bars.count > maxBars {
            bars.removeFirst(bars.count - maxBars)
        }
        needsDisplay = true
    }

    func effectiveWindowSize() -> Int {
        if let windowOverride {
            return max(1, windowOverride)
        }
        return max(1, latestRefreshRate / 20)
    }

    func effectiveJankThresholdMillis() -> Double {
        if let jankThresholdMillisOverride {
            return max(0, jankThresholdMillisOverride)
        }
        return 1000.0 / Double(max(1, latestRefreshRate))
    }

    func drawJankLine(
        in context: CGContext,
        chartRect: CGRect
    ) {
        let y = chartRect.minY + chartRect.height * Self.jankLineHeightFraction

        context.saveGState()
        context.setStrokeColor(jankLineColor.cgColor)
        context.setLineWidth(Self.jankLineWidth)
        context.move(to: CGPoint(x: chartRect.minX, y: y))
        context.addLine(to: CGPoint(x: chartRect.maxX, y: y))
        context.strokePath()
        context.restoreGState()
    }

    func drawBars(
        in context: CGContext,
        chartRect: CGRect,
        visibleBars: [ComposeRenderFrameSample],
        jankThresholdMicros: Int
    ) {
        context.saveGState()
        context.clip(to: chartRect)

        let stride = Self.barWidth + Self.barSpacing
        let progressOffset = currentWindowProgressOffset()
        let startX = chartRect.maxX - Self.barWidth - progressOffset

        for (offset, barSample) in visibleBars.reversed().enumerated() {
            let x = startX - CGFloat(offset) * stride
            if x + Self.barWidth < chartRect.minX {
                break
            }
            drawSample(
                barSample,
                in: context,
                x: x,
                chartRect: chartRect,
                jankThresholdMicros: jankThresholdMicros
            )
        }

        context.restoreGState()
    }

    func drawSample(
        _ sample: ComposeRenderFrameSample,
        in context: CGContext,
        x: CGFloat,
        chartRect: CGRect,
        jankThresholdMicros: Int
    ) {
        let segments: [(Int32, NSColor)] = [
            (sample.dispatchDelayMicros, barColors.dispatchDelay),
            (sample.inputDrainMicros, barColors.inputDrain),
            (sample.sceneRenderMicros, barColors.sceneRender),
            (sample.acquireDrawableMicros, barColors.acquireDrawable),
            (sample.submitMicros, barColors.submit),
        ]

        var currentY = chartRect.minY
        let thresholdHeight = chartRect.height * Self.jankLineHeightFraction
        for (durationMicros, color) in segments where durationMicros > 0 {
            let height = thresholdHeight * CGFloat(durationMicros) / CGFloat(max(1, jankThresholdMicros))
            let rect = CGRect(x: x, y: currentY, width: Self.barWidth, height: height)
            context.setFillColor(color.cgColor)
            context.fill(rect)
            currentY += height
        }
    }

    func currentWindowProgressOffset() -> CGFloat {
        let windowSize = effectiveWindowSize()
        guard windowSize > 0 else {
            return 0
        }
        return CGFloat(pendingWindowSamples.count) * Self.barWidth / CGFloat(windowSize)
    }

    func longestFrame(in samples: [ComposeRenderFrameSample]) -> ComposeRenderFrameSample {
        var longestSample = samples[0]
        var longestMicros = Int(longestSample.totalMicros)
        for sample in samples.dropFirst() {
            let totalMicros = Int(sample.totalMicros)
            if totalMicros >= longestMicros {
                longestMicros = totalMicros
                longestSample = sample
            }
        }
        return longestSample
    }
}

/// SwiftUI profile rendering view for a compose host runtime.
public struct ComposeProfileRenderingView: NSViewRepresentable {
    private let runtime: ComposeHostRuntime
    private let barColors: ComposeProfileBarColors
    private let jankLineColor: NSColor
    private let jankThresholdMillis: Double?
    private let window: Int?

    /// Creates a SwiftUI profile rendering view.
    /// - Parameters:
    ///   - runtime: Frame sample source.
    ///   - barColors: Colors for the stacked timing bars.
    ///   - jankLineColor: Color of the jank threshold line.
    ///   - jankThresholdMillis: Jank threshold in milliseconds. Defaults to 1000 / refreshRate.
    ///   - window: Number of frame samples collected before drawing one longest-frame bar. 
    //      Defaults to refreshRate / 20.
    public init(
        runtime: ComposeHostRuntime,
        barColors: ComposeProfileBarColors = .default,
        jankLineColor: NSColor = .systemRed,
        jankThresholdMillis: Double? = nil,
        window: Int? = nil
    ) {
        self.runtime = runtime
        self.barColors = barColors
        self.jankLineColor = jankLineColor
        self.jankThresholdMillis = jankThresholdMillis
        self.window = window
    }

    public func makeNSView(context: Context) -> ComposeProfileRenderingNSView {
        ComposeProfileRenderingNSView(
            runtime: runtime,
            barColors: barColors,
            jankLineColor: jankLineColor,
            jankThresholdMillis: jankThresholdMillis,
            window: window
        )
    }

    public func updateNSView(_ nsView: ComposeProfileRenderingNSView, context: Context) {
        nsView.bind(
            runtime: runtime,
            barColors: barColors,
            jankLineColor: jankLineColor,
            jankThresholdMillis: jankThresholdMillis,
            window: window
        )
    }
}
