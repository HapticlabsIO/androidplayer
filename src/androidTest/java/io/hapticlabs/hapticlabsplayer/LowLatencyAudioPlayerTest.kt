package io.hapticlabs.hapticlabsplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LowLatencyAudioPlayerTest {

    private lateinit var audioPlayer: LowLatencyAudioPlayer

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioFile = requireNotNull(HapticlabsPlayer(context).resolveFile("legacy/lvl2/audio.wav"))
        audioPlayer = LowLatencyAudioPlayer(audioFile, context).apply { preload() }
    }

    @After
    fun tearDown() {
        audioPlayer.release()
    }

    /** @return Whether the audio plays to its end in time */
    private fun playsToItsEnd(): Boolean {
        val latch = CountDownLatch(1)
        audioPlayer.setPlaybackEndedCallback { latch.countDown() }
        audioPlayer.playAudio()
        return latch.await(5, TimeUnit.SECONDS)
    }

    @Test
    fun playAudio_afterPlayingToItsEnd_playsAgain() {
        assertTrue(playsToItsEnd())

        assertTrue(playsToItsEnd())
    }

    @Test
    fun playAudio_afterStopping_playsAgain() {
        audioPlayer.playAudio()
        audioPlayer.stopPlayback()

        assertTrue(playsToItsEnd())
    }
}
