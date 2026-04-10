package letmutex.compose.nativehost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderFrameStatsTest {
    @Test
    fun notRenderedReturnsSentinelValues() {
        val stats = RenderFrameStats(rendered = false, acquireDrawableNanos = 1_000, sceneRenderNanos = 2_000, submitNanos = 3_000)

        assertFalse(stats.rendered)
        assertEquals(0, stats.acquireDrawableMicros)
        assertEquals(0, stats.sceneRenderMicros)
        assertEquals(0, stats.submitMicros)
    }

    @Test
    fun renderedStatsPackAndUnpackMicros() {
        val stats = RenderFrameStats(
            rendered = true,
            acquireDrawableNanos = 12_345_678,
            sceneRenderNanos = 98_765_432,
            submitNanos = 1_999,
        )

        assertTrue(stats.rendered)
        assertEquals(12_345, stats.acquireDrawableMicros)
        assertEquals(98_765, stats.sceneRenderMicros)
        assertEquals(1, stats.submitMicros)
        assertEquals(12_345_000L, stats.acquireDrawableNanos)
        assertEquals(98_765_000L, stats.sceneRenderNanos)
        assertEquals(1_000L, stats.submitNanos)
    }

    @Test
    fun renderedStatsClampNegativeAndOverflowValues() {
        val maxStoredMicros = 0x1FFFFF
        val stats = RenderFrameStats(
            rendered = true,
            acquireDrawableNanos = -1_000,
            sceneRenderNanos = (maxStoredMicros.toLong() + 100) * 1_000,
            submitNanos = Long.MAX_VALUE,
        )

        assertTrue(stats.rendered)
        assertEquals(0, stats.acquireDrawableMicros)
        assertEquals(maxStoredMicros, stats.sceneRenderMicros)
        assertEquals(maxStoredMicros, stats.submitMicros)
    }
}
