package io.hapticlabs.hapticlabsplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hapticlabs.hapticlabsplayer.core.LoadedAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlayLoadedHLATest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val player = HapticlabsPlayer(instrumentation.targetContext)

    private fun silence(duration: Long) = LoadedHLA(emptyList(), null, emptyList(), duration)

    /** @return Whether playing [hla] completes within [timeoutMs] */
    private fun completesWithin(timeoutMs: Long, hla: LoadedHLA): Boolean {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            player.playLoadedHLA(hla) { latch.countDown() }
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    @Test
    fun playLoadedHLA_completesAfterItsDuration() {
        assertFalse(completesWithin(timeoutMs = 500, silence(1000)))
        assertTrue(completesWithin(timeoutMs = 1500, silence(1000)))
    }

    @Test
    fun playLoadedHLA_withUnreadableAudio_completesAfterItsDuration() {
        val unreadablePath = File(instrumentation.targetContext.cacheDir, "unreadable.wav").apply {
            writeText("not audio")
        }.absolutePath

        assertTrue(completesWithin(timeoutMs = 1000, LoadedHLA(emptyList(), LoadedAudio(unreadablePath), emptyList(), 100)))
    }

    @Test
    fun playLoadedHLA_aborted_completesOnceRightAway() {
        val completions = AtomicInteger()

        instrumentation.runOnMainSync {
            val abort = player.playLoadedHLA(silence(500)) { completions.incrementAndGet() }
            abort()
        }
        // Until it would have completed by itself
        Thread.sleep(1000)

        assertEquals(1, completions.get())
    }
}
