package io.hapticlabs.hapticlabsplayer

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FileResolutionTest {

    private lateinit var context: Context
    private lateinit var hapticlabsPlayer: HapticlabsPlayer

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        hapticlabsPlayer = HapticlabsPlayer(context)
    }

    /** @return Whether [startPlayback] calls the completion callback passed to it in time */
    private fun completes(startPlayback: (completionCallback: () -> Unit) -> Unit): Boolean {
        val latch = CountDownLatch(1)
        startPlayback { latch.countDown() }
        return latch.await(5, TimeUnit.SECONDS)
    }

    private fun readAsset(assetName: String): ByteArray {
        return context.assets.open(assetName).use { it.readBytes() }
    }

    @Test
    fun resolveFile_rootLevelAsset_extractsItToTheFilesystem() {
        val resolved = hapticlabsPlayer.resolveFile("test.mp4")

        assertArrayEquals(readAsset("test.mp4"), resolved?.readBytes())
    }

    @Test
    fun resolveFile_assetExtractedBeforeAppUpdate_extractsItAgain() {
        hapticlabsPlayer.resolveFile("test.mp4")?.apply {
            writeText("outdated")
            setLastModified(0)
        }

        val resolved = hapticlabsPlayer.resolveFile("test.mp4")

        assertArrayEquals(readAsset("test.mp4"), resolved?.readBytes())
    }

    @Test
    fun resolveFile_absolutePath_returnsThatFile() {
        val file = File(context.cacheDir, "resolveFileTest.ogg").apply { writeText("ogg") }

        assertEquals(file.absolutePath, hapticlabsPlayer.resolveFile(file.absolutePath)?.absolutePath)
    }

    @Test
    fun resolveFile_missingAsset_returnsNull() {
        assertNull(hapticlabsPlayer.resolveFile("missing/main.ogg"))
    }

    @Test
    fun resolveFile_missingAbsolutePath_returnsNull() {
        assertNull(hapticlabsPlayer.resolveFile(File(context.cacheDir, "missing.ogg").absolutePath))
    }

    @Test
    fun loadHLA_asset_findsAudioInTheSameAssetDirectory() {
        assumeTrue(hapticlabsPlayer.hapticsCapabilities.hapticSupportLevel > 0)
        var loadedHLA: LoadedHLA? = null

        hapticlabsPlayer.loadHLA("legacy/lvl1/main.hla") { loadedHLA = it }

        assertEquals(1, loadedHLA?.audio?.size)
    }

    @Test
    fun play_legacyAssetDirectory_completes() {
        assumeTrue(hapticlabsPlayer.hapticsCapabilities.hapticSupportLevel in 1..2)

        assertTrue(completes { hapticlabsPlayer.play("legacy", it) })
    }

    @Test
    fun playOGG_missingFile_completes() {
        assertTrue(completes { hapticlabsPlayer.playOGG("missing/main.ogg", it) })
    }

    @Test
    fun playHLA_missingFile_completes() {
        assertTrue(completes { hapticlabsPlayer.playHLA("missing/main.hla", it) })
    }

    @Test
    fun playHAC_missingFile_completes() {
        assertTrue(completes { hapticlabsPlayer.playHAC("missing.hac", it) })
    }

    @Test
    fun preloadOGG_missingFile_doesNotThrow() {
        hapticlabsPlayer.preloadOGG("missing/main.ogg")
    }
}
