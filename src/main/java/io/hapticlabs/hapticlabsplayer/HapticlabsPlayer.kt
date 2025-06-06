package io.hapticlabs.hapticlabsplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.GET_DEVICES_OUTPUTS
import android.media.MediaPlayer
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.vibrator.VibratorEnvelopeEffectInfo
import android.os.vibrator.VibratorFrequencyProfile
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlin.math.abs
import androidx.mediarouter.media.MediaRouter
import java.io.IOException


class HapticlabsPlayer(private val context: Context) {
    val supportsOnOff = determineSupportsOnOff()
    val supportsAmplitudeControl = determineSupportsAmplitudeControl()
    val supportsAudioCoupled = determineSupportsAudioCoupled()
    val supportsEnvelopeEffects = determineSupportsEnvelopeEffects()
    val resonantFrequency = determineResonantFrequency()
    val frequencyResponse = determineFrequencyResponse()
    val qFactor = determineQFactor()
    val envelopeEffectInfo = determineEnvelopeEffectInfo()
    val hapticSupportLevel = determineHapticSupportLevel()

    private var mediaPlayer: MediaPlayer
    private var audioPlayer: MediaPlayer
    private var handler: Handler
    private var isBuiltInSpeakerSelected: Boolean = true

    init {
        mediaPlayer = MediaPlayer()
        audioPlayer = MediaPlayer()

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

        // Listen for device speaker selection to determine whether or not haptic playback must
        // be routed to the device speaker explicitly
        handler = Handler(Looper.getMainLooper())
        handler.post(
            Runnable {
                val mediaRouter = MediaRouter.getInstance(context)
                val selector = MediaRouteSelector.Builder()
                    .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
                    .build()

                val mediaRouterCallback = object : MediaRouter.Callback() {
                    override fun onRouteSelected(
                        router: MediaRouter,
                        route: MediaRouter.RouteInfo
                    ) {
                        isBuiltInSpeakerSelected = router.selectedRoute.isDeviceSpeaker
                    }

                    override fun onRouteUnselected(
                        router: MediaRouter,
                        route: MediaRouter.RouteInfo
                    ) {
                        isBuiltInSpeakerSelected = router.selectedRoute.isDeviceSpeaker
                    }

                    override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                        isBuiltInSpeakerSelected = router.selectedRoute.isDeviceSpeaker
                    }
                }

                // 4. Add MediaRouter.Callback
                mediaRouter.addCallback(selector, mediaRouterCallback)
            }
        )
    }

    protected fun finalize() {
        mediaPlayer.release()
        audioPlayer.release()
    }

    private fun determineSupportsOnOff(): Boolean {
        val vibrator = getVibrator(context)
        return vibrator.hasVibrator()
    }

    private fun determineSupportsAmplitudeControl(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getVibrator(context).hasAmplitudeControl()
        } else {
            false
        }
    }

    private fun determineSupportsAudioCoupled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioManager.isHapticPlaybackSupported()
        } else {
            false
        }
    }

    private fun determineSupportsEnvelopeEffects(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            getVibrator(context).areEnvelopeEffectsSupported()
        } else {
            false
        }
    }

    private fun determineResonantFrequency(): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getVibrator(context).resonantFrequency
        } else {
            Float.NaN
        }
    }

    private fun determineFrequencyResponse(): VibratorFrequencyProfile? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            getVibrator(context).frequencyProfile
        } else {
            null
        }
    }

    private fun determineQFactor(): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getVibrator(context).qFactor
        } else {
            Float.NaN
        }
    }

    private fun determineEnvelopeEffectInfo(): VibratorEnvelopeEffectInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            getVibrator(context).envelopeEffectInfo
        } else {
            null
        }
    }

    private fun determineHapticSupportLevel(): Int {
        val level = if (determineSupportsOnOff()) {
            if (determineSupportsAmplitudeControl()) {
                if (determineSupportsAudioCoupled()) {
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

            val startTime = SystemClock.uptimeMillis() + syncDelay

            for (i in 0 until audiosArray.size()) {
                handler.postAtTime({
                    audioTrackPlayers[i].playAudio()
                }, startTime + audioDelays[i])
            }
            handler.postAtTime({
                vibrator.vibrate(vibrationEffect)
            }, startTime)
            handler.postAtTime({
                completionCallback()
            }, startTime + durationMs)
        }
    }

    fun playOGG(path: String, completionCallback: () -> Unit) {
        val uncompressedPath = getUncompressedPath(path, context)
        mediaPlayer.release()

        // Prepare the mediaPlayer
        mediaPlayer = MediaPlayer()

        val mediaPlayerAudioAttributes =
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType((AudioAttributes.CONTENT_TYPE_SONIFICATION))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaPlayerAudioAttributes.setHapticChannelsMuted(false)
        }

        mediaPlayer.setAudioAttributes(
            mediaPlayerAudioAttributes
                .build()
        )

        mediaPlayer.setDataSource(uncompressedPath)

        // Check if we need to separate audio from the media player
        var useSeparateAudio = false
        if (!isBuiltInSpeakerSelected) {
            // We need to route the haptics to the device speaker!

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outputDevices = audioManager.getDevices(GET_DEVICES_OUTPUTS)

            // Find the built-in speaker
            val builtInSpeaker =
                outputDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

            builtInSpeaker.let {
                useSeparateAudio = true

                // Set up the audio player
                audioPlayer.release()
                audioPlayer = MediaPlayer()

                val audioPlayerAttributesBuilder =
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType((AudioAttributes.CONTENT_TYPE_SONIFICATION))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    audioPlayerAttributesBuilder.setHapticChannelsMuted(true)
                }

                audioPlayer.setAudioAttributes(
                    audioPlayerAttributesBuilder.build()
                )

                // Turn off audio on the device speaker, we only need haptics there
                mediaPlayer.setVolume(0f, 0f)

                // Route the haptic playback to the built-in speaker
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    mediaPlayer.setPreferredDevice(builtInSpeaker)
                }

                // Prepare the audio player
                audioPlayer.setDataSource(uncompressedPath)
                try {
                    audioPlayer.prepare()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        try {
            mediaPlayer.prepare()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        // Go!
        if (useSeparateAudio) {
            audioPlayer.start()
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