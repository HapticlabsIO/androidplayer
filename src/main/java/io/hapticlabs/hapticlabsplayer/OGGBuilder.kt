package io.hapticlabs.hapticlabsplayer

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

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
        init {
            System.loadLibrary("habGen")
        }

        public
        fun createOggWithMedia(
            oggPath: File,
            mediaPath: File?,
            habBuffer: ByteArray,
            habDuration: Float,
            completionCallback: () -> Unit,
            quality: Float = 0.4f,
            title: String = "Unknown",
            album: String = "Unknown"
        ): Unit {
            // Quicc if media is null
            if (mediaPath == null) {
                OGGBuilder(
                    habBuffer,
                    48000u,
                    2u,
                    habDuration,
                    oggPath,
                    quality,
                    title,
                    album
                ).close()
                completionCallback()
                return
            }

            // Extract audio from media
            val audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(mediaPath.absolutePath)
            val trackCount = audioExtractor.trackCount

            var decoder: MediaCodec? = null
            var decoderFormat: MediaFormat? = null
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

            // Select audio tracks
            for (i in 0 until trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    val decoderType = codecList.findDecoderForFormat(format)
                    if (decoderType != null) {
                        decoder = MediaCodec.createByCodecName(decoderType)
                        decoderFormat = format
                        audioExtractor.selectTrack(i)
                        break
                    }
                }
            }

            val sampleRate =
                decoderFormat?.getInteger(MediaFormat.KEY_SAMPLE_RATE)?.toUInt() ?: 48000u;
            val channelCount =
                decoderFormat?.getInteger(MediaFormat.KEY_CHANNEL_COUNT)?.toUShort() ?: 2u;
            val oggBuilder = OGGBuilder(
                habBuffer,
                sampleRate,
                channelCount,
                habDuration,
                oggPath,
                quality,
                title,
                album
            )

            // Decode
            if (decoder == null) {
                // No audio track found
                // Close the builder directly, no audio needs to be forwarded.
                oggBuilder.close()
                audioExtractor.release()
                completionCallback()
                return
            }
            decoder.setCallback(
                object : MediaCodec.Callback() {
                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        val outputBuffer = codec.getOutputBuffer(index)
                        if (outputBuffer != null && info.size > 0) {
                            // Correctly handle buffer offset and size
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)

                            // Copy data to ByteArray (required by current pushAudioSamples signature)
                            val bytes = ByteArray(info.size)
                            outputBuffer.get(bytes)
                            oggBuilder.pushAudioSamples(bytes)
                        }

                        codec.releaseOutputBuffer(index, false)

                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            oggBuilder.close()
                            codec.stop()
                            codec.release()
                            audioExtractor.release()
                            completionCallback()
                        }
                    }

                    override fun onInputBufferAvailable(
                        codec: MediaCodec,
                        index: Int
                    ) {
                        val inputBuffer = codec.getInputBuffer(index) ?: return

                        val sampleSize = audioExtractor.readSampleData(inputBuffer, 0)
                        val presentationTimeUs = audioExtractor.sampleTime

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        } else {
                            val isEOS = !audioExtractor.advance()
                            val flags = if (isEOS) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            codec.queueInputBuffer(index, 0, sampleSize, presentationTimeUs, flags)
                        }
                    }

                    override fun onOutputFormatChanged(
                        codec: MediaCodec,
                        format: MediaFormat
                    ) {
                    }

                    override fun onError(
                        codec: MediaCodec,
                        e: MediaCodec.CodecException
                    ) {
                        oggBuilder.close()
                        codec.release()
                        audioExtractor.release()
                    }
                }
            )

            decoder.configure(decoderFormat, null, null, 0)
            decoder.start()
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