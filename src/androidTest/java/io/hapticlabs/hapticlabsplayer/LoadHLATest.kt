package io.hapticlabs.hapticlabsplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LoadHLATest {

    private val player = HapticlabsPlayer(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before
    fun requireHaptics() {
        // Without haptics, no signal loads
        assumeTrue(player.hapticsCapabilities.hapticSupportLevel > 0)
    }

    private fun load(hlaPath: String): LoadedHLA? {
        var loadedHLA: LoadedHLA? = null
        player.loadHLA(hlaPath) { loadedHLA = it }
        return loadedHLA
    }

    @Test
    fun loadHLA_version3WithAudio_loadsIt() {
        assertEquals("audio.wav", load("hla3/withAudio.hla")?.audio?.path?.let { File(it).name })
    }

    @Test
    fun loadHLA_version3WithoutAudio_loadsTheSignalAlone() {
        val loadedHLA = load("hla3/withoutAudio.hla")

        assertNull(loadedHLA?.audio)
        assertEquals(10L, loadedHLA?.duration)
    }

    @Test
    fun loadHLA_version2WithSeveralAudios_loadsTheFirst() {
        assertEquals("audio.wav", load("hla2/severalAudios.hla")?.audio?.path?.let { File(it).name })
    }

    @Test
    fun loadHLA_unknownVersion_loadsNothing() {
        val loadedHLA = load("hla3/unknownVersion.hla")

        assertNull(loadedHLA?.audio)
        assertEquals(0L, loadedHLA?.duration)
    }
}
