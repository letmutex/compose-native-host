import Darwin
import Foundation

private let appProcessStartTime: TimeInterval = {
    var mib = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
    var process = kinfo_proc()
    var size = MemoryLayout<kinfo_proc>.size
    sysctl(&mib, u_int(mib.count), &process, &size, nil, 0)
    let start = process.kp_proc.p_un.__p_starttime
    return TimeInterval(start.tv_sec) + TimeInterval(start.tv_usec) / 1_000_000.0
}()

private let hostLoggingLock = NSLock()
private var hostLoggingEnabled = false

func setHostLoggingEnabled(_ enabled: Bool) {
    hostLoggingLock.lock()
    defer { hostLoggingLock.unlock() }

    hostLoggingEnabled = enabled
}

public func logPhaseTiming(_ name: String) {
    hostLoggingLock.lock()
    let enabled = hostLoggingEnabled
    hostLoggingLock.unlock()

    guard enabled else {
        return
    }
    print(formattedHostLogLine(name))
}

private func formattedHostLogLine(_ name: String) -> String {
    let now = Date().timeIntervalSince1970
    let duration = (now - appProcessStartTime) * 1000
    return String(format: "[NativeHost] [%.2f ms] Phase: %@", duration, name)
}
