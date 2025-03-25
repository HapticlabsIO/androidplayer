package io.hapticlabs.hapticlabsplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlin.math.abs

class HapticlabsPlayer(private val context: Context) {
    val hapticSupportLevel = determineHapticSupportLevel()

    private var mediaPlayer: MediaPlayer
    private var handler: Handler? = null

    init {
        mediaPlayer = MediaPlayer()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder().setHapticChannelsMuted(false).build()
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isAvailable = HapticGenerator.isAvailable()
            if (isAvailable) {
                val generator = HapticGenerator.create(mediaPlayer.audioSessionId)
                generator.setEnabled(false)
            }
        }
    }

    protected fun finalize() {
        mediaPlayer.release()
    }

    private fun determineHapticSupportLevel(): Int {
        val vibrator = getVibrator(context)
        val level = if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && AudioManager.isHapticPlaybackSupported()) {
                    3
                } else {
                    2
                }
            } else {
                1
            }
        } else {
            // Vibrator service not available
            0
        }
        return level
    }

    fun play(directoryPath: String, completionCallback: () -> Unit) {
        // Switch by hapticSupportLevel
        when (hapticSupportLevel) {
            0 -> {
                return // Do nothing
            }

            1 -> {
                val path = "$directoryPath/lvl1/main.hla"
                return playHLA(path, completionCallback)
            }

            2 -> {
                val path = "$directoryPath/lvl2/main.hla"
                return playHLA(path, completionCallback)
            }

            3 -> {
                val path = "$directoryPath/lvl3/main.ogg"
                return playOGG(path, completionCallback)
            }
        }
    }

    fun playHLA(path: String, completionCallback: () -> Unit) {
        val data: String

        val uncompressedPath =
            getUncompressedPath(path, context)

        val file = File(uncompressedPath)
        val fis = FileInputStream(file)
        val dataBytes = ByteArray(file.length().toInt())
        fis.read(dataBytes)
        fis.close()
        data = String(dataBytes, StandardCharsets.UTF_8)

        // Parse the file to a JSON
        val gson = Gson()
        val jsonObject = gson.fromJson(data, JsonObject::class.java)

        // Extracting Amplitudes array
        val amplitudesArray = jsonObject.getAsJsonArray("Amplitudes")
        val amplitudes = IntArray(amplitudesArray.size())
        for (i in 0 until amplitudesArray.size()) {
            amplitudes[i] = abs(amplitudesArray[i].asInt)
        }

        // Extracting Repeat value
        val repeat = jsonObject.get("Repeat").asInt

        // Extracting Timings array
        val timingsArray = jsonObject.getAsJsonArray("Timings")
        val timings = LongArray(timingsArray.size())
        for (i in 0 until timingsArray.size()) {
            timings[i] = timingsArray[i].asLong
        }

        val durationMs = jsonObject.get("Duration").asLong

        val audiosArray = jsonObject.getAsJsonArray("Audios")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Prepare the vibration
            val vibrationEffect = VibrationEffect.createWaveform(timings, amplitudes, repeat)
            val vibrator = getVibrator(context)

            val audioTrackPlayers = Array(audiosArray.size()) { LowLatencyAudioPlayer("", context) }
            val audioDelays = IntArray(audiosArray.size())

            // Get the directory of the hla file
            val audioDirectoryPath = path.substringBeforeLast('/')

            // Prepare the audio files
            for (i in 0 until audiosArray.size()) {
                val audioObject = audiosArray[i].asJsonObject

                // Get the "Time" value
                val time = audioObject.get("Time").asInt

                // Get the "Filename" value
                val fileName = audioDirectoryPath + "/" + audioObject.get("Filename").asString

                val audioTrackPlayer = LowLatencyAudioPlayer(fileName, context)
                audioTrackPlayer.preload()

                audioTrackPlayers[i] = audioTrackPlayer
                audioDelays[i] = time
            }

            val syncDelay = 0

            if (handler == null) {
                handler = Handler(Looper.getMainLooper())
            }

            val startTime = SystemClock.uptimeMillis() + syncDelay

            for (i in 0 until audiosArray.size()) {
                handler?.postAtTime({
                    audioTrackPlayers[i].playAudio()
                }, startTime + audioDelays[i])
            }
            handler?.postAtTime({
                vibrator.vibrate(vibrationEffect)
            }, startTime)
            handler?.postAtTime({
                completionCallback()
            }, startTime + durationMs)
        }
    }

    fun playOGG(path: String, completionCallback: () -> Unit) {
        val uncompressedPath = getUncompressedPath(path, context)
        mediaPlayer.release()
        mediaPlayer = MediaPlayer()

        mediaPlayer.setDataSource(uncompressedPath)

        try {
            mediaPlayer.prepare()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        mediaPlayer.start()
        mediaPlayer.setOnCompletionListener { _ ->
            // Playback completed
            completionCallback()
        }
    }


    fun playBuiltIn(name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = when (name) {
                "Click" -> VibrationEffect.EFFECT_CLICK
                "Double Click" -> VibrationEffect.EFFECT_DOUBLE_CLICK
                "Heavy Click" -> VibrationEffect.EFFECT_HEAVY_CLICK
                "Tick" -> VibrationEffect.EFFECT_TICK
                else -> null
            }

            effect?.let {
                getVibrator(context).vibrate(VibrationEffect.createPredefined(it))
            }
        }
    }
}