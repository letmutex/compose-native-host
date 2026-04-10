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
        return absolute &* UInt64(info.numer) / UInt64(info.denom)
    }
}
