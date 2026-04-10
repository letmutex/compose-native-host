import Cocoa
import Foundation

let inputEventKindNone: Int32 = 0
let inputEventKindPointer: Int32 = 1
let inputEventKindKey: Int32 = 2
let inputEventKindText: Int32 = 3
let inputEventRecordStride = 10

let pointerEventTypePress: Int32 = 1
let pointerEventTypeRelease: Int32 = 2
let pointerEventTypeMove: Int32 = 3
let pointerEventTypeEnter: Int32 = 4
let pointerEventTypeExit: Int32 = 5
let pointerEventTypeScroll: Int32 = 6

let keyEventTypeDown: Int32 = 1
let keyEventTypeUp: Int32 = 2

let textInputEventTypeCommit: Int32 = 1
let textInputEventTypeSetComposing: Int32 = 2
let textInputEventTypeFinishComposing: Int32 = 3

let pointerButtonPrimary: Int32 = 1 << 0
let pointerButtonSecondary: Int32 = 1 << 1
let pointerButtonTertiary: Int32 = 1 << 2
let pointerButtonBack: Int32 = 1 << 3
let pointerButtonForward: Int32 = 1 << 4

let keyboardModifierCtrl: Int32 = 1 << 0
let keyboardModifierMeta: Int32 = 1 << 1
let keyboardModifierAlt: Int32 = 1 << 2
let keyboardModifierShift: Int32 = 1 << 3

struct PointerEventRecord {
    var eventType: Int32
    var timestampMillis: Int64
    var x: Float
    var y: Float
    var scrollX: Float
    var scrollY: Float
    var buttonsMask: Int32
    var modifiersMask: Int32
    var buttonIndex: Int32
}

struct KeyEventRecord {
    var eventType: Int32
    var timestampMillis: Int64
    var keyCode: Int32
    var codePoint: Int32
    var modifiersMask: Int32
}

struct TextEventRecord {
    var eventType: Int32
    var timestampMillis: Int64
    var text: String
}

enum InputEventRecord {
    case pointer(PointerEventRecord)
    case key(KeyEventRecord)
    case text(TextEventRecord)
}

final class InputEventStore {
    private static let compactThreshold = 64
    private let onEventEnqueued: () -> Void
    private let lock = NSLock()
    private var queue: [InputEventRecord] = []
    private var head = 0

    init(onEventEnqueued: @escaping () -> Void) {
        self.onEventEnqueued = onEventEnqueued
    }

    func enqueue(_ event: InputEventRecord) {
        lock.lock()
        if coalesceLastQueuedEvent(with: event) == false {
            queue.append(event)
        }
        lock.unlock()
        onEventEnqueued()
    }

    func pollBatch(
        maxCount: Int,
        records: UnsafeMutablePointer<Int64>,
        texts: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
    ) -> Int32 {
        lock.lock()
        defer {
            compactIfNeeded()
            lock.unlock()
        }
        guard maxCount > 0, head < queue.count else {
            return 0
        }

        var count = 0
        while count < maxCount, head < queue.count {
            let recordOffset = count * inputEventRecordStride
            let record = records.advanced(by: recordOffset)
            for index in 0..<inputEventRecordStride {
                record[index] = 0
            }
            texts?[count] = nil

            switch queue[head] {
            case .pointer(let event):
                record[0] = Int64(inputEventKindPointer)
                record[1] = event.timestampMillis
                record[2] = Int64(event.eventType)
                record[3] = Int64(event.x.bitPattern)
                record[4] = Int64(event.y.bitPattern)
                record[5] = Int64(event.scrollX.bitPattern)
                record[6] = Int64(event.scrollY.bitPattern)
                record[7] = Int64(event.buttonsMask)
                record[8] = Int64(event.modifiersMask)
                record[9] = Int64(event.buttonIndex)
            case .key(let event):
                record[0] = Int64(inputEventKindKey)
                record[1] = event.timestampMillis
                record[2] = Int64(event.eventType)
                record[3] = Int64(event.keyCode)
                record[4] = Int64(event.codePoint)
                record[5] = Int64(event.modifiersMask)
            case .text(let event):
                record[0] = Int64(inputEventKindText)
                record[1] = event.timestampMillis
                record[2] = Int64(event.eventType)
                texts?[count] = event.text.withCString { strdup($0) }
            }

            head += 1
            count += 1
        }

        return Int32(count)
    }

    private func coalesceLastQueuedEvent(with event: InputEventRecord) -> Bool {
        guard head < queue.count else {
            return false
        }
        let lastIndex = queue.count - 1
        switch (queue[lastIndex], event) {
        case let (.pointer(previous), .pointer(next)):
            guard previous.eventType == next.eventType else {
                return false
            }
            switch next.eventType {
            case pointerEventTypeMove:
                guard previous.buttonsMask == next.buttonsMask &&
                    previous.modifiersMask == next.modifiersMask &&
                    previous.buttonIndex == next.buttonIndex else {
                    return false
                }
                queue[lastIndex] = .pointer(next)
                return true
            case pointerEventTypeScroll:
                guard previous.buttonsMask == next.buttonsMask &&
                    previous.modifiersMask == next.modifiersMask else {
                    return false
                }
                queue[lastIndex] = .pointer(
                    PointerEventRecord(
                        eventType: next.eventType,
                        timestampMillis: next.timestampMillis,
                        x: next.x,
                        y: next.y,
                        scrollX: previous.scrollX + next.scrollX,
                        scrollY: previous.scrollY + next.scrollY,
                        buttonsMask: next.buttonsMask,
                        modifiersMask: next.modifiersMask,
                        buttonIndex: next.buttonIndex
                    )
                )
                return true
            default:
                return false
            }
        default:
            return false
        }
    }

    private func compactIfNeeded() {
        guard head >= Self.compactThreshold && head * 2 >= queue.count else {
            return
        }
        queue.removeFirst(head)
        head = 0
    }
}

func timestampMillis(for event: NSEvent) -> Int64 {
    Int64((event.timestamp * 1_000.0).rounded())
}

func currentTimestampMillis() -> Int64 {
    Int64((ProcessInfo.processInfo.systemUptime * 1_000.0).rounded())
}

func codePoint(for event: NSEvent) -> Int32 {
    guard let scalar = event.characters?.unicodeScalars.first else {
        return 0
    }
    return Int32(scalar.value)
}

func pointerButtonIndex(for event: NSEvent) -> Int32 {
    switch event.buttonNumber {
    case 0:
        return 0
    case 1:
        return 1
    case 2:
        return 2
    case 3:
        return 3
    case 4:
        return 4
    default:
        return -1
    }
}

func buttonsMask(for event: NSEvent) -> Int32 {
    var mask: Int32 = 0
    let pressedButtons = NSEvent.pressedMouseButtons
    if pressedButtons & (1 << 0) != 0 {
        mask |= pointerButtonPrimary
    }
    if pressedButtons & (1 << 1) != 0 {
        mask |= pointerButtonSecondary
    }
    if pressedButtons & (1 << 2) != 0 {
        mask |= pointerButtonTertiary
    }
    if pressedButtons & (1 << 3) != 0 {
        mask |= pointerButtonBack
    }
    if pressedButtons & (1 << 4) != 0 {
        mask |= pointerButtonForward
    }
    if event.type == .leftMouseDown {
        mask |= pointerButtonPrimary
    }
    if event.type == .rightMouseDown {
        mask |= pointerButtonSecondary
    }
    if event.type == .otherMouseDown {
        switch event.buttonNumber {
        case 2:
            mask |= pointerButtonTertiary
        case 3:
            mask |= pointerButtonBack
        case 4:
            mask |= pointerButtonForward
        default:
            break
        }
    }
    return mask
}

func modifiersMask(for flags: NSEvent.ModifierFlags) -> Int32 {
    var mask: Int32 = 0
    if flags.contains(.control) {
        mask |= keyboardModifierCtrl
    }
    if flags.contains(.command) {
        mask |= keyboardModifierMeta
    }
    if flags.contains(.option) {
        mask |= keyboardModifierAlt
    }
    if flags.contains(.shift) {
        mask |= keyboardModifierShift
    }
    return mask
}
