package io.hapticlabs.hapticlabsplayer

import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.IOException

/**
 * The first decodable audio track of a media file or stream, decoded to interleaved 16-bit PCM.
 *
 * Open it with [open], call [decodeInto] once, then [close] it.
 */
class DecodedAudioTrack private constructor(
    private val extractor: MediaExtractor,
    private val decoder: MediaCodec,
    private val trackDurationUs: Long?
) : AutoCloseable {
    private val bufferInfo = MediaCodec.BufferInfo()
    private var isInputDone = false
    private var lastSampleTimeUs = 0L
    private var stalledDequeueCount = 0

    /** An output buffer dequeued while waiting for the output format, not yet consumed. */
    private var pendingOutputIndex: Int? = null

    /** Sample rate of the decoded PCM in Hz. */
    val sampleRate: Int

    /** Number of interleaved channels in the decoded PCM. */
    val channelCount: Int

    init {
        decoder.start()
        // Decoders may change the sample rate or channel count, for instance for HE-AAC, so the
        // PCM format is only known once the decoder announces it
        pendingOutputIndex = awaitOutputFormat()
        val outputFormat = decoder.outputFormat
        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    }

    /**
     * Decodes the whole track, passing the PCM to [consumePcm] chunk by chunk.
     *
     * Blocks until the end of the track, so call it off the main thread.
     *
     * @throws IOException if the track ends early, for instance because a stream broke off
     */
    fun decodeInto(consumePcm: (ByteArray) -> Unit) {
        var outputIndex = pendingOutputIndex ?: dequeueOutput()
        pendingOutputIndex = null
        while (true) {
            if (outputIndex >= 0) {
                val outputBuffer = decoder.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val pcm = ByteArray(bufferInfo.size)
                    outputBuffer.get(pcm)
                    consumePcm(pcm)
                }
                decoder.releaseOutputBuffer(outputIndex, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    checkTrackIsComplete()
                    return
                }
            }
            outputIndex = dequeueOutput()
        }
    }

    override fun close() {
        decoder.release()
        extractor.release()
    }

    /**
     * Feeds input until the decoder announces its output format or returns output.
     *
     * @return The index of the output buffer returned before the format announcement, if any
     */
    private fun awaitOutputFormat(): Int? {
        while (true) {
            val outputIndex = dequeueOutput()
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                return null
            }
            if (outputIndex >= 0) {
                return outputIndex
            }
        }
    }

    /**
     * Queues the next input sample if the decoder has room for it, then dequeues output.
     *
     * @return An output buffer index, or one of the `MediaCodec.INFO_` constants
     */
    private fun dequeueOutput(): Int {
        if (!isInputDone) {
            queueInput()
        }
        val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)

        // Without input left, a decoder that keeps returning nothing never will
        if (isInputDone && outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (++stalledDequeueCount > MAX_STALLED_DEQUEUE_COUNT) {
                throw IOException("Decoder stalled at the end of the track")
            }
        } else {
            stalledDequeueCount = 0
        }
        return outputIndex
    }

    private fun queueInput() {
        val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex < 0) {
            return
        }
        val inputBuffer = decoder.getInputBuffer(inputIndex)
            ?: throw IllegalStateException("Decoder returned no input buffer for index $inputIndex")
        val sampleSize = extractor.readSampleData(inputBuffer, 0)
        if (sampleSize < 0) {
            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            isInputDone = true
        } else {
            lastSampleTimeUs = extractor.sampleTime
            decoder.queueInputBuffer(inputIndex, 0, sampleSize, lastSampleTimeUs, 0)
            extractor.advance()
        }
    }

    /**
     * Streams that break off look like they ended, so compare against the announced duration.
     */
    private fun checkTrackIsComplete() {
        if (trackDurationUs != null && lastSampleTimeUs + TRUNCATION_TOLERANCE_US < trackDurationUs) {
            throw IOException(
                "Audio track ended at ${lastSampleTimeUs / 1000} ms of ${trackDurationUs / 1000} ms"
            )
        }
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val MAX_STALLED_DEQUEUE_COUNT = 500

        /** Generous, as some formats only estimate their duration. */
        private const val TRUNCATION_TOLERANCE_US = 1_000_000L

        /**
         * Opens the first audio track of [source] that this device can decode.
         *
         * Blocks while opening the source, so call it off the main thread for streams.
         *
         * @param source A file path or an http(s) URL. URLs are streamed, not downloaded
         * @return The decoded track, or null if [source] has no decodable audio track
         * @throws IOException if [source] can't be read
         */
        fun open(source: String): DecodedAudioTrack? = open { it.setDataSource(source) }

        /**
         * Opens the first audio track of [source] that this device can decode.
         *
         * @param source The media, such as an asset stored uncompressed. It can be closed once
         * this returns
         * @return The decoded track, or null if [source] has no decodable audio track
         * @throws IOException if [source] can't be read
         */
        fun open(source: AssetFileDescriptor): DecodedAudioTrack? = open { it.setDataSource(source) }

        private fun open(setDataSource: (MediaExtractor) -> Unit): DecodedAudioTrack? {
            val extractor = MediaExtractor()
            try {
                setDataSource(extractor)
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (trackIndex in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(trackIndex)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) {
                        continue
                    }
                    val decoderName = codecList.findDecoderForFormat(format) ?: continue

                    extractor.selectTrack(trackIndex)
                    val trackDurationUs =
                        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
                    val decoder = MediaCodec.createByCodecName(decoderName)
                    try {
                        decoder.configure(format, null, null, 0)
                        return DecodedAudioTrack(extractor, decoder, trackDurationUs)
                    } catch (e: Exception) {
                        decoder.release()
                        throw e
                    }
                }
            } catch (e: Exception) {
                extractor.release()
                throw e
            }
            extractor.release()
            return null
        }
    }
}
