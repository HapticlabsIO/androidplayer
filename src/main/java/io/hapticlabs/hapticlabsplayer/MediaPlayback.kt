package io.hapticlabs.hapticlabsplayer

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.SoundPool
import android.os.Build
import android.util.Log

/**
 * A playback of a media file, prepared so that it can start right away.
 */
internal sealed interface MediaPlayback {
    /** Starts the playback, unless it was stopped. It ends by itself at the end of the file */
    fun start()

    /** Stops the playback, or keeps it from starting, and releases what it holds */
    fun stop()
}

/**
 * Plays a sound that [soundPool] loaded. It starts with little latency, but only at the start of
 * the sound.
 */
internal class SoundPoolPlayback(
    private val soundPool: SoundPool,
    private val soundId: Int,
    /** From 0 for silence to 1 for the sound as it is */
    private val volume: Float
) : MediaPlayback {
    private var streamId = 0

    override fun start() {
        streamId = soundPool.play(soundId, volume, volume, 1, 0, 1f)
    }

    override fun stop() {
        soundPool.stop(streamId)
    }
}

/**
 * Plays a media file with MediaPlayers playing it together, such as one for its haptic channels and
 * one for its audio. It can start anywhere in the file, but it takes a while until it's heard.
 */
internal class MediaPlayerPlayback private constructor(
    private val players: List<MediaPlayer>
) : MediaPlayback {
    private var isReleased = false

    /** Where each of [players] was when it started, in milliseconds since the start of the file */
    private var startPositions = emptyList<Int>()

    /**
     * Whether the started playback is heard by now, or ended without being heard. A player's
     * position stays where it started until its audio comes out.
     */
    val isHeard: Boolean
        get() = isReleased || players.zip(startPositions).all { (player, startPosition) ->
            try {
                !player.isPlaying || player.currentPosition > startPosition
            } catch (e: IllegalStateException) {
                // Failed
                true
            }
        }

    override fun start() {
        if (!isReleased) {
            startPositions = players.map { it.currentPosition }
            players.forEach { it.start() }
        }
    }

    override fun stop() {
        if (!isReleased) {
            isReleased = true
            players.forEach { it.release() }
        }
    }

    companion object {
        private const val TAG = "MediaPlayerPlayback"

        /**
         * Prepares [players], whose data source is set, to play from [startPosition], in
         * milliseconds since the start of their file. Calls the players' listeners from the thread
         * that created them, or the main thread if that has no looper.
         *
         * @param durationScale What to multiply the duration of the file with, keeping its pitch,
         * such as 2 to play it half as fast
         * @param onPrepared Called with the playback once the players are ready to start, or with
         * null if one of them fails, after releasing them
         */
        fun prepare(
            players: List<MediaPlayer>,
            startPosition: Long,
            durationScale: Float,
            onPrepared: (MediaPlayerPlayback?) -> Unit
        ) {
            val playback = MediaPlayerPlayback(players)
            var unpreparedCount = players.size
            var isReported = false
            val report = { prepared: MediaPlayerPlayback? ->
                if (!isReported) {
                    isReported = true
                    onPrepared(prepared)
                }
            }
            val onReady = {
                unpreparedCount--
                if (unpreparedCount == 0) {
                    report(playback)
                }
            }

            for (player in players) {
                // Seeking takes a while too, so it's part of preparing
                player.setOnPreparedListener {
                    if (startPosition == 0L) onReady() else seekPrecisely(it, startPosition)
                }
                player.setOnSeekCompleteListener { onReady() }
                player.setOnCompletionListener { playback.stop() }
                player.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer failed: $what, $extra")
                    playback.stop()
                    report(null)
                    true
                }
                // Before preparing, as setting a speed afterwards starts the player
                if (durationScale != 1f) {
                    player.playbackParams = PlaybackParams().setSpeed(1 / durationScale)
                }
                player.prepareAsync()
            }
        }

        /**
         * Seeks a prepared [player] to [position] in milliseconds. Seeks to the closest frame where
         * the SDK version allows, rather than to the closest sync frame.
         */
        private fun seekPrecisely(player: MediaPlayer, position: Long) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        }
    }
}
