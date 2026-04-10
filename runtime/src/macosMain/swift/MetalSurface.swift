import Cocoa
import Foundation
import Metal
import QuartzCore

final class MetalSurface {
    private let tripleBufferingEnabled: Bool
    private let opaqueSurface: Bool

    private var metalLayer: CAMetalLayer?
    private var device: MTLDevice?
    private var commandQueue: MTLCommandQueue?
    private var currentDrawable: CAMetalDrawable?

    init(tripleBufferingEnabled: Bool, opaqueSurface: Bool) {
        self.tripleBufferingEnabled = tripleBufferingEnabled
        self.opaqueSurface = opaqueSurface
    }

    func prepare(for view: NSView) {
        let resolvedDevice: MTLDevice
        if let device {
            resolvedDevice = device
        } else {
            guard let device = MTLCreateSystemDefaultDevice() else {
                fatalError("Metal is unavailable on this machine")
            }
            self.device = device
            resolvedDevice = device
        }

        if commandQueue == nil {
            guard let commandQueue = resolvedDevice.makeCommandQueue() else {
                fatalError("Unable to create a Metal command queue")
            }
            self.commandQueue = commandQueue
        }

        let resolvedLayer: CAMetalLayer
        if let metalLayer {
            resolvedLayer = metalLayer
        } else {
            let layer = CAMetalLayer()
            layer.device = resolvedDevice
            layer.pixelFormat = .bgra8Unorm
            layer.framebufferOnly = true
            layer.allowsNextDrawableTimeout = true
            layer.contentsGravity = .topLeft
            layer.isOpaque = opaqueSurface
            layer.backgroundColor = opaqueSurface ? nil : NSColor.clear.cgColor
            layer.displaySyncEnabled = true
            if #available(macOS 10.13, *) {
                layer.maximumDrawableCount = tripleBufferingEnabled ? 3 : 2
            }
            self.metalLayer = layer
            resolvedLayer = layer
        }

        view.autoresizingMask = [.width, .height]
        view.wantsLayer = true
        if view.layer !== resolvedLayer {
            view.layer = resolvedLayer
        }
    }

    func syncLayer(window: NSWindow?, view: NSView?) {
        guard let window, let view, let metalLayer else {
            return
        }
        let scaleFactor = window.backingScaleFactor
        let bounds = view.bounds
        metalLayer.frame = bounds
        metalLayer.contentsScale = scaleFactor
        metalLayer.drawableSize = CGSize(width: bounds.width * scaleFactor, height: bounds.height * scaleFactor)
    }

    @discardableResult
    func refreshMetrics(
        window: NSWindow?,
        view: NSView?,
        metricsStore: WindowMetricsStore
    ) -> Bool {
        guard let window, let view else {
            return false
        }
        let scaleFactor = Double(window.backingScaleFactor)
        let bounds = view.bounds
        let refreshRate = preferredFramesPerSecond(for: window.screen)
        return metricsStore.update(
            CachedWindowInfo(
                width: Int32((bounds.width * scaleFactor).rounded(.down)),
                height: Int32((bounds.height * scaleFactor).rounded(.down)),
                scaleFactor: scaleFactor,
                refreshRate: Int32(refreshRate),
                isFocused: window.isKeyWindow
            )
        )
    }

    func metalDevicePtr() -> Int64 {
        guard let device else {
            return 0
        }
        return Int64(bitPattern: UInt64(UInt(bitPattern: Unmanaged.passUnretained(device).toOpaque())))
    }

    func metalQueuePtr() -> Int64 {
        guard let commandQueue else {
            return 0
        }
        return Int64(bitPattern: UInt64(UInt(bitPattern: Unmanaged.passUnretained(commandQueue).toOpaque())))
    }

    func acquireDrawableTexturePtr() -> Int64 {
        autoreleasepool {
            guard let metalLayer, let drawable = metalLayer.nextDrawable() else {
                currentDrawable = nil
                return 0
            }
            currentDrawable = drawable
            return Int64(bitPattern: UInt64(UInt(bitPattern: Unmanaged.passUnretained(drawable.texture).toOpaque())))
        }
    }

    func presentCurrentDrawable() {
        autoreleasepool {
            guard let drawable = currentDrawable, let commandQueue else {
                return
            }
            guard let commandBuffer = commandQueue.makeCommandBuffer() else {
                return
            }
            commandBuffer.present(drawable)
            commandBuffer.commit()
            currentDrawable = nil
        }
    }

    private func preferredFramesPerSecond(for screen: NSScreen?) -> Int {
        if #available(macOS 12.0, *), let maxFramesPerSecond = screen?.maximumFramesPerSecond, maxFramesPerSecond > 0 {
            return maxFramesPerSecond
        }
        return 60
    }
}
