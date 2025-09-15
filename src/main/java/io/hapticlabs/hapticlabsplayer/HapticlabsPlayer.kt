package io.hapticlabs.hapticlabsplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.GET_DEVICES_OUTPUTS
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibrationEffect.WaveformEnvelopeBuilder
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

data class HapticCapabilities(
    val supportsOnOff: Boolean,
    val supportsAmplitudeControl: Boolean,
    val supportsAudioCoupled: Boolean,
    val supportsEnvelopeEffects: Boolean,
    val resonantFrequency: Float,
    val frequencyResponse: VibratorFrequencyProfile?,
    val qFactor: Float,
    val envelopeEffectInfo: VibratorEnvelopeEffectInfo?,
    val hapticSupportLevel: Float
)

data class LoadedOGG(
    val duration: Int,
    val soundId: Int
)

data class LoadedEnvelope(
    val effect: VibrationEffect,
    val startOffset: Long
)

class HapticlabsPlayer(private val context: Context) {
    val hapticsCapabilities = determineHapticCapabilities()

    private var mediaPlayer: MediaPlayer
    private var audioPlayer: MediaPlayer
    private var oggPool: SoundPool
    private var poolMap: HashMap<String, LoadedOGG> = HashMap()
    private var loadedSoundsSet: HashSet<Int> = HashSet()
    private var handler: Handler
    private var isBuiltInSpeakerSelected: Boolean = true

    private val SOUNDPOOL_BITS_PER_SAMPLE = 16

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

        // For the compiler
        oggPool = SoundPool.Builder().build()
        oggPool.release()

        setUpSoundPool()

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

    private fun setUpSoundPool() {
        val oggPoolAudioAttributes =
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType((AudioAttributes.CONTENT_TYPE_SONIFICATION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            oggPoolAudioAttributes.setHapticChannelsMuted(false)
        }
        oggPool = SoundPool.Builder().setAudioAttributes(oggPoolAudioAttributes.build()).build()

        oggPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedSoundsSet.add(sampleId)
            }
        }
    }


    protected fun finalize() {
        mediaPlayer.release()
        audioPlayer.release()
        oggPool.release()
    }

    private fun determineHapticCapabilities(): HapticCapabilities {
        val vibrator = getVibrator(context)

        val supportsOnOff = vibrator.hasVibrator()
        val supportsAmplitudeControl =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
        val supportsAudioCoupled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && AudioManager.isHapticPlaybackSupported()
        val supportsEnvelopeEffects =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && vibrator.areEnvelopeEffectsSupported()

        return HapticCapabilities(
            supportsOnOff,
            supportsAmplitudeControl,
            supportsAudioCoupled,
            supportsEnvelopeEffects,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) vibrator.resonantFrequency else Float.NaN,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) vibrator.frequencyProfile else null,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) getVibrator(context).qFactor else Float.NaN,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) getVibrator(context).envelopeEffectInfo else null,
            if (supportsOnOff) {
                if (supportsAmplitudeControl) {
                    if (supportsAudioCoupled) {
                        3f
                    } else {
                        if (supportsEnvelopeEffects) {
                            2.5f
                        } else {
                            2f
                        }
                    }
                } else {
                    1f
                }
            } else {
                // Vibrator service not available
                0f
            }
        )
    }

    private fun directoryPathToOGG(directoryPath: String): String {
        return "$directoryPath/lvl3/main.ogg"
    }

    fun play(directoryPath: String, completionCallback: () -> Unit) {
        // Switch by hapticSupportLevel
        when (hapticsCapabilities.hapticSupportLevel) {
            0f -> {
                return // Do nothing
            }

            1f -> {
                val path = "$directoryPath/lvl1/main.hla"
                return playHLA(path, completionCallback)
            }

            2f -> {
                val path = "$directoryPath/lvl2/main.hla"
                return playHLA(path, completionCallback)
            }

            2.5f -> {
                val path = "$directoryPath/lvl2_5/main.hle"
                return playHLE(path, completionCallback)
            }

            3f -> {
                val path = directoryPathToOGG(directoryPath)
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

    fun playHLE(path: String, completionCallback: () -> Unit) {
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

        // Extracting envelopes array
        val envelopesArray = jsonObject.getAsJsonArray("envelopes")
        val envelopeEffects =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA
                || ((
                        hapticsCapabilities.envelopeEffectInfo?.maxSize
                            ?: 0) <= 0)
            ) {
                emptyList()
            } else {
                envelopesArray.map { envelopeData ->
                    val envelopeObject = envelopeData.asJsonObject
                    val startFrequency = envelopeObject.get("initialFrequency").asFloat.coerceIn(
                        hapticsCapabilities.frequencyResponse?.minFrequencyHz ?: 0f,
                        hapticsCapabilities.frequencyResponse?.maxFrequencyHz ?: 500f
                    )
                    val startOffset = envelopeObject.get("startOffset").asLong
                    val points = envelopeObject.getAsJsonArray("points")

                    // Filter those points that have a priority < max supported point count
                    val mostRelevantPoints = points.filter { point ->
                        point.asJsonObject.get("priority").asInt < (
                                hapticsCapabilities.envelopeEffectInfo?.maxSize
                                    ?: 0)
                    }

                    val envelope = WaveformEnvelopeBuilder().setInitialFrequencyHz(startFrequency)
                    var currentTimeInEnvelope = 0L

                    mostRelevantPoints.forEach { p ->
                        val pointObject = p.asJsonObject
                        val safeFrequency = pointObject.get("frequency").asFloat.coerceIn(
                            hapticsCapabilities.frequencyResponse?.minFrequencyHz ?: 0f,
                            hapticsCapabilities.frequencyResponse?.maxFrequencyHz ?: 500f
                        )
                        val amplitude = pointObject.get("amplitude").asFloat
                        val time = pointObject.get("time").asLong

                        envelope.addControlPoint(
                            amplitude,
                            safeFrequency,
                            time - currentTimeInEnvelope
                        )
                        currentTimeInEnvelope = time
                    }

                    LoadedEnvelope(
                        envelope.build(), startOffset
                    )
                }
            }
        val durationMs = jsonObject.get("Duration").asLong

        val audiosArray = jsonObject.getAsJsonArray("Audios")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Prepare the vibration
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

            envelopeEffects.map { effect ->
                handler.postAtTime({
                    vibrator.vibrate(effect.effect)
                }, startTime + effect.startOffset)
            }

            handler.postAtTime({
                completionCallback()
            }, startTime + durationMs)
        }
    }

    fun canOGGBeLoadedToSoundPool(
        uncompressedPath: String
    ): Boolean {
        if (poolMap.containsKey(uncompressedPath)) {
            return true
        }

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(uncompressedPath)
        val durationMs =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt()
        val sampleRateHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toLong()
        } else {
            // Assume 48 kHz
            48000
        }
        retriever.release()

        // Stereo audio + haptics
        val channelCount = 3

        if (durationMs == null || sampleRateHz == null) {
            // Truly, we can't tell. So to be safe, we need to assume we can't load it
            return false
        }

        val totalBitsConsumed =
            SOUNDPOOL_BITS_PER_SAMPLE * channelCount * durationMs * sampleRateHz / 1000

        // Maximum is one MB
        return totalBitsConsumed < 8 * 1_000_000
    }

    private fun preloadUncompressedPath(
        uncompressedPath: String
    ) {
        if (hapticsCapabilities.supportsAudioCoupled) {
            loadOGG(
                uncompressedPath
            )
        }
    }

    fun preload(
        directoryPath: String
    ) {
        val uncompressedPath = getUncompressedPath(directoryPathToOGG(directoryPath), context)
        preloadUncompressedPath(uncompressedPath)
    }

    fun preloadOGG(
        oggPath: String
    ) {
        val uncompressedPath = getUncompressedPath(oggPath, context)
        preloadUncompressedPath(uncompressedPath)
    }

    private fun unloadUncompressedPath(uncompressedPath: String) {
        val loaded = poolMap[uncompressedPath]
        loaded?.soundId?.let {
            oggPool.unload(it)
            poolMap.remove(uncompressedPath)
            loadedSoundsSet.remove(it)
        }
    }

    fun unload(directoryPath: String) {
        val uncompressedPath = getUncompressedPath(directoryPathToOGG(directoryPath), context)
        unloadUncompressedPath(uncompressedPath)
    }

    fun unloadOGG(oggPath: String) {
        val uncompressedPath = getUncompressedPath(oggPath, context)
        unloadUncompressedPath(uncompressedPath)
    }

    fun unloadAll() {
        // Release all loaded OGGs
        oggPool.release()

        // Recreate the SoundPool
        setUpSoundPool()

        // Clear the pool map
        poolMap.clear()
    }

    private fun getOGGSoundId(
        uncompressedPath: String
    ): LoadedOGG? {
        // Check if it's already loaded
        poolMap[uncompressedPath]?.let {
            // Already loading or loaded
            return if (loadedSoundsSet.contains(it.soundId)) it else null
        } ?: return null
    }

    private fun loadOGG(
        uncompressedPath: String
    ) {
        // Check if it's already loaded
        poolMap[uncompressedPath]?.let {
            // Already loading or loaded
            return
        }

        if (canOGGBeLoadedToSoundPool(uncompressedPath)) {
            // Load the duration
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(uncompressedPath)
            val durationMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt()
                    ?: 0
            retriever.release()

            poolMap[uncompressedPath] =
                LoadedOGG(durationMs, oggPool.load(uncompressedPath, 1))
        }

    }

    fun playOGG(path: String, completionCallback: () -> Unit) {
        val uncompressedPath = getUncompressedPath(path, context)
        val loadedSound = getOGGSoundId(uncompressedPath)

        if (isBuiltInSpeakerSelected && loadedSound != null) {
            // SoundPool approach
            oggPool.play(loadedSound.soundId, 1f, 1f, 1, 0, 1.0f)
            println("Playing OGG from SoundPool: $uncompressedPath")
            handler.postDelayed(completionCallback, loadedSound.duration.toLong())
            return
        }

        // Need to route the haptic playback to the device speaker!
        // That only works with the MediaPlayer.

        // Set up the mediaPlayer
        mediaPlayer.release()
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

        // Whether we need to run the audioPlayer or not
        var useSeparateAudio = false

        // Continue with the separating approach only if we found a built-in speaker and if the
        // built-in speaker is not selected
        if (!isBuiltInSpeakerSelected) {

            // We need to route the haptics to the device speaker!
            // Let's find that device speaker
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outputDevices = audioManager.getDevices(GET_DEVICES_OUTPUTS)

            val builtInSpeaker =
                outputDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            builtInSpeaker?.let {
                // We will need to run the audioPlayer
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
        println("Playing OGG from MediaPlayer: $uncompressedPath")

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