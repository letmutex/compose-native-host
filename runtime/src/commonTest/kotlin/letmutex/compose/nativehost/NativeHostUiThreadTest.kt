package letmutex.compose.nativehost

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeHostUiThreadTest {
    @Test
    fun dispatchRunsOnUiThread() {
        val uiThread = NativeHostUiThread("test-dispatch-runs-on-ui-thread")
        try {
            val ranOnUiThread = AtomicBoolean(false)
            val completed = CountDownLatch(1)

            uiThread.dispatch(
                Runnable {
                    ranOnUiThread.set(uiThread.isUiThread())
                    completed.countDown()
                },
            )

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(ranOnUiThread.get())
        } finally {
            uiThread.close()
        }
    }

    @Test
    fun dispatchRunsInlineWhenAlreadyOnUiThread() {
        val uiThread = NativeHostUiThread("test-dispatch-runs-inline")
        try {
            val ranInline = AtomicBoolean(false)

            uiThread.callOnUiThread {
                uiThread.dispatch(
                    Runnable {
                        ranInline.set(uiThread.isUiThread())
                    },
                )
                assertTrue(ranInline.get())
            }

            assertTrue(ranInline.get())
        } finally {
            uiThread.close()
        }
    }

    @Test
    fun callOnUiThreadReturnsValueFromUiThread() {
        val uiThread = NativeHostUiThread("test-invoke-returns-value")
        try {
            val threadName = uiThread.callOnUiThread { Thread.currentThread().name }
            assertEquals("test-invoke-returns-value", threadName)
        } finally {
            uiThread.close()
        }
    }

    @Test
    fun callOnUiThreadFailsAfterClose() {
        val uiThread = NativeHostUiThread("test-invoke-fails-after-close")
        uiThread.ensureStarted()
        uiThread.close()

        assertFailsWith<IllegalStateException> {
            uiThread.callOnUiThread { "should not run" }
        }
    }
}
