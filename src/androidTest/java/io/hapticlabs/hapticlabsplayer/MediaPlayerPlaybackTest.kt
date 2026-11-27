package io.hapticlabs.hapticlabsplayer

import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MediaPlayerPlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val audioFile = silentWav(instrumentation.targetContext, "mediaPlayerPlaybackTest.wav", 2000)

    /**
     * @param player Plays the playback, created for [file] unless passed
     * @return What preparing a playback of [file] from [startPosition] reported
     */
    private fun prepare(
        file: File,
        startPosition: Long,
        durationScale: Float = 1f,
        player: MediaPlayer = MediaPlayer()
    ): MediaPlayerPlayback? {
        val latch = CountDownLatch(1)
        var prepared: MediaPlayerPlayback? = null
        instrumentation.runOnMainSync {
            player.setDataSource(file.absolutePath)
            MediaPlayerPlayback.prepare(listOf(player), startPosition, durationScale) {
                prepared = it
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return prepared
    }

    @Test
    fun isHeard_rightAfterStarting_isFalse() {
        val playback = requireNotNull(prepare(audioFile, 0))
        var isHeard = true

        instrumentation.runOnMainSync {
            playback.start()
            isHeard = playback.isHeard
        }

        playback.stop()
        assertFalse(isHeard)
    }

    @Test
    fun isHeard_fromAStartPosition_becomesTrueOnceItPlays() {
        val playback = requireNotNull(prepare(audioFile, 500))

        instrumentation.runOnMainSync { playback.start() }

        assertTrue(becomesTrueWithin(1000) { playback.isHeard })
        playback.stop()
    }

    @Test
    fun isHeard_fromBeyondTheEnd_becomesTrue() {
        val playback = requireNotNull(prepare(audioFile, 5000))

        instrumentation.runOnMainSync { playback.start() }

        assertTrue(becomesTrueWithin(1000) { playback.isHeard })
        playback.stop()
    }

    @Test
    fun start_withADurationScale_playsAtTheSpeedItScales() {
        val player = MediaPlayer()
        val playback = requireNotNull(prepare(audioFile, 0, durationScale = 0.5f, player = player))
        instrumentation.runOnMainSync { playback.start() }
        assertTrue(becomesTrueWithin(1000) { playback.isHeard })

        var heardPosition = 0
        instrumentation.runOnMainSync { heardPosition = player.currentPosition }
        Thread.sleep(500)
        var laterPosition = 0
        instrumentation.runOnMainSync { laterPosition = player.currentPosition }

        playback.stop()
        // Twice as fast
        assertEquals(1000.0, (laterPosition - heardPosition).toDouble(), 150.0)
    }

    @Test
    fun prepare_unreadableFile_reportsNoPlayback() {
        val unreadableFile = File(instrumentation.targetContext.cacheDir, "unreadable.wav").apply {
            writeText("not audio")
        }

        assertNull(prepare(unreadableFile, 0))
    }
}
