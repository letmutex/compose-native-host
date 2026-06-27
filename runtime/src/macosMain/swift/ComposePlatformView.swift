import Cocoa
import Foundation

private let javaDefaultCursorType: Int32 = 0
private let javaCrosshairCursorType: Int32 = 1
private let javaTextCursorType: Int32 = 2
private let javaWaitCursorType: Int32 = 3
private let javaSouthwestResizeCursorType: Int32 = 4
private let javaSoutheastResizeCursorType: Int32 = 5
private let javaNorthwestResizeCursorType: Int32 = 6
private let javaNortheastResizeCursorType: Int32 = 7
private let javaNorthResizeCursorType: Int32 = 8
private let javaSouthResizeCursorType: Int32 = 9
private let javaWestResizeCursorType: Int32 = 10
private let javaEastResizeCursorType: Int32 = 11
private let javaHandCursorType: Int32 = 12
private let javaMoveCursorType: Int32 = 13

/// NSView that forwards pointer, keyboard, and IME input to the host.
final class ComposePlatformView: NSView, NSTextInputClient {
    private var runtimeState: ComposeHostRuntimeState
    weak var hostRuntime: ComposeHostRuntime?
    private var trackingAreaRef: NSTrackingArea?
    private var markedTextValue: NSAttributedString?
    private var markedSelectionRange = NSRange(location: NSNotFound, length: 0)
    private var textInputGeometry = TextInputGeometryState()
    private var pointerInside = false
    private var currentCursor: NSCursor = .arrow

    init(
        frame frameRect: NSRect,
        runtimeState: ComposeHostRuntimeState
    ) {
        self.runtimeState = runtimeState
        super.init(frame: frameRect)
        registerForDraggedTypes([
            .fileURL,
            .string,
            .png,
            .tiff,
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private var inputEvents: InputEventStore {
        runtimeState.inputEvents
    }

    override var acceptsFirstResponder: Bool { true }

    /// Ensures that the first click on an inactive window is delivered to Compose
    /// instead of being swallowed by AppKit for window activation.
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func resetCursorRects() {
        super.resetCursorRects()
        addCursorRect(bounds, cursor: currentCursor)
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let trackingAreaRef {
            removeTrackingArea(trackingAreaRef)
        }
        let trackingArea = NSTrackingArea(
            rect: bounds,
            options: [.activeInKeyWindow, .inVisibleRect, .mouseMoved, .mouseEnteredAndExited, .enabledDuringMouseDrag],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(trackingArea)
        trackingAreaRef = trackingArea
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        window?.acceptsMouseMovedEvents = true
        hostRuntime?.bindPlatformView(self)
    }

    func bindRuntime(_ runtime: ComposeHostRuntime) {
        runtimeState = runtime.runtimeState
        hostRuntime = runtime
    }

    override func mouseDown(with event: NSEvent) {
        handlePointerPress(event)
    }

    override func mouseUp(with event: NSEvent) {
        handlePointerRelease(event)
    }

    override func rightMouseDown(with event: NSEvent) {
        handlePointerPress(event)
    }

    override func rightMouseUp(with event: NSEvent) {
        handlePointerRelease(event)
    }

    override func otherMouseDown(with event: NSEvent) {
        handlePointerPress(event)
    }

    override func otherMouseUp(with event: NSEvent) {
        handlePointerRelease(event)
    }

    override func mouseMoved(with event: NSEvent) {
        handlePointerMove(event)
    }

    override func mouseDragged(with event: NSEvent) {
        handlePointerMove(event)
    }

    override func rightMouseDragged(with event: NSEvent) {
        handlePointerMove(event)
    }

    override func otherMouseDragged(with event: NSEvent) {
        handlePointerMove(event)
    }

    override func mouseEntered(with event: NSEvent) {
        pointerInside = true
        currentCursor.set()
        enqueuePointerEvent(type: pointerEventTypeEnter, event: event)
    }

    override func mouseExited(with event: NSEvent) {
        pointerInside = false
        enqueuePointerEvent(type: pointerEventTypeExit, event: event)
    }

    override func scrollWheel(with event: NSEvent) {
        let position = pointerPosition(for: event)
        let scale = backingScaleFactor()
        let pointerEvent = PointerEventRecord(
            eventType: pointerEventTypeScroll,
            timestampMillis: timestampMillis(for: event),
            x: Float(position.x),
            y: Float(position.y),
            scrollX: Float(event.scrollingDeltaX * scale),
            scrollY: Float(-event.scrollingDeltaY * scale),
            buttonsMask: buttonsMask(for: event),
            modifiersMask: modifiersMask(for: event.modifierFlags),
            buttonIndex: -1
        )
        inputEvents.enqueue(.pointer(pointerEvent))
    }

    override func keyDown(with event: NSEvent) {
        if shouldHandleKeyInIme(event) {
            interpretKeyEvents([event])
            return
        }
        inputEvents.enqueue(.key(makeKeyEvent(type: keyEventTypeDown, event: event)))
        interpretKeyEvents([event])
    }

    override func keyUp(with event: NSEvent) {
        if shouldHandleKeyInIme(event) {
            return
        }
        inputEvents.enqueue(.key(makeKeyEvent(type: keyEventTypeUp, event: event)))
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        handleExternalDragEntered(sender)
    }

    override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation {
        handleExternalDragMoved(sender)
    }

    override func draggingExited(_ sender: NSDraggingInfo?) {
        hostRuntime?.handleExternalDragExited()
    }

    override func draggingEnded(_ sender: NSDraggingInfo) {
        hostRuntime?.handleExternalDragEnded()
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        guard let snapshot = externalDropSnapshot(for: sender) else {
            hostRuntime?.handleExternalDragExited()
            return false
        }
        let didDrop = hostRuntime?.handleExternalDrop(snapshot) == true
        return didDrop
    }

    override func resignFirstResponder() -> Bool {
        let result = super.resignFirstResponder()
        finishMarkedTextIfNeeded()
        return result
    }

    private func enqueuePointerEvent(type: Int32, event: NSEvent) {
        let position = pointerPosition(for: event)
        let pointerEvent = PointerEventRecord(
            eventType: type,
            timestampMillis: timestampMillis(for: event),
            x: Float(position.x),
            y: Float(position.y),
            scrollX: 0,
            scrollY: 0,
            buttonsMask: buttonsMask(for: event),
            modifiersMask: modifiersMask(for: event.modifierFlags),
            buttonIndex: pointerButtonIndex(for: event)
        )
        inputEvents.enqueue(.pointer(pointerEvent))
    }

    private func handlePointerPress(_ event: NSEvent) {
        window?.makeFirstResponder(self)
        enqueuePointerEvent(type: pointerEventTypePress, event: event)
    }

    private func handlePointerRelease(_ event: NSEvent) {
        enqueuePointerEvent(type: pointerEventTypeRelease, event: event)
    }

    private func handlePointerMove(_ event: NSEvent) {
        enqueuePointerEvent(type: pointerEventTypeMove, event: event)
    }

    private func makeKeyEvent(type: Int32, event: NSEvent) -> KeyEventRecord {
        KeyEventRecord(
            eventType: type,
            timestampMillis: timestampMillis(for: event),
            keyCode: Int32(event.keyCode),
            codePoint: codePoint(for: event),
            modifiersMask: modifiersMask(for: event.modifierFlags)
        )
    }

    private func handleExternalDragEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        guard let snapshot = externalHoverSnapshot(for: sender) else {
            hostRuntime?.handleExternalDragExited()
            return []
        }
        let accepted = hostRuntime?.handleExternalDragEntered(snapshot) == true
        return accepted ? dragOperation(for: snapshot.action) : []
    }

    private func handleExternalDragMoved(_ sender: NSDraggingInfo) -> NSDragOperation {
        guard let snapshot = externalHoverSnapshot(for: sender) else {
            hostRuntime?.handleExternalDragExited()
            return []
        }
        let accepted = hostRuntime?.handleExternalDragMoved(snapshot) == true
        return accepted ? dragOperation(for: snapshot.action) : []
    }

    private func externalHoverSnapshot(for sender: NSDraggingInfo) -> ExternalDropSnapshot? {
        guard let action = hostDropAction(for: sender),
              let payloadKind = externalDropPayloadKind(for: sender.draggingPasteboard) else {
            return nil
        }
        let position = dropPosition(for: sender)
        return ExternalDropSnapshot(
            x: Int32(position.x.rounded()),
            y: Int32(position.y.rounded()),
            action: action,
            payloadKind: payloadKind,
            timestampMillis: currentTimestampMillis(),
            files: [],
            text: nil,
            imageBytes: nil,
            imageFormat: nil
        )
    }

    private func externalDropSnapshot(for sender: NSDraggingInfo) -> ExternalDropSnapshot? {
        guard let action = hostDropAction(for: sender),
              let payload = loadExternalDropPayload(from: sender.draggingPasteboard),
              payload.hasPayload else {
            return nil
        }
        let position = dropPosition(for: sender)
        return ExternalDropSnapshot(
            x: Int32(position.x.rounded()),
            y: Int32(position.y.rounded()),
            action: action,
            payloadKind: payload.payloadKind,
            timestampMillis: currentTimestampMillis(),
            files: payload.files,
            text: payload.text,
            imageBytes: payload.imageBytes,
            imageFormat: payload.imageFormat
        )
    }

    private func loadExternalDropPayload(from pasteboard: NSPasteboard) -> CachedExternalDropPayload? {
        let fileUrls =
            (pasteboard.readObjects(
                forClasses: [NSURL.self],
                options: [.urlReadingFileURLsOnly: true]
            ) as? [URL]) ?? []
        let files = fileUrls.map(javaLikeFileUriString)
        if !files.isEmpty {
            return CachedExternalDropPayload(
                payloadKind: 1,
                files: files,
                text: nil,
                imageBytes: nil,
                imageFormat: nil
            )
        }
        let text = pasteboard.string(forType: .string)
        if let text, !text.isEmpty {
            return CachedExternalDropPayload(
                payloadKind: 2,
                files: [],
                text: text,
                imageBytes: nil,
                imageFormat: nil
            )
        }
        guard let imageBytes = encodedDroppedImage(from: pasteboard) else {
            return nil
        }
        return CachedExternalDropPayload(
            payloadKind: 3,
            files: [],
            text: nil,
            imageBytes: imageBytes,
            imageFormat: "image/png"
        )
    }

    private func encodedDroppedImage(from pasteboard: NSPasteboard) -> Data? {
        if let image = (pasteboard.readObjects(forClasses: [NSImage.self], options: nil) as? [NSImage])?.first,
           let tiffData = image.tiffRepresentation,
           let bitmap = NSBitmapImageRep(data: tiffData),
           let pngData = bitmap.representation(using: .png, properties: [:]) {
            return pngData
        }
        if let tiffData = pasteboard.data(forType: .tiff),
           let bitmap = NSBitmapImageRep(data: tiffData),
           let pngData = bitmap.representation(using: .png, properties: [:]) {
            return pngData
        }
        return nil
    }

    private func javaLikeFileUriString(for url: URL) -> String {
        guard url.isFileURL else {
            return url.absoluteString
        }
        return "file:" + escapedFileUriPath(url.path)
    }

    private func escapedFileUriPath(_ path: String) -> String {
        var encoded = ""
        encoded.reserveCapacity(path.count)
        for scalar in path.unicodeScalars {
            switch scalar {
            case " ", "#", "%", "?":
                for byte in String(scalar).utf8 {
                    encoded += String(format: "%%%02X", byte)
                }
            default:
                encoded.unicodeScalars.append(scalar)
            }
        }
        return encoded
    }

    private func externalDropPayloadKind(for pasteboard: NSPasteboard) -> Int32? {
        if pasteboard.canReadObject(
            forClasses: [NSURL.self],
            options: [.urlReadingFileURLsOnly: true]
        ) {
            return 1
        }
        if pasteboard.canReadObject(forClasses: [NSImage.self], options: nil) ||
            pasteboard.availableType(from: [.tiff]) != nil {
            return 3
        }
        if pasteboard.availableType(from: [.string]) != nil {
            return 2
        }
        return nil
    }

    private func dragOperation(for action: Int32) -> NSDragOperation {
        switch action {
        case 2:
            return .move
        case 3:
            return .link
        default:
            return .copy
        }
    }

    private func hostDropAction(for sender: NSDraggingInfo) -> Int32? {
        guard let operation = resolvedDragOperation(for: sender) else {
            return nil
        }
        if operation.contains(.copy) {
            return 1
        }
        if operation.contains(.move) {
            return 2
        }
        if operation.contains(.link) {
            return 3
        }
        return nil
    }

    private func resolvedDragOperation(for sender: NSDraggingInfo) -> NSDragOperation? {
        let operationMask = sender.draggingSourceOperationMask
        let modifierFlags = NSApp.currentEvent?.modifierFlags.intersection(.deviceIndependentFlagsMask) ?? []

        if modifierFlags.contains(.control) {
            return operationMask.contains(.link) ? .link : nil
        }
        if modifierFlags.contains(.option) {
            return operationMask.contains(.copy) ? .copy : nil
        }
        if modifierFlags.contains(.command) {
            return nil
        }
        if operationMask.contains(.move) {
            return .move
        }
        if operationMask.contains(.copy) {
            return .copy
        }
        if operationMask.contains(.link) {
            return .link
        }
        return nil
    }

    func insertText(_ string: Any, replacementRange _: NSRange) {
        let text = plainString(from: string)
        guard !text.isEmpty else {
            finishMarkedTextIfNeeded()
            return
        }
        clearMarkedTextState()
        enqueueTextEvent(type: textInputEventTypeCommit, text: text)
    }

    func setMarkedText(_ string: Any, selectedRange: NSRange, replacementRange _: NSRange) {
        let text = plainString(from: string)
        markedTextValue = NSAttributedString(string: text)
        markedSelectionRange = selectedRange
        enqueueTextEvent(type: textInputEventTypeSetComposing, text: text)
    }

    func unmarkText() {
        finishMarkedTextIfNeeded()
    }

    func selectedRange() -> NSRange {
        if textInputGeometry.selectionStart == textInputNotFound || textInputGeometry.selectionEnd == textInputNotFound {
            return markedSelectionRange
        }
        let start = Int(textInputGeometry.selectionStart)
        let end = Int(textInputGeometry.selectionEnd)
        return NSRange(location: min(start, end), length: abs(end - start))
    }

    func markedRange() -> NSRange {
        if textInputGeometry.compositionStart != textInputNotFound && textInputGeometry.compositionEnd != textInputNotFound {
            let start = Int(textInputGeometry.compositionStart)
            let end = Int(textInputGeometry.compositionEnd)
            return NSRange(location: min(start, end), length: abs(end - start))
        }
        guard let markedTextValue else {
            return NSRange(location: NSNotFound, length: 0)
        }
        return NSRange(location: 0, length: markedTextValue.length)
    }

    func hasMarkedText() -> Bool {
        !(markedTextValue?.string.isEmpty ?? true)
    }

    func attributedSubstring(forProposedRange range: NSRange, actualRange: NSRangePointer?) -> NSAttributedString? {
        actualRange?.pointee = NSRange(location: NSNotFound, length: 0)
        return nil
    }

    func validAttributesForMarkedText() -> [NSAttributedString.Key] {
        []
    }

    func firstRect(forCharacterRange range: NSRange, actualRange: NSRangePointer?) -> NSRect {
        actualRange?.pointee = range
        let localRect = imeFocusedRectInView()
        let windowRect = convert(localRect, to: nil)
        return window?.convertToScreen(windowRect) ?? .zero
    }

    func characterIndex(for _: NSPoint) -> Int {
        if textInputGeometry.selectionStart != textInputNotFound {
            return Int(textInputGeometry.selectionStart)
        }
        return 0
    }

    override func doCommand(by _: Selector) {
    }

    private func plainString(from value: Any) -> String {
        if let string = value as? String {
            return string
        }
        if let attributedString = value as? NSAttributedString {
            return attributedString.string
        }
        return ""
    }

    private func shouldHandleKeyInIme(_ event: NSEvent) -> Bool {
        guard hasMarkedText() else {
            return false
        }
        return !event.modifierFlags.intersection(.deviceIndependentFlagsMask).contains(.command)
    }

    private func enqueueTextEvent(type: Int32, text: String) {
        let textEvent = TextEventRecord(
            eventType: type,
            timestampMillis: currentTimestampMillis(),
            text: text
        )
        inputEvents.enqueue(.text(textEvent))
    }

    private func clearMarkedTextState() {
        markedTextValue = nil
        markedSelectionRange = NSRange(location: NSNotFound, length: 0)
    }

    private func finishMarkedTextIfNeeded() {
        guard markedTextValue != nil else {
            return
        }
        clearMarkedTextState()
        enqueueTextEvent(type: textInputEventTypeFinishComposing, text: "")
    }

    func updateTextInputGeometry(_ geometry: TextInputGeometryState?) {
        if let geometry {
            textInputGeometry = geometry
        } else {
            textInputGeometry = TextInputGeometryState()
        }
        inputContext?.invalidateCharacterCoordinates()
    }

    func setPointerIcon(_ cursorType: Int32) {
        currentCursor = appKitCursor(for: cursorType)
        window?.invalidateCursorRects(for: self)
        if pointerInside {
            currentCursor.set()
        }
    }

    private func imeFocusedRectInView() -> NSRect {
        let scale = backingScaleFactor()
        let left = textInputGeometry.focusedRectLeft / scale
        let top = textInputGeometry.focusedRectTop / scale
        let right = textInputGeometry.focusedRectRight / scale
        let bottom = textInputGeometry.focusedRectBottom / scale
        let width = max(1, right - left)
        let height = max(1, bottom - top)
        let centerX = left + (width / 2)
        let y = bounds.height - bottom
        return NSRect(x: centerX, y: y, width: 1, height: height)
    }

    private func pointerPosition(for event: NSEvent) -> CGPoint {
        let local = convert(event.locationInWindow, from: nil)
        let scale = backingScaleFactor()
        return CGPoint(
            x: local.x * scale,
            y: (bounds.height - local.y) * scale
        )
    }

    private func dropPosition(for sender: NSDraggingInfo) -> CGPoint {
        let local = convert(sender.draggingLocation, from: nil)
        let scale = backingScaleFactor()
        return CGPoint(
            x: local.x * scale,
            y: (bounds.height - local.y) * scale
        )
    }

    private func backingScaleFactor() -> CGFloat {
        window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 1.0
    }

    private func appKitCursor(for cursorType: Int32) -> NSCursor {
        switch cursorType {
        case javaCrosshairCursorType:
            return .crosshair
        case javaTextCursorType:
            return .iBeam
        case javaHandCursorType:
            return .pointingHand
        case javaMoveCursorType:
            return .openHand
        case javaSouthwestResizeCursorType,
             javaSoutheastResizeCursorType,
             javaNorthwestResizeCursorType,
             javaNortheastResizeCursorType:
            return frameResizeCursor(for: cursorType) ?? .arrow
        case javaNorthResizeCursorType, javaSouthResizeCursorType:
            return .resizeUpDown
        case javaWestResizeCursorType, javaEastResizeCursorType:
            return .resizeLeftRight
        case javaWaitCursorType, javaDefaultCursorType:
            return .arrow
        default:
            return .arrow
        }
    }

    private func frameResizeCursor(for cursorType: Int32) -> NSCursor? {
        guard #available(macOS 15.0, *) else {
            return nil
        }

        let position: NSCursor.FrameResizePosition
        switch cursorType {
        case javaSouthwestResizeCursorType:
            position = .bottomLeft
        case javaSoutheastResizeCursorType:
            position = .bottomRight
        case javaNorthwestResizeCursorType:
            position = .topLeft
        case javaNortheastResizeCursorType:
            position = .topRight
        default:
            return nil
        }

        return NSCursor.frameResize(position: position, directions: .all)
    }

    private struct CachedExternalDropPayload {
        let payloadKind: Int32
        let files: [String]
        let text: String?
        let imageBytes: Data?
        let imageFormat: String?

        var hasPayload: Bool {
            !files.isEmpty || text != nil || imageBytes != nil
        }
    }
}
