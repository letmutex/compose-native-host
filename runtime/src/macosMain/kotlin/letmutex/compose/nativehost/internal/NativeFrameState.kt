package letmutex.compose.nativehost.internal

private const val FRAME_STATE_RECORD_STRIDE = 10

internal class NativeFrameState(
    initialCapacity: Int,
) {
    var windowInfo: Long = 0L
    var eventCount: Int = 0
    val records: LongArray = LongArray(initialCapacity * FRAME_STATE_RECORD_STRIDE)
    val texts: Array<String?> = arrayOfNulls(initialCapacity)
}
