package io.hapticlabs.hapticlabsplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlayLoadedHLATest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val player = HapticlabsPlayer(instrumentation.targetContext)
    private val audioPath = silentWav(instrumentation.targetContext, "playLoadedHLATest.wav", 1000).absolutePath

    /** @return Whether playing silence of [duration] ms from [startPosition] completes within [timeoutMs] */
    private fun completesWithin(timeoutMs: Long, duration: Long, startPosition: Long): Boolean {
        val latch = CountDownLatch(1)
        val silence = LoadedHLA(emptyList(), null, emptyList(), duration)
        instrumentation.runOnMainSync {
            player.playLoadedHLA(silence, startPosition, 1f) { latch.countDown() }
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun audioOnly(path: String, duration: Long = 1000) =
        LoadedHLA(emptyList(), LoadedAudio(path), emptyList(), duration)

    /**
     * @return The callbacks that playing [hla] from [startPosition] called, in order, once it
     * completed
     */
    private fun callbacksOf(hla: LoadedHLA, startPosition: Long = 0, audioDurationScale: Float = 1f): List<String> {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            player.playLoadedHLA(hla, startPosition, 1f, audioDurationScale, { calls += START }) {
                calls += COMPLETION
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return calls.toList()
    }

    @Test
    fun playLoadedHLA_fromAStartPosition_completesAfterTheRest() {
        assertTrue(completesWithin(timeoutMs = 1000, duration = 2000, startPosition = 1500))
    }

    @Test
    fun playLoadedHLA_fromTheStart_completesAfterItsDuration() {
        assertFalse(completesWithin(timeoutMs = 1000, duration = 2000, startPosition = 0))
    }

    @Test
    fun playLoadedHLA_fromBeyondItsEnd_completesRightAway() {
        assertTrue(completesWithin(timeoutMs = 500, duration = 1000, startPosition = 5000))
    }

    @Test
    fun playLoadedHLA_withAudio_startsThenCompletes() {
        assertEquals(listOf(START, COMPLETION), callbacksOf(audioOnly(audioPath)))
    }

    @Test
    fun playLoadedHLA_withAudioFromAStartPosition_startsThenCompletes() {
        assertEquals(listOf(START, COMPLETION), callbacksOf(audioOnly(audioPath), startPosition = 500))
    }

    @Test
    fun playLoadedHLA_withPreloadedAudio_startsThenCompletes() {
        instrumentation.runOnMainSync { player.preloadAudio(audioPath) }
        assertTrue(becomesTrueWithin(2000) { player.isLoadedToSoundPool(audioPath) })

        assertEquals(listOf(START, COMPLETION), callbacksOf(audioOnly(audioPath)))
    }

    @Test
    fun playLoadedHLA_withStretchedAudioFromAStartPosition_startsThenCompletes() {
        assertEquals(
            listOf(START, COMPLETION),
            callbacksOf(audioOnly(audioPath, 2000), startPosition = 500, audioDurationScale = 2f)
        )
    }

    @Test
    fun playLoadedHLA_withStretchedPreloadedAudio_startsThenCompletes() {
        instrumentation.runOnMainSync { player.preloadAudio(audioPath) }
        assertTrue(becomesTrueWithin(2000) { player.isLoadedToSoundPool(audioPath) })

        assertEquals(listOf(START, COMPLETION), callbacksOf(audioOnly(audioPath, 500), audioDurationScale = 0.5f))
    }

    @Test
    fun playLoadedHLA_withAudioDurationScaleOutOfRange_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            player.playLoadedHLA(audioOnly(audioPath), 0, 1f, 4f, {}) {}
        }
    }

    @Test
    fun playLoadedHLA_withUnreadableMedia_startsThenCompletes() {
        val unreadablePath = File(instrumentation.targetContext.cacheDir, "unreadable.ogg").apply {
            writeText("not media")
        }.absolutePath
        val hla = LoadedHLA(
            emptyList(),
            LoadedAudio(unreadablePath),
            listOf(UncompressedOGGFile(unreadablePath, 0)),
            100
        )

        assertEquals(listOf(START, COMPLETION), callbacksOf(hla))
    }

    @Test
    fun playLoadedHLA_abortedWhilePreparing_completesWithoutStarting() {
        val calls = Collections.synchronizedList(mutableListOf<String>())

        instrumentation.runOnMainSync {
            val abort = player.playLoadedHLA(audioOnly(audioPath), 0, 1f, 1f, { calls += START }) {
                calls += COMPLETION
            }
            abort()
        }
        // Until it would have started
        Thread.sleep(500)

        assertEquals(listOf(COMPLETION), calls.toList())
    }

    @Test
    fun playLoadedHLA_abortedWhileAudioPlays_completes() {
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var abort: () -> Unit = {}
        instrumentation.runOnMainSync {
            abort = player.playLoadedHLA(audioOnly(audioPath, 10_000), 0, 1f, 1f, { started.countDown() }) {
                completed.countDown()
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        instrumentation.runOnMainSync { abort() }

        assertTrue(completed.await(1, TimeUnit.SECONDS))
    }

    private companion object {
        const val START = "start"
        const val COMPLETION = "completion"
    }
}
