package letmutex.compose.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowInfoTest {
    @Test
    fun packsAndUnpacksWindowInfoIntoLong() {
        val windowInfo = WindowInfo(
            width = 12345,
            height = 6789,
            scale = 1.2346f,
            refreshRate = 144,
            isFocused = true,
        )

        assertEquals(12345, windowInfo.width)
        assertEquals(6789, windowInfo.height)
        assertEquals(1.235f, windowInfo.scale)
        assertEquals(144, windowInfo.refreshRate)
        assertTrue(windowInfo.isFocused)
        assertEquals(windowInfo, WindowInfo.fromPacked(windowInfo.packedValue))
    }

    @Test
    fun clampsValuesToBitBudget() {
        val windowInfo = WindowInfo(
            width = 40000,
            height = -1,
            scale = 20f,
            refreshRate = 5000,
            isFocused = false,
        )

        assertEquals(32767, windowInfo.width)
        assertEquals(0, windowInfo.height)
        assertEquals(16.383f, windowInfo.scale)
        assertEquals(2047, windowInfo.refreshRate)
        assertFalse(windowInfo.isFocused)
        assertNull(WindowInfo.fromPacked(windowInfo.packedValue))
    }
}
