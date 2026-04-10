import Foundation

/// One completed rendered frame sample published by the host runtime.
public struct ComposeRenderFrameSample: Sendable {
    public let refreshRate: Int32
    public let rendered: Bool
    public let dispatchDelayMicros: Int32
    public let inputDrainMicros: Int32
    public let acquireDrawableMicros: Int32
    public let sceneRenderMicros: Int32
    public let submitMicros: Int32

    public init(
        refreshRate: Int32,
        rendered: Bool,
        dispatchDelayMicros: Int32,
        inputDrainMicros: Int32,
        acquireDrawableMicros: Int32,
        sceneRenderMicros: Int32,
        submitMicros: Int32
    ) {
        self.refreshRate = refreshRate
        self.rendered = rendered
        self.dispatchDelayMicros = dispatchDelayMicros
        self.inputDrainMicros = inputDrainMicros
        self.acquireDrawableMicros = acquireDrawableMicros
        self.sceneRenderMicros = sceneRenderMicros
        self.submitMicros = submitMicros
    }
}

extension ComposeRenderFrameSample {
    var totalMicros: Int32 {
        dispatchDelayMicros +
            inputDrainMicros +
            acquireDrawableMicros +
            sceneRenderMicros +
            submitMicros
    }
}

/// Broadcasts frame samples to any active profiler views.
final class ComposeRenderFrameSampleDistributor {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<ComposeRenderFrameSample>.Continuation] = [:]
    private var finished = false

    func makeStream() -> AsyncStream<ComposeRenderFrameSample> {
        AsyncStream { continuation in
            let id = UUID()

            lock.lock()
            if finished {
                lock.unlock()
                continuation.finish()
                return
            }
            continuations[id] = continuation
            lock.unlock()

            continuation.onTermination = { [weak self] _ in
                self?.removeContinuation(id)
            }
        }
    }

    func publish(_ sample: ComposeRenderFrameSample) {
        lock.lock()
        let activeContinuations = Array(continuations.values)
        lock.unlock()

        for continuation in activeContinuations {
            continuation.yield(sample)
        }
    }

    func finish() {
        lock.lock()
        if finished {
            lock.unlock()
            return
        }
        finished = true
        let activeContinuations = Array(continuations.values)
        continuations.removeAll()
        lock.unlock()

        for continuation in activeContinuations {
            continuation.finish()
        }
    }

    private func removeContinuation(_ id: UUID) {
        lock.lock()
        continuations.removeValue(forKey: id)
        lock.unlock()
    }
}
