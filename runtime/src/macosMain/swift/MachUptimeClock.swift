import Darwin

enum MachUptimeClock {
    private static let timebaseInfo: mach_timebase_info_data_t = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return info
    }()

    static func nowNanos() -> UInt64 {
        let absolute = mach_absolute_time()
        let info = timebaseInfo
        if info.numer == info.denom {
            return absolute
        }
        let (product, overflow) = UInt64(info.numer).multipliedReportingOverflow(by: absolute)
        let nanos = product / UInt64(info.denom)
        assert(!overflow, "mach_absolute_time * timebaseInfo.numer overflowed UInt64")
        return nanos
    }
}
