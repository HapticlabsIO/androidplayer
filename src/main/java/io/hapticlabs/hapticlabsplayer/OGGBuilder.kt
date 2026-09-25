package io.hapticlabs.hapticlabsplayer

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

class OGGBuilder(
    val habBuffer: ByteArray,
    val mediaSampleRate: UInt,
    val mediaChannelCount: UShort,
    val habDuration: Float,
    val oggFile: File,
    val quality: Float = 0.4f,
    val title: String = "Unknown",
    val album: String = "Unknown"
) : AutoCloseable {
    private var isDeinitialized = false
    private val pointerToOggGenerator: Long

    companion object {
        private const val TAG = "OGGBuilder"

        init {
            System.loadLibrary("habGen")
        }

        /**
         * Writes an OGG with the haptics of a .hab and the audio of a media file, if any.
         *
         * Blocks until the OGG is complete, so call it off the main thread.
         *
         * @param mediaSource Path or http(s) URL of the media whose first audio track to include,
         * or null for haptics only. URLs are streamed, not downloaded
         * @throws java.io.IOException if the media can't be read
         */
        fun writeOggWithMedia(
            oggFile: File,
            mediaSource: String?,
            habBuffer: ByteArray,
            habDuration: Float,
            quality: Float = 0.4f,
            title: String = "Unknown",
            album: String = "Unknown"
        ) {
            mediaSource?.let { DecodedAudioTrack.open(it) }.use { audioTrack ->
                writeOggWithAudio(oggFile, audioTrack, habBuffer, habDuration, quality, title, album)
            }
        }

        /**
         * Writes an OGG with the haptics of a .hab and an audio track, if any.
         *
         * Blocks until the OGG is complete, so call it off the main thread.
         *
         * @param audioTrack The audio to include, or null for haptics only. It's decoded to its
         * end, but left open
         * @throws java.io.IOException if the audio can't be read
         */
        fun writeOggWithAudio(
            oggFile: File,
            audioTrack: DecodedAudioTrack?,
            habBuffer: ByteArray,
            habDuration: Float,
            quality: Float = 0.4f,
            title: String = "Unknown",
            album: String = "Unknown"
        ) {
            // Without audio, fall back to 48 kHz stereo silence next to the haptics
            OGGBuilder(
                habBuffer,
                audioTrack?.sampleRate?.toUInt() ?: 48000u,
                audioTrack?.channelCount?.toUShort() ?: 2u,
                habDuration,
                oggFile,
                quality,
                title,
                album
            ).use { oggBuilder ->
                audioTrack?.decodeInto(oggBuilder::pushAudioSamples)
            }
        }

        /**
         * Writes an OGG like [writeOggWithMedia], but in the background.
         *
         * @param completionCallback Called on the main thread once the OGG is complete. Not
         * called if writing fails
         */
        fun createOggWithMedia(
            oggPath: File,
            mediaPath: File?,
            habBuffer: ByteArray,
            habDuration: Float,
            completionCallback: () -> Unit,
            quality: Float = 0.4f,
            title: String = "Unknown",
            album: String = "Unknown"
        ) {
            thread {
                try {
                    writeOggWithMedia(
                        oggPath,
                        mediaPath?.absolutePath,
                        habBuffer,
                        habDuration,
                        quality,
                        title,
                        album
                    )
                    Handler(Looper.getMainLooper()).post(completionCallback)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write OGG: $oggPath", e)
                }
            }
        }
    }

    init {
        pointerToOggGenerator = newOGGGenerator(
            oggFile.absolutePath,
            habBuffer,
            habDuration,
            1, // 48 kHz / 1 = 48 kHz output sample rate
            quality,
            mediaSampleRate.toInt(),
            mediaChannelCount.toShort(),
            title,
            album
        )
    }

    override fun close(): Unit {
        if (isDeinitialized) {
            // Already deinitialized,warn and return
            println("OGGBuilder already deinitialized!")
            return
        }
        isDeinitialized = true
        deleteOGGGenerator(
            pointerToOggGenerator
        )
    }

    fun pushAudioSamples(buffer: ByteArray): Unit {
        pushDataToOGGGenerator(
            pointerToOggGenerator,
            buffer
        )
    }

    private external fun newOGGGenerator(
        outputPath: String,
        habBuffer: ByteArray,
        habDuration: Float,
        hab48kHzDivider: Int,
        quality: Float,
        mediaSampleRate: Int,
        mediaChannelCount: Short,
        title: String,
        album: String
    ): Long

    private external fun deleteOGGGenerator(pointer: Long): Unit

    private external fun pushDataToOGGGenerator(pointer: Long, buffer: ByteArray): Unit
}