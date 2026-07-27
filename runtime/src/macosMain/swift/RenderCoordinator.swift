import Cocoa
import Foundation
import QuartzCore

final class RenderCoordinator: NSObject {
    private let renderSignal = DispatchSemaphore(value: 0)
    private let renderSignalLock = NSLock()

    private var renderSignalPending = false
    private var renderTickRequested = false
    private var pendingRenderTickVsyncNanos: UInt64 = 0

    private var appKitDisplayLink: CADisplayLink?
    private var legacyDisplayTimer: Timer?
    private var legacyDisplayTimerPaused = false

    var hasDisplaySyncDriver: Bool {
        renderSignalLock.lock()
        defer { renderSignalLock.unlock() }
        return appKitDisplayLink != nil || legacyDisplayTimer != nil
    }

    func requestRenderTick() {
        renderSignalLock.lock()
        let hasDriver = appKitDisplayLink != nil || legacyDisplayTimer != nil
        if hasDriver {
            renderTickRequested = true
            if #available(macOS 14.0, *) {
                if let displayLink = appKitDisplayLink {
                    if Thread.isMainThread {
                        displayLink.isPaused = false
                    } else {
                        DispatchQueue.main.async {
                            displayLink.isPaused = false
                        }
                    }
                }
            } else if let legacyDisplayTimer, legacyDisplayTimerPaused {
                legacyDisplayTimerPaused = false
                if Thread.isMainThread {
                    legacyDisplayTimer.fireDate = Date()
                } else {
                    DispatchQueue.main.async {
                        legacyDisplayTimer.fireDate = Date()
                    }
                }
            }
            renderSignalLock.unlock()
            return
        }
        let shouldSignal = !renderSignalPending
        renderSignalPending = true
        renderSignalLock.unlock()
        if shouldSignal {
            renderSignal.signal()
        }
    }

    func waitForRenderSignal() {
        renderSignal.wait()
    }

    func wakeRenderLoop() {
        renderSignalLock.lock()
        let shouldSignal = !renderSignalPending
        renderSignalPending = true
        pendingRenderTickVsyncNanos = 0
        renderSignalLock.unlock()
        if shouldSignal {
            renderSignal.signal()
        }
    }

    func consumeRenderTickVsyncNanos() -> UInt64 {
        renderSignalLock.lock()
        let vsyncNanos = pendingRenderTickVsyncNanos
        pendingRenderTickVsyncNanos = 0
        renderSignalPending = false
        renderSignalLock.unlock()
        return vsyncNanos
    }

    func setupDisplayLink(window: NSWindow?, screen: NSScreen?) {
        logPhaseTiming("[vsync] setupDisplayLink")
        stopDisplayLink()
        if #available(macOS 14.0, *) {
            guard let displayLink = makeAppKitDisplayLink(window: window, screen: screen) else {
                requestRenderTick()
                return
            }
            displayLink.add(to: .main, forMode: .common)
            renderSignalLock.lock()
            appKitDisplayLink = displayLink
            displayLink.isPaused = !renderTickRequested
            renderSignalLock.unlock()
        } else {
            setupLegacyDisplayTimer(screen: screen)
        }
    }

    func stopDisplayLink() {
        let displayLinkToInvalidate: CADisplayLink?
        let timerToInvalidate: Timer?
        if #available(macOS 14.0, *) {
            renderSignalLock.lock()
            displayLinkToInvalidate = appKitDisplayLink
            appKitDisplayLink = nil
            renderSignalLock.unlock()
        } else {
            displayLinkToInvalidate = nil
        }
        displayLinkToInvalidate?.invalidate()
        stopLegacyDisplayTimer()
    }

    @available(macOS 14.0, *)
    private func makeAppKitDisplayLink(window: NSWindow?, screen: NSScreen?) -> CADisplayLink? {
        if let window {
            return window.displayLink(target: self, selector: #selector(handleDisplayLinkTick(_:)))
        }
        if let screen {
            return screen.displayLink(target: self, selector: #selector(handleDisplayLinkTick(_:)))
        }
        return nil
    }

    @objc
    private func handleDisplayLinkTick(_: CADisplayLink) {
        let shouldSignal = schedulePendingRenderTick(at: MachUptimeClock.nowNanos())
        if shouldSignal {
            renderSignal.signal()
        }
    }

    private func setupLegacyDisplayTimer(screen: NSScreen?) {
        let framesPerSecond = max(1, preferredFramesPerSecond(for: screen))
        let timer = Timer(timeInterval: 1.0 / Double(framesPerSecond), repeats: true) { [weak self] _ in
            self?.handleLegacyTimerTick()
        }
        timer.tolerance = 1.0 / Double(framesPerSecond) * 0.15
        RunLoop.main.add(timer, forMode: .common)
        renderSignalLock.lock()
        legacyDisplayTimer = timer
        legacyDisplayTimerPaused = !renderTickRequested
        let shouldParkTimer = legacyDisplayTimerPaused
        renderSignalLock.unlock()
        if shouldParkTimer {
            timer.fireDate = .distantFuture
        }
    }

    private func stopLegacyDisplayTimer() {
        renderSignalLock.lock()
        let timerToInvalidate = legacyDisplayTimer
        legacyDisplayTimer = nil
        legacyDisplayTimerPaused = false
        renderSignalLock.unlock()
        timerToInvalidate?.invalidate()
    }

    private func handleLegacyTimerTick() {
        let shouldSignal = schedulePendingRenderTick(at: MachUptimeClock.nowNanos())
        if shouldSignal {
            renderSignal.signal()
        }
    }

    private func schedulePendingRenderTick(at vsyncNanos: UInt64) -> Bool {
        renderSignalLock.lock()
        defer { renderSignalLock.unlock() }
        let shouldSignal = renderTickRequested && !renderSignalPending
        if shouldSignal {
            renderTickRequested = false
            renderSignalPending = true
            pendingRenderTickVsyncNanos = vsyncNanos
        }
        if !renderTickRequested {
            if #available(macOS 14.0, *) {
                appKitDisplayLink?.isPaused = true
            } else if let legacyDisplayTimer, !legacyDisplayTimerPaused {
                legacyDisplayTimerPaused = true
                legacyDisplayTimer.fireDate = .distantFuture
            }
        }
        return shouldSignal
    }

    private func preferredFramesPerSecond(for screen: NSScreen?) -> Int {
        if #available(macOS 12.0, *), let maxFramesPerSecond = screen?.maximumFramesPerSecond, maxFramesPerSecond > 0 {
            return maxFramesPerSecond
        }
        return 60
    }
}
