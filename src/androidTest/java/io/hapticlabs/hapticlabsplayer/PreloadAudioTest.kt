package io.hapticlabs.hapticlabsplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreloadAudioTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val player = HapticlabsPlayer(instrumentation.targetContext)
    private val audioPath = silentWav(instrumentation.targetContext, "preloadAudioTest.wav", 1000).absolutePath

    /** @return Whether SoundPool loads the audio in time */
    private fun loads(): Boolean = becomesTrueWithin(2000) { player.isLoadedToSoundPool(audioPath) }

    @Test
    fun preloadAudio_shortAudio_loadsItIntoSoundPool() {
        instrumentation.runOnMainSync { player.preloadAudio(audioPath) }

        assertTrue(loads())
    }

    @Test
    fun unloadAudio_preloadedAudio_unloadsIt() {
        instrumentation.runOnMainSync { player.preloadAudio(audioPath) }
        assertTrue(loads())
        var isLoaded = true

        instrumentation.runOnMainSync {
            player.unloadAudio(audioPath)
            isLoaded = player.isLoadedToSoundPool(audioPath)
        }

        assertFalse(isLoaded)
    }

    @Test
    fun preloadAudio_afterUnloadAll_isNotLoadedBeforeItLoads() {
        instrumentation.runOnMainSync { player.preloadAudio(audioPath) }
        assertTrue(loads())
        var isLoadedRightAway = true

        // Loading completes on the main thread, so not before this returns
        instrumentation.runOnMainSync {
            player.unloadAll()
            player.preloadAudio(audioPath)
            isLoadedRightAway = player.isLoadedToSoundPool(audioPath)
        }

        assertFalse(isLoadedRightAway)
    }

    @Test
    fun preloadAudio_missingFile_doesNotThrow() {
        player.preloadAudio("missing/audio.wav")
    }
}
