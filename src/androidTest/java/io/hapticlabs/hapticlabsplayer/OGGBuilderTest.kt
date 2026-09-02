package io.hapticlabs.hapticlabsplayer

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OGGBuilderTest {

    private lateinit var context: Context
    private lateinit var testContext: Context
    private lateinit var hapticlabsPlayer: HapticlabsPlayer

    @Before
    fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        testContext = instrumentation.context
        hapticlabsPlayer = HapticlabsPlayer(context)
    }

    private fun readAssetBytes(fileName: String): ByteArray {
        return testContext.assets.open(fileName).use { it.readBytes() }
    }

    private fun copyAssetToFile(fileName: String, outputFile: File) {
        testContext.assets.open(fileName).use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    @Test
    fun oggBuilder_hapticsOnlyOGG() {
        val latch = CountDownLatch(1)
        val habBuffer = readAssetBytes("sine8sfreqfade.hab")
        val expectedDurationSec = 1f

        val outputDir = context.externalCacheDir ?: context.cacheDir
        val oggPath = File(outputDir, "sine8sfreqfade.ogg")

        hapticlabsPlayer.generateOGGFromHAB(habBuffer, null, expectedDurationSec, oggPath) {
            println("OGG generated at: ${oggPath.absolutePath}")
            latch.countDown()
        }

        assertTrue("OGG generation timed out", latch.await(5, TimeUnit.SECONDS))
        
        assertValidOgg(oggPath)
        assertOggDuration(oggPath, (expectedDurationSec * 1000).toLong())
        // For haptics only, it might be 1 (mono haptics) or more depending on OGGBuilder impl
        assertOggChannelCount(oggPath, 3)
        assertOggMetadata(oggPath, "Unknown", "Unknown")
    }

    @Test
    fun oggBuilder_videoAndHapticsOGG() {
        val latch = CountDownLatch(1)
        val habBuffer = readAssetBytes("sine8sfreqfade.hab")
        val habDurationSec = 1f
        val customTitle = "Test Title"
        val customAlbum = "Test Album"

        val mediaFile = File(context.cacheDir, "test.mp4")
        copyAssetToFile("test.mp4", mediaFile)

        val outputDir = context.getExternalFilesDir(null)
        val oggPath = File(outputDir, "sine8sfreqfade_with_media.ogg")

        OGGBuilder.createOggWithMedia(
            oggPath,
            mediaFile,
            habBuffer,
            habDurationSec,
            {
                println("OGG generated at: ${oggPath.absolutePath}")
                latch.countDown()
            },
            quality = 0.5f,
            title = customTitle,
            album = customAlbum
        )

        assertTrue("OGG generation timed out", latch.await(5, TimeUnit.SECONDS))
        
        assertValidOgg(oggPath)
        assertOggDuration(oggPath, (4 * 1000).toLong())
        // Media (2 channels) + Haptics (1 channel) = 3 channels
        assertOggChannelCount(oggPath, 3) 
        assertOggMetadata(oggPath, customTitle, customAlbum)
    }

    private fun assertValidOgg(file: File) {
        assertTrue("OGG file should exist", file.exists())
        assertTrue("OGG file should not be empty", file.length() > 0)

        file.inputStream().use { input ->
            val header = ByteArray(4)
            val read = input.read(header)
            assertEquals("Could not read OGG header", 4, read)
            assertEquals("File should start with 'OggS' magic bytes", "OggS", String(header))
        }
    }

    private fun assertOggChannelCount(file: File, expectedChannels: Int) {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertTrue("OGG should have at least one track", extractor.trackCount > 0)
            val format = extractor.getTrackFormat(0)
            val channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            assertEquals("OGG channel count mismatch", expectedChannels, channels)
        } finally {
            extractor.release()
        }
    }

    private fun assertOggDuration(file: File, expectedDurationMs: Long, toleranceMs: Long = 200L) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0L
            
            val diff = Math.abs(durationMs - expectedDurationMs)
            assertTrue("OGG duration mismatch: expected around ${expectedDurationMs}ms, got ${durationMs}ms", 
                diff <= toleranceMs)
        } finally {
            retriever.release()
        }
    }

    private fun assertOggMetadata(file: File, expectedTitle: String, expectedAlbum: String) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
            
            assertEquals("Title metadata mismatch", expectedTitle, title)
            assertEquals("Album metadata mismatch", expectedAlbum, album)
        } finally {
            retriever.release()
        }
    }
}
