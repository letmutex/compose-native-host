import Cocoa
import Foundation

final class WindowObserver {
    private var windowObservers: [NSObjectProtocol] = []

    func observe(
        _ window: NSWindow,
        onResize: @escaping () -> Void,
        onBackingPropertiesChanged: @escaping () -> Void,
        onWillClose: @escaping () -> Void,
        onBecomeKey: @escaping () -> Void,
        onResignKey: @escaping () -> Void,
        onOcclusionStateChanged: @escaping () -> Void
    ) {
        removeObservers()

        let notificationCenter = NotificationCenter.default
        windowObservers = [
            notificationCenter.addObserver(
                forName: NSWindow.didResizeNotification,
                object: window,
                queue: .main
            ) { _ in
                onResize()
            },
            notificationCenter.addObserver(
                forName: NSWindow.didChangeBackingPropertiesNotification,
                object: window,
                queue: .main
            ) { _ in
                onBackingPropertiesChanged()
            },
            notificationCenter.addObserver(
                forName: NSWindow.willCloseNotification,
                object: window,
                queue: .main
            ) { _ in
                onWillClose()
            },
            notificationCenter.addObserver(
                forName: NSWindow.didBecomeKeyNotification,
                object: window,
                queue: .main
            ) { _ in
                onBecomeKey()
            },
            notificationCenter.addObserver(
                forName: NSWindow.didResignKeyNotification,
                object: window,
                queue: .main
            ) { _ in
                onResignKey()
            },
            notificationCenter.addObserver(
                forName: NSWindow.didChangeOcclusionStateNotification,
                object: window,
                queue: .main
            ) { _ in
                onOcclusionStateChanged()
            },
        ]
    }

    func removeObservers() {
        let notificationCenter = NotificationCenter.default
        windowObservers.forEach(notificationCenter.removeObserver)
        windowObservers.removeAll()
    }
}
