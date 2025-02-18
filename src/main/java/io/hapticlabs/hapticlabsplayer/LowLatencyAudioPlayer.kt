package io.hapticlabs.hapticlabsplayer

import android.content.Context
import android.media.AudioTrack
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import java.io.ByteArrayOutputStream
import java.io.IOException

class LowLatencyAudioPlayer(private val filePath: String, private val context: Context) {
    private var audioTrack: AudioTrack? = null
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var info: MediaCodec.BufferInfo? = null
    private var isEOS = false

    /**
     * Preload the audio data from the file. This sets up the MediaExtractor and
     * MediaCodec and prepares the AudioTrack for playback.
     */
    fun preload() {
        val uncompressedPath = getUncompressedPath(filePath, context)
        extractor = MediaExtractor()
        try {
            extractor?.setDataSource(uncompressedPath)
            var format: MediaFormat? = null

            // Find the first audio track in the file
            for (i in 0 until extractor!!.trackCount) {
                format = extractor!!.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    extractor!!.selectTrack(i)
                    codec = MediaCodec.createDecoderByType(mime)
                    codec?.configure(format, null, null, 0)
                    break
                }
            }

            if (codec == null) {
                return // No suitable codec found
            }

            codec?.start()

            info = MediaCodec.BufferInfo()

            // Set up AudioTrack
            val sampleRate = format!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val channelConfig =
                if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            // Load the entire audio file into the AudioTrack
            val byteArrayOutputStream = ByteArrayOutputStream()

            while (!isEOS) {
                val inIndex = codec!!.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val buffer = codec!!.getInputBuffer(inIndex)
                    if (buffer != null) {
                        val sampleSize = extractor!!.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec!!.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isEOS = true
                        } else {
                            codec!!.queueInputBuffer(
                                inIndex,
                                0,
                                sampleSize,
                                extractor!!.sampleTime,
                                0
                            )
                            extractor!!.advance()
                        }
                    }
                }


                when (val outIndex = codec!!.dequeueOutputBuffer(info!!, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> {
                        val outBuffer = codec!!.getOutputBuffer(outIndex)
                        val chunk = ByteArray(info!!.size)
                        if (outBuffer != null) {
                            outBuffer.get(chunk)
                            outBuffer.clear()
                            // Copy the chunk into the full buffer
                            try {
                                byteArrayOutputStream.write(chunk)
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                            codec!!.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }

                if (info!!.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            }
            codec!!.stop()
            codec!!.release()
            extractor!!.release()

            val fullBuffer = byteArrayOutputStream.toByteArray()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(fullBuffer.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(fullBuffer, 0, fullBuffer.size)

        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * Trigger playback of the audio.
     */
    fun playAudio() {
        audioTrack?.play()
    }
}