import Cocoa
import Foundation

private let hostWindowDimensionMask: UInt64 = (1 << 15) - 1
private let hostWindowScaleMask: UInt64 = (1 << 14) - 1
private let hostWindowRefreshRateMask: UInt64 = (1 << 11) - 1
private let hostWindowScaleMultiplier = 1000.0

private func composeHostEngine() -> ComposeHostEngine? {
    ComposeHostEngine.activeIfInitialized()
}

private func runtimeState(for runtimeId: Int64) -> ComposeHostRuntimeState? {
    composeHostEngine()?.runtimeState(for: runtimeId)
}

@_cdecl("nativeHostGetWindowInfo")
public func nativeHostGetWindowInfo(_ runtimeId: Int64) -> Int64 {
    guard let runtimeState = runtimeState(for: runtimeId) else {
        return 0
    }
    let snapshot = runtimeState.metrics.snapshot()
    let width = UInt64(max(0, min(Int64(snapshot.width), Int64(hostWindowDimensionMask))))
    let height = UInt64(max(0, min(Int64(snapshot.height), Int64(hostWindowDimensionMask))))
    let scale = UInt64(
        max(
            0,
            min(
                Int64((snapshot.scaleFactor * hostWindowScaleMultiplier).rounded()),
                Int64(hostWindowScaleMask)
            )
        )
    )
    let refreshRate = UInt64(max(0, min(Int64(snapshot.refreshRate), Int64(hostWindowRefreshRateMask))))
    let focused: UInt64 = snapshot.isFocused ? 1 : 0

    let packed = height | (width << 15) | (scale << 30) | (refreshRate << 44) | (focused << 55)
    return Int64(bitPattern: packed)
}

@_cdecl("nativeHostInputEventRecordStride")
public func nativeHostInputEventRecordStride() -> Int32 {
    Int32(inputEventRecordStride)
}

@_cdecl("nativeHostMetalDevicePtr")
public func nativeHostMetalDevicePtr(_ runtimeId: Int64) -> Int64 {
    runtimeState(for: runtimeId)?.currentRenderHandle()?.metalDevicePtr() ?? 0
}

@_cdecl("nativeHostMetalQueuePtr")
public func nativeHostMetalQueuePtr(_ runtimeId: Int64) -> Int64 {
    runtimeState(for: runtimeId)?.currentRenderHandle()?.metalQueuePtr() ?? 0
}

@_cdecl("nativeHostAcquireDrawableTexturePtr")
public func nativeHostAcquireDrawableTexturePtr(_ runtimeId: Int64) -> Int64 {
    runtimeState(for: runtimeId)?.currentRenderHandle()?.acquireDrawableTexturePtr() ?? 0
}

@_cdecl("nativeHostPresentDrawable")
public func nativeHostPresentDrawable(_ runtimeId: Int64) {
    runtimeState(for: runtimeId)?.currentRenderHandle()?.presentCurrentDrawable()
}

@_cdecl("nativeHostRequestRenderTick")
public func nativeHostRequestRenderTick(_ runtimeId: Int64) {
    runtimeState(for: runtimeId)?.requestRenderTick()
}

@_cdecl("nativeHostSetPointerIcon")
public func nativeHostSetPointerIcon(_ runtimeId: Int64, _ cursorType: Int32) {
    runtimeState(for: runtimeId)?.withCoordinator { runtime in
        runtime?.dispatchToMain {
            runtime?.setPointerIcon(cursorType)
        }
    }
}

@_cdecl("nativeHostEmitAppEvent")
public func nativeHostEmitAppEvent(
    _ runtimeId: Int64,
    _ namePtr: UnsafePointer<CChar>?,
    _ payloadPtr: UnsafePointer<CChar>?
) {
    guard let runtimeState = runtimeState(for: runtimeId),
          let namePtr else {
        return
    }
    let name = String(cString: namePtr)
    let payload = payloadPtr.map { String(cString: $0) }
    runtimeState.withCoordinator { $0?.emitAppEvent(name: name, payload: payload) }
}

@_cdecl("nativeHostLogPhaseTiming")
public func nativeHostLogPhaseTiming(_ namePtr: UnsafePointer<CChar>?) {
    guard let namePtr else {
        return
    }
    logPhaseTiming(String(cString: namePtr))
}

@_cdecl("nativeHostEmitProfileFrameSample")
public func nativeHostEmitProfileFrameSample(
    _ runtimeId: Int64,
    _ refreshRate: Int32,
    _ rendered: Int32,
    _ dispatchDelayMicros: Int32,
    _ inputDrainMicros: Int32,
    _ acquireDrawableMicros: Int32,
    _ sceneRenderMicros: Int32,
    _ submitMicros: Int32
) {
    guard let runtimeState = runtimeState(for: runtimeId) else {
        return
    }
    let sampleRefreshRate = max(1, refreshRate)
    let sampleDispatchDelayMicros = max(0, dispatchDelayMicros)
    let sampleInputDrainMicros = max(0, inputDrainMicros)
    let sampleAcquireDrawableMicros = max(0, acquireDrawableMicros)
    let sampleSceneRenderMicros = max(0, sceneRenderMicros)
    let sampleSubmitMicros = max(0, submitMicros)
    let sample = ComposeRenderFrameSample(
        refreshRate: sampleRefreshRate,
        rendered: rendered != 0,
        dispatchDelayMicros: sampleDispatchDelayMicros,
        inputDrainMicros: sampleInputDrainMicros,
        acquireDrawableMicros: sampleAcquireDrawableMicros,
        sceneRenderMicros: sampleSceneRenderMicros,
        submitMicros: sampleSubmitMicros
    )
    runtimeState.withCoordinator { $0?.emitFrameSample(sample) }
}

@_cdecl("nativeHostProfileRenderingEnabled")
public func nativeHostProfileRenderingEnabled(_ runtimeId: Int64) -> Int32 {
    runtimeState(for: runtimeId)?.profileRenderingEnabled == true ? 1 : 0
}

@_cdecl("nativeHostUpdateTextInputGeometry")
public func nativeHostUpdateTextInputGeometry(
    _ runtimeId: Int64,
    _ focusedRectLeft: Float,
    _ focusedRectTop: Float,
    _ focusedRectRight: Float,
    _ focusedRectBottom: Float,
    _ selectionStart: Int32,
    _ selectionEnd: Int32,
    _ compositionStart: Int32,
    _ compositionEnd: Int32
) {
    runtimeState(for: runtimeId)?.withCoordinator { runtime in
        runtime?.dispatchToMain {
            runtime?.updateTextInputGeometry(
                TextInputGeometryState(
                    focusedRectLeft: CGFloat(focusedRectLeft),
                    focusedRectTop: CGFloat(focusedRectTop),
                    focusedRectRight: CGFloat(focusedRectRight),
                    focusedRectBottom: CGFloat(focusedRectBottom),
                    selectionStart: selectionStart,
                    selectionEnd: selectionEnd,
                    compositionStart: compositionStart,
                    compositionEnd: compositionEnd
                )
            )
        }
    }
}

@_cdecl("nativeHostClearTextInputGeometry")
public func nativeHostClearTextInputGeometry(_ runtimeId: Int64) {
    runtimeState(for: runtimeId)?.withCoordinator { runtime in
        runtime?.dispatchToMain {
            runtime?.updateTextInputGeometry(nil)
        }
    }
}

@_cdecl("nativeHostIsRunning")
public func nativeHostIsRunning() -> Int32 {
    composeHostEngine()?.runState.snapshotIsRunning() == true ? 1 : 0
}

@_cdecl("nativeHostUsesSharedLibraryRuntime")
public func nativeHostUsesSharedLibraryRuntime() -> Int32 {
    if composeHostEngine()?.usesSharedLibraryBackend() == true {
        return 1
    }
    let runtimeMode = Bundle.main.object(forInfoDictionaryKey: "ComposeNativeHostRuntimeMode") as? String
    return runtimeMode == "sharedLibrary" ? 1 : 0
}

@_cdecl("nativeHostWaitForShutdown")
public func nativeHostWaitForShutdown() {
    composeHostEngine()?.runState.waitForStop()
}

@_cdecl("nativeHostIsWindowAttached")
public func nativeHostIsWindowAttached(_ runtimeId: Int64) -> Int32 {
    runtimeState(for: runtimeId)?.withCoordinator { $0?.isWindowAttached() == true ? 1 : 0 } ?? 0
}

@_cdecl("nativeHostWaitForWindowAttached")
public func nativeHostWaitForWindowAttached(_ runtimeId: Int64) -> Int32 {
    runtimeState(for: runtimeId)?.attachment.waitForAttachment() == true ? 1 : 0
}

@_cdecl("nativeHostPollFrameStateData")
public func nativeHostPollFrameStateData(
    _ runtimeId: Int64,
    _ maxCount: Int32,
    _ packedWindowInfoOut: UnsafeMutablePointer<Int64>?,
    _ recordsOut: UnsafeMutablePointer<Int64>?,
    _ textsOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    guard maxCount > 0,
          let packedWindowInfoOut,
          let recordsOut,
          let runtimeState = runtimeState(for: runtimeId) else {
        return -1
    }

    let packedWindowInfo = nativeHostGetWindowInfo(runtimeId)
    guard packedWindowInfo != 0 else {
        return -1
    }

    packedWindowInfoOut.pointee = packedWindowInfo
    return runtimeState.inputEvents.pollBatch(
        maxCount: Int(maxCount),
        records: recordsOut,
        texts: textsOut
    )
}
