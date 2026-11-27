package io.hapticlabs.hapticlabsplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.GET_DEVICES_OUTPUTS
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibrationEffect.WaveformEnvelopeBuilder
import android.util.Log
import android.os.vibrator.VibratorEnvelopeEffectInfo
import android.os.vibrator.VibratorFrequencyProfile
import androidx.annotation.RequiresApi
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import java.io.File
import java.nio.charset.StandardCharsets
import androidx.mediarouter.media.MediaRouter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException
import java.util.Vector
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.round

data class HapticCapabilities(
    /**
     * Whether the device supports on / off vibrator control
     */
    val supportsOnOff: Boolean,
    /**
     * Whether the device supports vibration amplitude control
     */
    val supportsAmplitudeControl: Boolean,
    /**
     * Whether the device supports audio coupled haptics using OGG files
     */
    val supportsAudioCoupled: Boolean,
    /**
     * Whether the device supports envelope effects (Basic/WaveformEnvelopeBuilder)
     */
    val supportsEnvelopeEffects: Boolean,
    /**
     * The self-reported resonance frequency of the device's vibration actuator
     */
    val resonantFrequency: Float,
    /**
     * The self-reported frequency response of the device's vibration actuator
     *
     * See [android.os.vibrator.VibratorFrequencyProfile] for more information.
     */
    val frequencyResponse: VibratorFrequencyProfile?,
    /**
     * The self-reported q-factor of the device's vibration actuator
     */
    val qFactor: Float,
    /**
     * The self-reported envelope effect info of the device's vibration actuator.
     *
     * See [android.os.vibrator.VibratorEnvelopeEffectInfo] for more information.
     */
    val envelopeEffectInfo: VibratorEnvelopeEffectInfo?,
    /**
     * The level of haptic support of the device.
     * 0: No haptic support
     * 1: On/off haptic support
     * 2: Amplitude haptic support
     * 3: OGG haptic support
     * 4: PWLE haptic support
     */
    val hapticSupportLevel: Int
)

@JsonClass(generateAdapter = true)
internal data class LegacyHlaAudio(
    val Time: Int,
    val Filename: String
)

@JsonClass(generateAdapter = true)
// Original HLA format before v2
internal data class LegacyHLA(
    val ProjectName: String,
    val TrackName: String,
    val Duration: Long,
    val Audios: List<LegacyHlaAudio>,
    val Timings: LongArray,
    val Amplitudes: IntArray,
    val Repeat: Int
)


interface HasDuration {
    /** Duration in milliseconds */
    val duration: Long
}

interface HasOffset {
    /** Offset from the start of playback in milliseconds */
    val startOffset: Long
}

/** An audio file of HLA version 2, deprecated, to play at [startOffset] */
@JsonClass(generateAdapter = true)
internal data class HlaAudio(
    override val startOffset: Long,
    val filename: String
) : HasOffset

@JsonClass(generateAdapter = true)
internal data class PWLEPoint(
    val priority: Int,
    val frequency: Float,
    val amplitude: Float,
    val time: Long
)

@JsonClass(generateAdapter = true)
internal data class BasicPWLEPoint(
    val priority: Int,
    val sharpness: Float,
    val intensity: Float,
    val time: Long
)

@JsonClass(generateAdapter = true)
internal data class AmplitudeWaveform(
    val timings: LongArray,
    val amplitudes: IntArray,
    val repeat: Int,
    override val startOffset: Long
) : HasOffset

/** A haptic primitive, such as a click, to play at [startOffset]. */
@JsonClass(generateAdapter = true)
data class HapticPrimitive(
    /** The primitive's name, such as "click", "tick" or "lowTick" */
    val name: String,
    /** The primitive's intensity, from 0 to 1 */
    val scale: Float,
    override val startOffset: Long
) : HasOffset

@JsonClass(generateAdapter = true)
internal data class OGGFile(
    val name: String,
    override val startOffset: Long
) : HasOffset

@JsonClass(generateAdapter = true)
internal data class PWLEEnvelope(
    val initialFrequency: Float,
    val points: List<PWLEPoint>,
    override val startOffset: Long
) : HasOffset

@JsonClass(generateAdapter = true)
internal data class BasicPWLEEnvelope(
    val initialSharpness: Float,
    val points: List<BasicPWLEPoint>,
    override val startOffset: Long
) : HasOffset

/**
 * A signal's audio: since version 3 one file in [audio], before that files at offsets in [audios].
 */
internal interface ReferencesAudio {
    /** The filename of the audio to play from the start, relative to the .hla file */
    val audio: String?

    /** Version 2's audio files, deprecated */
    val audios: List<HlaAudio>
}

@JsonClass(generateAdapter = true)
internal data class WaveformSignal(
    val primitives: List<HapticPrimitive>,
    val amplitudes: List<AmplitudeWaveform>,
    override val duration: Long,
    override val audio: String? = null,
    override val audios: List<HlaAudio> = emptyList()
) : HasDuration, ReferencesAudio

@JsonClass(generateAdapter = true)
internal data class OGGSignal(
    val primitives: List<HapticPrimitive>,
    val amplitudes: List<AmplitudeWaveform>,
    val oggs: List<OGGFile>,
    override val duration: Long,
    override val audio: String? = null,
    override val audios: List<HlaAudio> = emptyList()
) : HasDuration, ReferencesAudio

@JsonClass(generateAdapter = true)
internal data class PWLESignal(
    val primitives: List<HapticPrimitive>,
    val amplitudes: List<AmplitudeWaveform>,
    val oggs: List<OGGFile>,
    val envelopes: List<PWLEEnvelope>,
    val basicEnvelopes: List<BasicPWLEEnvelope>,
    override val duration: Long,
    override val audio: String? = null,
    override val audios: List<HlaAudio> = emptyList()
) : HasDuration, ReferencesAudio

/** An HLA file of version 2, deprecated, or 3 */
@JsonClass(generateAdapter = true)
internal data class HLA(
    val version: Int,
    val projectName: String,
    val trackName: String,
    val onOffSignal: WaveformSignal,
    val amplitudeSignal: WaveformSignal,
    val oggSignal: OGGSignal,
    val pwleSignal: PWLESignal
)


/** A sound loaded into SoundPool, or loading */
internal data class PooledSound(
    val soundId: Int,
    /** In milliseconds */
    val duration: Long,
)

/** A vibration effect, ready to play. */
data class LoadedEffect(
    val effect: VibrationEffect,
    override val startOffset: Long
) : HasOffset

/**
 * An audio file, ready to play from the start of a [LoadedHLA]. [HapticlabsPlayer.preloadAudio]
 * lowers the latency of playing it.
 */
data class LoadedAudio(
    /** The path of the audio file in the filesystem */
    val path: String
)

/** What [HapticlabsPlayer.playLoadedHLA] can multiply the duration of audio with */
val AUDIO_DURATION_SCALES = 0.5f..2f

/** A media file to play at [startOffset] */
private data class MediaFile(
    val path: String,
    /** Whether the file has haptic channels, which play on the built-in speaker's vibrator */
    val hasHapticChannels: Boolean,
    override val startOffset: Long,
    /** What the duration of the file is multiplied with, keeping its pitch */
    val durationScale: Float
) : HasOffset {
    /**
     * @return Where in the file a playback from [startPosition] starts it, in milliseconds since
     * the start of the file
     */
    fun positionAt(startPosition: Long): Long = (maxOf(0, startPosition - startOffset) / durationScale).roundToLong()
}

/** An OGG file with haptics on its own path, ready to play. */
data class UncompressedOGGFile(
    val uncompressedPath: String,
    override val startOffset: Long
) : HasOffset

/** Effects, audio and OGGs to play together, ready to play with [HapticlabsPlayer.playLoadedHLA]. */
data class LoadedHLA(
    val effects: List<LoadedEffect>,
    val audio: LoadedAudio?,
    val oggs: List<UncompressedOGGFile>,
    override val duration: Long
) : HasDuration

class HapticlabsPlayer(private val context: Context) {
    private val TAG = "HapticlabsPlayer"

    private val ZIP_CACHE_SUBDIRECTORY = "hapticlabsPlayerCache"

    private val OGG_CACHE_SUBDIRECTORY = "hapticlabsPlayerOGGCache"
    private val OGG_EXTENSION = ".ogg"
    private val OGG_SAMPLE_RATE = 48000
    private val WAV_GEN_STEP_SIZE = 1
    private val WAV_HEADER_SIZE = 44
    private val BYTES_PER_SAMPLE = 2

    private val LEVEL_2_SAMSUNG_DEVICES = arrayOf(
        "SM-S721B",
        "SM-S721B/DS",
        "SM-S721U1",
        "SM-S721U",
        "SM-S721W",
        "SM-S7210",
        "SM-S721N",
        "SM-S921B",
        "SM-S921B/DS",
        "SM-S921U",
        "SM-S921U1",
        "SM-S921W",
        "SM-S921N",
        "SM-S9210",
        "SM-S921E",
        "SM-S921E/DS",
        "SM-S926B",
        "SM-S926B/DS",
        "SM-S926U",
        "SM-S926U1",
        "SM-S926W",
        "SM-S926N",
        "SM-S9260",
        "SM-S926E",
        "SM-S926E/DS",
        "SM-S928B",
        "SM-S928B/DS",
        "SM-S928U",
        "SM-S928U1",
        "SM-S928W",
        "SM-S928N",
        "SM-S9280",
        "SM-S928E",
        "SM-S928E/DS"
    )

    val hapticsCapabilities = determineHapticCapabilities()

    private val mediaPlayer: MediaPlayer
    private var soundPool: SoundPool
    private var poolMap: HashMap<String, PooledSound> = HashMap()


    private var hacMap: HashMap<String, LoadedHLA> = HashMap()
    private var loadedSoundsSet: HashSet<Int> = HashSet()
    private var handler: Handler
    private var isBuiltInSpeakerSelected: Boolean = true

    private val MICROSECONDS_PER_MILLISECOND = 1000
    private val MILLISECONDS_PER_SECOND = 1000

    private val SOUNDPOOL_BYTES_PER_SAMPLE = 2

    // SoundPool fails to load sounds that decode to more
    private val SOUNDPOOL_MAX_BYTES = 1_000_000

    // How many sounds SoundPool plays at once. Beyond that, a new one stops the oldest
    private val MAX_SOUND_POOL_STREAMS = 8

    // How often to check whether media playing with MediaPlayers is heard yet
    private val HEARD_CHECK_INTERVAL_MS = 5L

    // How long to wait at most for media playing with MediaPlayers to be heard
    private val MAX_HEARD_WAIT_MS = 1000L

    private val HAC_EXTENSION = ".hac"

    private val EMPTY_LOADED_HLA = LoadedHLA(emptyList(), null, emptyList(), 0)

    /**
     * Initialize the player.
     *
     * Starts observing audio output device changes to handle OGG routing and removes outdated
     * caches.
     */
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
                try {
                    val generator = HapticGenerator.create(mediaPlayer.audioSessionId)
                    generator.setEnabled(false)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Failed to create haptic generator to disable it", e)
                }
            }
        }

        // For the compiler
        soundPool = SoundPool.Builder().build()
        soundPool.release()

        setUpSoundPool()

        handler = Handler(Looper.getMainLooper())

        // Listen for device speaker selection to determine whether haptic playback must
        // be routed to the device speaker explicitly
        handler.post {
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

        ZipCacheManager.dropInvalidCachesIn(context, ZIP_CACHE_SUBDIRECTORY)
    }

    private fun setUpSoundPool() {
        val soundPoolAudioAttributes =
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType((AudioAttributes.CONTENT_TYPE_SONIFICATION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            soundPoolAudioAttributes.setHapticChannelsMuted(false)
        }
        // So that sounds play together, like from MediaPlayers
        soundPool = SoundPool.Builder()
            .setAudioAttributes(soundPoolAudioAttributes.build())
            .setMaxStreams(MAX_SOUND_POOL_STREAMS)
            .build()

        soundPool.setOnLoadCompleteListener { pool, sampleId, status ->
            // A released pool's sample IDs are reused by the next pool
            if (status == 0 && pool === soundPool) {
                loadedSoundsSet.add(sampleId)
            }
        }
    }


    protected fun finalize() {
        mediaPlayer.release()
        soundPool.release()
    }

    private fun determineHapticCapabilities(): HapticCapabilities {
        val vibrator = getVibrator(context)
        val isSamsungLevel2Device =
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                    LEVEL_2_SAMSUNG_DEVICES.contains(Build.MODEL)

        val supportsOnOff = vibrator.hasVibrator()
        val supportsAmplitudeControl =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
        val supportsAudioCoupled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    AudioManager.isHapticPlaybackSupported() &&
                    !isSamsungLevel2Device
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
                    if (supportsEnvelopeEffects) {
                        4
                    } else if (supportsAudioCoupled) {
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
        )
    }

    private fun directoryPathToOGG(directoryPath: String): String {
        return "$directoryPath/lvl3/main.ogg"
    }

    private fun directoryPathToHLA(directoryPath: String, hapticSupportLevel: Int): String {
        return "$directoryPath/lvl$hapticSupportLevel/main.hla"
    }

    /**
     * Play a .hac file.
     *
     * Also supports legacy directory-based approach, which is now deprecated. For this, pass the
     * path to a directory whose structure resembles
     *  ```
     *  directoryPath
     *   ├── lvl1
     *   │   └── main.hla
     *   ├── lvl2
     *   │   └── main.hla
     *   └── lvl3
     *       └── main.ogg
     * ```
     * Support for the directory-based approach may be removed in future releases. Use .hac files
     * instead.
     *
     * @param directoryOrHACPath The path to the .hac file.  Can be an absolute path in the
     * filesystem or a path in the assets directory
     * @param completionCallback A callback to be called when the playback is complete
     */
    fun play(directoryOrHACPath: String, completionCallback: () -> Unit): () -> Unit {
        // Check if it's a .hac file
        if (directoryOrHACPath.endsWith(HAC_EXTENSION)) {
            return playHAC(directoryOrHACPath, completionCallback)
        }

        // Not a .hac file -> Legacy directory approach

        var aborted = false
        var abortPlayback = {}

        val abortOrPreventPlayback = {
            aborted = true
            abortPlayback()
        }

        // Switch by hapticSupportLevel
        when (hapticsCapabilities.hapticSupportLevel) {
            0 -> {
                // Do nothing
                completionCallback()
            }

            1, 2 -> {
                val hlaPath =
                    directoryPathToHLA(directoryOrHACPath, hapticsCapabilities.hapticSupportLevel)
                loadHLA(hlaPath) { loadedHLA ->
                    if (!aborted)
                        abortPlayback = playLoadedHLA(loadedHLA, completionCallback)
                }
            }

            3, 4 -> {
                abortPlayback =
                    resolveAndPlayOGG(directoryPathToOGG(directoryOrHACPath), completionCallback)
            }
        }

        return abortOrPreventPlayback
    }

    private fun createFadeEffect(
        durationMs: Long,
        startAmplitude: Float,
        endAmplitude: Float,
    ): AmplitudeWaveform {
        val pointCount =
            min(durationMs.toInt(), abs(endAmplitude * 255 - startAmplitude * 255).toInt())
        val timings = LongArray(pointCount)
        val amplitudes = IntArray(pointCount)

        var passedDuration = 0L
        for (i in 0 until pointCount) {
            timings[i] =
                round((i + 1).toDouble() * durationMs / pointCount).toLong() - passedDuration
            passedDuration += timings[i]
            amplitudes[i] =
                round(((i + 0.5) / pointCount * (endAmplitude - startAmplitude) + startAmplitude) * 255).toInt()
        }

        return AmplitudeWaveform(timings, amplitudes, -1, 0)
    }

    private fun loadPrimitiveFallback(primitive: HapticPrimitive): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null
        }
        return when (primitive.name) {
            // 12 ms max amplitude
            "click" -> VibrationEffect.createWaveform(
                longArrayOf(12),
                intArrayOf((255 * primitive.scale).toInt()),
                -1
            )

            "thud" -> {
                // 300 ms fading amplitude from 1 to 0
                val fade = createFadeEffect(300, 1f * primitive.scale, 0f)
                VibrationEffect.createWaveform(fade.timings, fade.amplitudes, fade.repeat)
            }

            "spin" -> {
                // 75 ms fade from 0.5 to 1
                val fadeUp = createFadeEffect(75, 0.5f * primitive.scale, 1f * primitive.scale)
                // 75 ms fade from 1 to 0.5
                val fadeDown = createFadeEffect(75, 1f * primitive.scale, 0.5f * primitive.scale)
                VibrationEffect.createWaveform(
                    fadeUp.timings + fadeDown.timings,
                    fadeUp.amplitudes + fadeDown.amplitudes,
                    fadeUp.repeat
                )
            }

            "quickRise" -> {
                // 150 ms from 0 to 1
                val fade = createFadeEffect(150, 0f, 1f * primitive.scale)
                VibrationEffect.createWaveform(fade.timings, fade.amplitudes, fade.repeat)
            }

            "slowRise" -> {
                // 500 ms from 0 to 1
                val fade = createFadeEffect(500, 0f, 1f * primitive.scale)
                VibrationEffect.createWaveform(fade.timings, fade.amplitudes, fade.repeat)
            }

            "quickFall" -> {
                // 50 ms fade up from 0.5 to 1
                val fadeUp = createFadeEffect(50, 0.5f * primitive.scale, 1f * primitive.scale)
                // 50 ms fade down from 1 to 0
                val fadeDown = createFadeEffect(50, 1f * primitive.scale, 0f)
                VibrationEffect.createWaveform(
                    fadeUp.timings + fadeDown.timings,
                    fadeUp.amplitudes + fadeDown.amplitudes,
                    fadeUp.repeat
                )
            }

            // 5 ms half amplitude
            "tick" -> VibrationEffect.createWaveform(
                longArrayOf(5),
                intArrayOf((255 * primitive.scale / 2).toInt()),
                -1
            )

            // 12 ms quarter amplitude
            "lowTick" -> VibrationEffect.createWaveform(
                longArrayOf(12),
                intArrayOf((255 * primitive.scale / 4).toInt()),
                -1
            )

            else -> null
        }
    }

    /**
     * Load primitives as vibration effects, falling back to amplitude waveforms where the device
     * or SDK version doesn't support them.
     *
     * @return The effects of the primitives that could be loaded
     */
    fun loadPrimitives(primitives: List<HapticPrimitive>): List<LoadedEffect> {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Primitives are not supported on this SDK version, falling back.")
            primitives.mapNotNull {
                loadPrimitiveFallback(it)?.let { vibrationEffect ->
                    LoadedEffect(vibrationEffect, it.startOffset)
                }
            }
        } else {
            primitives.mapNotNull {
                // Map the string to the primitive id
                val primitiveId = when (it.name) {
                    "click" -> VibrationEffect.Composition.PRIMITIVE_CLICK
                    "thud" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        VibrationEffect.Composition.PRIMITIVE_THUD
                    } else {
                        null
                    }

                    "spin" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        VibrationEffect.Composition.PRIMITIVE_SPIN
                    } else {
                        null
                    }

                    "quickRise" -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                    "slowRise" -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
                    "quickFall" -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
                    "tick" -> VibrationEffect.Composition.PRIMITIVE_TICK
                    "lowTick" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                    } else {
                        null
                    }
                    // Default to click if the name is unknown
                    else -> null
                }

                (primitiveId?.let { primitiveId ->
                    // Check if the effect is supported
                    if (getVibrator(context).arePrimitivesSupported(primitiveId)[0]) {
                        VibrationEffect.startComposition().addPrimitive(
                            primitiveId, it.scale
                        ).compose()
                    } else {
                        // Effect not supported
                        Log.w(
                            TAG,
                            "Primitive ${it.name} is not supported on this device, falling back."
                        )
                        loadPrimitiveFallback(it)
                    }
                } ?: run {
                    // Effect not identified
                    Log.w(
                        TAG,
                        "Primitive ${it.name} is not known in this SDK version, falling back."
                    )
                    loadPrimitiveFallback(it)
                })?.let { vibrationEffect ->
                    LoadedEffect(vibrationEffect, it.startOffset)
                }
            }
        }
    }

    private fun loadAmplitudeWaveforms(amplitudes: List<AmplitudeWaveform>): List<LoadedEffect> {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.e(TAG, "No amplitude effects supported on this device")
            emptyList()
        } else {
            amplitudes.map {
                LoadedEffect(
                    VibrationEffect.createWaveform(it.timings, it.amplitudes, it.repeat),
                    it.startOffset
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun <T : HasOffset, P> loadEnvelopes(
        envelopes: List<T>,
        getPoints: (T) -> List<P>,
        getPriority: (P) -> Int,
        buildEffect: (T, List<P>) -> VibrationEffect
    ): List<LoadedEffect> {
        val maxSize = hapticsCapabilities.envelopeEffectInfo?.maxSize ?: 0

        return envelopes.mapNotNull { envelopeData ->
            val relevantPoints = getPoints(envelopeData).filter { getPriority(it) < maxSize }
            if (relevantPoints.isEmpty()) {
                null
            } else {
                LoadedEffect(buildEffect(envelopeData, relevantPoints), envelopeData.startOffset)
            }
        }
    }

    private fun loadBasicPWLEWaveforms(pwles: List<BasicPWLEEnvelope>): List<LoadedEffect> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Log.e(TAG, "No basic PWLE effects supported on this device")
            return emptyList()
        }

        return loadEnvelopes(pwles, { it.points }, { it.priority }) { envelopeData, points ->
            val envelope = VibrationEffect.BasicEnvelopeBuilder()
                .setInitialSharpness(envelopeData.initialSharpness)
            var currentTimeInEnvelope = 0L

            points.forEach { p ->
                envelope.addControlPoint(
                    p.intensity,
                    p.sharpness,
                    p.time - currentTimeInEnvelope
                )
                currentTimeInEnvelope = p.time
            }
            envelope.build()
        }
    }

    private fun loadPWLEWaveforms(pwles: List<PWLEEnvelope>): List<LoadedEffect> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Log.e(TAG, "No PWLE effects supported on this device")
            return emptyList()
        }

        return loadEnvelopes(pwles, { it.points }, { it.priority }) { envelopeData, points ->
            val minFreq = hapticsCapabilities.frequencyResponse?.minFrequencyHz ?: 0f
            val maxFreq = hapticsCapabilities.frequencyResponse?.maxFrequencyHz ?: 500f

            val startFrequency = envelopeData.initialFrequency.coerceIn(minFreq, maxFreq)
            val envelope = WaveformEnvelopeBuilder().setInitialFrequencyHz(startFrequency)
            var currentTimeInEnvelope = 0L

            points.forEach { p ->
                val safeFrequency = p.frequency.coerceIn(minFreq, maxFreq)
                envelope.addControlPoint(
                    p.amplitude,
                    safeFrequency,
                    p.time - currentTimeInEnvelope
                )
                currentTimeInEnvelope = p.time
            }
            envelope.build()
        }
    }

    private fun loadAudio(
        audioDirectory: PossiblyZippedDirectory,
        signal: ReferencesAudio
    ): LoadedAudio? {
        val filename = signal.audio ?: return null
        return audioDirectory.getChild(filename)?.let { LoadedAudio(it.absolutePath) } ?: run {
            Log.e(TAG, "Failed to find audio file: $filename in directory: $audioDirectory")
            null
        }
    }

    /**
     * Since version 3, a signal plays one audio file, from its start.
     *
     * @return The filename of the first of [audios], which version 2 and legacy HLAs play at
     * offsets
     */
    private fun firstAudioOf(audios: List<HlaAudio>): String? {
        val first = audios.firstOrNull() ?: return null
        if (audios.size > 1 || first.startOffset != 0L) {
            Log.w(
                TAG,
                "Playing only ${first.filename} of the audio files ${audios.map { it.filename }}, from the start"
            )
        }
        return first.filename
    }

    /**
     * @return This version 2 HLA with the audio of each signal as version 3 has it
     */
    private fun HLA.withAudiosOfVersion2(): HLA = copy(
        onOffSignal = onOffSignal.copy(audio = firstAudioOf(onOffSignal.audios)),
        amplitudeSignal = amplitudeSignal.copy(audio = firstAudioOf(amplitudeSignal.audios)),
        oggSignal = oggSignal.copy(audio = firstAudioOf(oggSignal.audios)),
        pwleSignal = pwleSignal.copy(audio = firstAudioOf(pwleSignal.audios))
    )

    private fun loadOGGs(
        oggDirectory: PossiblyZippedDirectory,
        oggs: List<OGGFile>
    ): List<UncompressedOGGFile> {
        return oggs.mapNotNull {
            oggDirectory.getChild(it.name)?.let { oggFile ->
                UncompressedOGGFile(
                    oggFile.absolutePath,
                    it.startOffset
                )
            } ?: run {
                Log.e(TAG, "Failed to find OGG file: ${it.name}")
                null
            }
        }
    }

    private fun loadParsedHLA(
        resourcesDirectoryPath: PossiblyZippedDirectory,
        hla: HLA,
        completionCallback: (loadedHLA: LoadedHLA) -> Unit
    ) {
        val loadedHLA = when (hapticsCapabilities.hapticSupportLevel) {
            1 -> LoadedHLA(
                loadPrimitives(hla.onOffSignal.primitives) + loadAmplitudeWaveforms(hla.onOffSignal.amplitudes),
                loadAudio(resourcesDirectoryPath, hla.onOffSignal),
                emptyList(),
                hla.onOffSignal.duration
            )

            2 -> LoadedHLA(
                loadPrimitives(hla.amplitudeSignal.primitives) + loadAmplitudeWaveforms(
                    hla.amplitudeSignal.amplitudes
                ),
                loadAudio(resourcesDirectoryPath, hla.amplitudeSignal),
                emptyList(),
                hla.amplitudeSignal.duration
            )

            3 -> LoadedHLA(
                loadPrimitives(hla.oggSignal.primitives) + loadAmplitudeWaveforms(hla.oggSignal.amplitudes),
                loadAudio(resourcesDirectoryPath, hla.oggSignal),
                loadOGGs(resourcesDirectoryPath, hla.oggSignal.oggs),
                hla.oggSignal.duration
            )

            4 -> LoadedHLA(
                loadPrimitives(hla.pwleSignal.primitives)
                        + loadAmplitudeWaveforms(hla.pwleSignal.amplitudes)
                        + loadPWLEWaveforms(hla.pwleSignal.envelopes)
                        + loadBasicPWLEWaveforms(hla.pwleSignal.basicEnvelopes),
                loadAudio(resourcesDirectoryPath, hla.pwleSignal),
                loadOGGs(resourcesDirectoryPath, hla.pwleSignal.oggs),
                hla.pwleSignal.duration
            )

            // No haptic support
            else -> EMPTY_LOADED_HLA
        }
        completionCallback(loadedHLA.copy(effects = EffectCombiner.combine(loadedHLA.effects)))
    }

    private fun loadLegacyHLA(
        audioDirectoryPath: PossiblyZippedDirectory,
        hla: LegacyHLA,
        completionCallback: (loadedHLA: LoadedHLA) -> Unit
    ) {
        // Map the LegacyHLA to a WaveformSignal
        val amplitudeWaveform = AmplitudeWaveform(
            hla.Timings,
            hla.Amplitudes,
            hla.Repeat,
            0
        )

        val audio = firstAudioOf(hla.Audios.map { HlaAudio(it.Time.toLong(), it.Filename) })

        return loadParsedHLA(
            audioDirectoryPath,
            HLA(
                3,
                hla.ProjectName,
                hla.TrackName,
                WaveformSignal(
                    emptyList(),
                    listOf(amplitudeWaveform),
                    hla.Duration,
                    audio
                ),
                WaveformSignal(
                    emptyList(),
                    listOf(amplitudeWaveform),
                    hla.Duration,
                    audio
                ),
                OGGSignal(
                    emptyList(),
                    listOf(amplitudeWaveform),
                    emptyList(),
                    hla.Duration,
                    audio
                ),
                PWLESignal(
                    emptyList(),
                    listOf(amplitudeWaveform),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    hla.Duration,
                    audio
                )
            ),
            completionCallback
        )
    }

    /**
     * Play loaded audio from the start, and effects and OGGs, each at its offset.
     *
     * @param completionCallback A callback to be called when the playback is complete
     * @return A callback that when called aborts the playback
     */
    fun playLoadedHLA(loadedHLA: LoadedHLA, completionCallback: () -> Unit): () -> Unit =
        playLoadedHLA(loadedHLA, 0, 1f, completionCallback)

    /**
     * Play loaded audio from the start, and effects and OGGs, each at its offset, from
     * [startPosition] on.
     *
     * Audio and OGGs that start before [startPosition] start part-way through. Effects that start
     * before it don't play, as vibration effects can't start part-way through.
     *
     * @param startPosition Where to start playing, in milliseconds from the start of [loadedHLA]
     * @param audioVolume The volume of the audio, and of the audio channels of the OGGs, from 0 for
     * silence to 1 for the audio as it is. The haptic channels of the OGGs play as they are
     * @param completionCallback A callback to be called when the playback is complete
     * @return A callback that when called aborts the playback
     */
    fun playLoadedHLA(
        loadedHLA: LoadedHLA,
        startPosition: Long,
        audioVolume: Float,
        completionCallback: () -> Unit
    ): () -> Unit = playLoadedHLA(loadedHLA, startPosition, audioVolume, 1f, {}, completionCallback)

    /**
     * Play loaded audio from the start, and effects and OGGs, each at its offset, from
     * [startPosition] on.
     *
     * Audio and OGGs that start before [startPosition] start part-way through. Effects that start
     * before it don't play, as vibration effects can't start part-way through.
     *
     * Audio and OGGs that aren't preloaded, are too long to preload, start part-way through or are
     * stretched play with MediaPlayers, which take a while to prepare and to be heard. The playback
     * then starts once those that start right away are heard, so that everything plays in sync.
     *
     * @param startPosition Where to start playing, in milliseconds from the start of [loadedHLA]
     * @param audioVolume The volume of the audio, and of the audio channels of the OGGs, from 0 for
     * silence to 1 for the audio as it is. The haptic channels of the OGGs play as they are
     * @param audioDurationScale What to multiply the duration of the audio with, keeping its pitch,
     * such as 2 to play it half as fast. In [AUDIO_DURATION_SCALES]. OGGs play as they are, and
     * [LoadedHLA.duration] is to include the audio's scaled duration
     * @param startCallback Called on the main thread when [startPosition] plays, unless the
     * playback is aborted before. Start what plays along with the playback here, to keep it in sync
     * @param completionCallback A callback to be called when the playback is complete
     * @return A callback that when called aborts the playback
     */
    fun playLoadedHLA(
        loadedHLA: LoadedHLA,
        startPosition: Long,
        audioVolume: Float,
        audioDurationScale: Float,
        startCallback: () -> Unit,
        completionCallback: () -> Unit
    ): () -> Unit {
        require(audioDurationScale in AUDIO_DURATION_SCALES) {
            "audioDurationScale must be in $AUDIO_DURATION_SCALES: $audioDurationScale"
        }
        var aborted = false
        val playbacks = Vector<MediaPlayback>()
        val mediaFiles = listOfNotNull(loadedHLA.audio?.let { MediaFile(it.path, false, 0, audioDurationScale) }) +
                loadedHLA.oggs.map { MediaFile(it.uncompressedPath, true, it.startOffset, 1f) }

        // On the main thread, so that MediaPlayers call their listeners there
        handler.post {
            preparePlaybacks(mediaFiles, startPosition, audioVolume) { prepared ->
                playbacks.addAll(prepared.map { it.second })
                if (aborted) {
                    prepared.forEach { it.second.stop() }
                    return@preparePlaybacks
                }

                // MediaPlayers that start right away set when everything else starts
                val pacingPlaybacks = prepared
                    .filter { (file, _) -> file.startOffset <= startPosition }
                    .map { it.second }
                    .filterIsInstance<MediaPlayerPlayback>()
                pacingPlaybacks.forEach { it.start() }

                whenHeard(pacingPlaybacks) {
                    if (aborted) {
                        return@whenHeard
                    }
                    // When the start of the HLA would have played, so that it plays from
                    // startPosition now
                    val startTime = SystemClock.uptimeMillis() - startPosition
                    startCallback()

                    for ((file, playback) in prepared.filter { it.second !in pacingPlaybacks }) {
                        handler.postAtTime({
                            if (!aborted) {
                                playback.start()
                            }
                        }, startTime + maxOf(file.startOffset, startPosition))
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        for (oneEffect in loadedHLA.effects.filter { it.startOffset >= startPosition }) {
                            handler.postAtTime({
                                if (!aborted) {
                                    getVibrator(context).vibrate(oneEffect.effect)
                                }
                            }, startTime + oneEffect.startOffset)
                        }
                    }

                    handler.postAtTime({
                        if (!aborted) {
                            completionCallback()
                        }
                    }, startTime + loadedHLA.duration)
                }
            }
        }

        return {
            aborted = true
            // Of a copy, as preparing may add more
            playbacks.toList().forEach { it.stop() }
            // Leaves the vibrations of other playbacks be
            if (loadedHLA.effects.isNotEmpty()) {
                getVibrator(context).cancel()
            }
            completionCallback()
        }
    }

    /**
     * Prepares a playback of each of [mediaFiles] from where a playback from [startPosition] starts
     * it.
     *
     * @param volume The volume of the audio, from 0 for silence to 1 for the audio as it is
     * @param onPrepared Called once all are prepared, with each file that can play and its
     * playback, in the order of [mediaFiles]
     */
    private fun preparePlaybacks(
        mediaFiles: List<MediaFile>,
        startPosition: Long,
        volume: Float,
        onPrepared: (List<Pair<MediaFile, MediaPlayback>>) -> Unit
    ) {
        if (mediaFiles.isEmpty()) {
            onPrepared(emptyList())
            return
        }
        val playbacks = arrayOfNulls<MediaPlayback>(mediaFiles.size)
        var unpreparedCount = mediaFiles.size
        mediaFiles.forEachIndexed { index, file ->
            preparePlayback(file, file.positionAt(startPosition), volume) { playback ->
                playbacks[index] = playback
                unpreparedCount--
                if (unpreparedCount == 0) {
                    onPrepared(mediaFiles.zip(playbacks).mapNotNull { (file, playback) ->
                        playback?.let { file to it }
                    })
                }
            }
        }
    }

    /**
     * Prepares a playback of [file] from [position], in milliseconds since its start. Plays sounds
     * that SoundPool loaded from it with SoundPool, and others with MediaPlayers.
     *
     * @param volume The volume of the audio, from 0 for silence to 1 for the audio as it is. The
     * haptic channels play as they are
     * @param onPrepared Called with the playback once it's prepared, or with null if it can't play
     */
    private fun preparePlayback(
        file: MediaFile,
        position: Long,
        volume: Float,
        onPrepared: (MediaPlayback?) -> Unit
    ) {
        val pooledSound = loadedSoundOf(file.path)
        // SoundPool can't start part-way through, stretch keeping the pitch, nor route haptic
        // channels apart from the audio
        if (pooledSound != null && position == 0L && file.durationScale == 1f &&
            (isBuiltInSpeakerSelected || !file.hasHapticChannels)
        ) {
            onPrepared(SoundPoolPlayback(soundPool, pooledSound.soundId, volume))
            return
        }

        val players = try {
            createMediaPlayers(file, volume)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open media file: ${file.path}", e)
            onPrepared(null)
            return
        }
        MediaPlayerPlayback.prepare(players, position, file.durationScale, onPrepared)
    }

    /**
     * Creates the MediaPlayers that play [file] together. Haptic channels play on the built-in
     * speaker's vibrator, so where audio plays elsewhere, one player plays them there and another
     * the audio.
     *
     * @param volume The volume of the audio, from 0 for silence to 1 for the audio as it is. The
     * haptic channels play as they are
     * @throws IOException if [file] can't be opened
     */
    private fun createMediaPlayers(file: MediaFile, volume: Float): List<MediaPlayer> {
        val players = mutableListOf<MediaPlayer>()
        try {
            val mainPlayer = MediaPlayer().also { players += it }
            val mainPlayerAudioAttributes =
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType((AudioAttributes.CONTENT_TYPE_SONIFICATION))
            if (file.hasHapticChannels && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mainPlayerAudioAttributes.setHapticChannelsMuted(false)
            }
            mainPlayer.setAudioAttributes(mainPlayerAudioAttributes.build())
            mainPlayer.setDataSource(file.path)
            mainPlayer.setVolume(volume, volume)

            if (!file.hasHapticChannels || isBuiltInSpeakerSelected) {
                return players
            }

            // We need to route the haptics to the built-in speaker! Let's find it
            val builtInSpeaker = context.getSystemService(AudioManager::class.java)
                ?.getDevices(GET_DEVICES_OUTPUTS)
                ?.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: return players

            // Turn off audio on the built-in speaker, we only need haptics there
            mainPlayer.setVolume(0f, 0f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mainPlayer.setPreferredDevice(builtInSpeaker)
            }

            val audioPlayer = MediaPlayer().also { players += it }
            val audioPlayerAttributesBuilder =
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType((AudioAttributes.CONTENT_TYPE_SONIFICATION))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audioPlayerAttributesBuilder.setHapticChannelsMuted(true)
            }
            audioPlayer.setAudioAttributes(audioPlayerAttributesBuilder.build())
            audioPlayer.setDataSource(file.path)
            audioPlayer.setVolume(volume, volume)
            return players
        } catch (e: IOException) {
            players.forEach { it.release() }
            throw e
        }
    }

    /**
     * Calls [onHeard] on the main thread once all of the started [playbacks] are heard, or after
     * [MAX_HEARD_WAIT_MS] if they aren't by then.
     */
    private fun whenHeard(playbacks: List<MediaPlayerPlayback>, onHeard: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + MAX_HEARD_WAIT_MS
        fun check() {
            if (playbacks.all { it.isHeard }) {
                onHeard()
            } else if (SystemClock.uptimeMillis() >= deadline) {
                Log.w(TAG, "Media wasn't heard within $MAX_HEARD_WAIT_MS ms, playing the rest anyway")
                onHeard()
            } else {
                handler.postDelayed({ check() }, HEARD_CHECK_INTERVAL_MS)
            }
        }
        check()
    }

    /**
     * Play a .hla file.
     *
     * @param hlaPath The path to the .hla file. Can be an absolute path in the filesystem or a path
     * in the assets directory
     * @param completionCallback A callback to be called when the playback is complete
     */
    fun playHLA(hlaPath: String, completionCallback: () -> Unit) {
        loadHLA(hlaPath) { loadedHLA ->
            playLoadedHLA(loadedHLA, completionCallback)
        }
    }

    /**
     * Load a .hla file, finding its audio files next to it.
     *
     * @param hlaPath The path to the .hla file. Can be an absolute path in the filesystem or a path
     * in the assets directory
     * @param completionCallback Called with the loaded .hla file, which is empty if it can't be found
     */
    internal fun loadHLA(hlaPath: String, completionCallback: (loadedHLA: LoadedHLA) -> Unit) {
        val hlaDirectory = parentDirectoryOf(hlaPath)
        hlaDirectory.getChild(File(hlaPath).name)?.let { hlaFile ->
            loadHLAImpl(hlaDirectory, hlaFile, completionCallback)
        } ?: run {
            Log.e(TAG, "No .hla file found at: $hlaPath")
            completionCallback(EMPTY_LOADED_HLA)
        }
    }

    /**
     * @param path An absolute path in the filesystem or a path in the assets directory
     * @return The directory containing [path]
     */
    private fun parentDirectoryOf(path: String): PossiblyZippedDirectory {
        return PossiblyZippedDirectory(
            ZIP_CACHE_SUBDIRECTORY, File(path).parent ?: "", false, context
        )
    }

    /**
     * Resolves [path] to a file in the filesystem, extracting it from the assets if needed.
     *
     * @param path An absolute path in the filesystem or a path in the assets directory
     * @return The resolved file, or null if there is no file at [path]
     */
    internal fun resolveFile(path: String): File? {
        return parentDirectoryOf(path).getChild(File(path).name)
    }

    private fun loadHAC(hacPath: String, completionCallback: (loadedHLA: LoadedHLA) -> Unit) {
        val hacDirectory = PossiblyZippedDirectory(
            ZIP_CACHE_SUBDIRECTORY, hacPath, true, context
        )
        val hlaFile = hacDirectory.getChild("main.hla")
        hlaFile?.let {
            loadHLAImpl(hacDirectory, it, completionCallback)
        } ?: run {
            Log.e(TAG, "No .hla file found in .hac file: $hacPath")
            completionCallback(EMPTY_LOADED_HLA)
        }
    }

    /**
     * Play a .hac file.
     *
     * @param hacPath The path to the .hac file. Can be an absolute path in the filesystem or a path
     * in the assets directory
     * @param completionCallback A callback to be called when the playback is complete
     * @return A callback that when called aborts the playback
     */
    fun playHAC(hacPath: String, completionCallback: () -> Unit): () -> Unit {
        var aborted = false
        var abortCallback: () -> Unit = {}
        loadHAC(hacPath) {
            if (!aborted) {
                abortCallback = playLoadedHLA(it, completionCallback)
            }
        }
        return {
            aborted = true
            abortCallback()
        }
    }

    private fun loadHLAImpl(
        audioDirectoryPath: PossiblyZippedDirectory,
        hlaFile: File,
        completionCallback: (loadedHLA: LoadedHLA) -> Unit
    ) {
        val data = hlaFile.readText(StandardCharsets.UTF_8)

        // Parse the file to a JSON
        val moshi = Moshi.Builder().build()
        val genericJSONAdapter = moshi.adapter(Map::class.java)

        try {
            val jsonObject = genericJSONAdapter.fromJson(data) ?: emptyMap<String, Any>()
            val version = (jsonObject["version"] as? Number)?.toInt()
            if (version != null) {
                if (version != 2 && version != 3) {
                    // Invalid version
                    throw Exception("Unknown HLA version: $version")
                }
                val hla = moshi.adapter(HLA::class.java).fromJson(data)
                    ?: throw Exception("Failed to parse the file as HLA version $version")

                if (version == 2) {
                    Log.w(TAG, "HLA version 2 is deprecated in favor of version 3: ${hlaFile.name}")
                    loadParsedHLA(audioDirectoryPath, hla.withAudiosOfVersion2(), completionCallback)
                } else {
                    loadParsedHLA(audioDirectoryPath, hla, completionCallback)
                }
            } else {
                // It's likely a LegacyHLA file
                val legacyHLAAdapter = moshi.adapter(LegacyHLA::class.java)
                val legacyHLA = legacyHLAAdapter.fromJson(data)
                    ?: throw Exception("Failed to parse the file as LegacyHLA")
                loadLegacyHLA(audioDirectoryPath, legacyHLA, completionCallback)
            }
        } catch (e: Exception) {
            // Failed to parse the file
            Log.e(TAG, "Failed to parse the HLA file", e)
            completionCallback(EMPTY_LOADED_HLA)
        }
    }

    /**
     * @return The format of the first audio track of the file at [path], or null if it has none or
     * can't be read
     */
    private fun audioFormatOf(path: String): MediaFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read the audio format of: $path", e)
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * @return The duration of the audio in [format] in milliseconds, or null if it doesn't state it
     */
    private fun durationOf(format: MediaFormat): Long? =
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION) / MICROSECONDS_PER_MILLISECOND
        } else {
            null
        }

    private fun preloadUncompressedPathOGG(
        uncompressedPath: String
    ) {
        if (hapticsCapabilities.supportsAudioCoupled) {
            loadToSoundPool(uncompressedPath)
        }
    }

    /**
     * Preload a .hac file for lower latency playback.
     *
     * The file will be parsed and its contents loaded, so future calls to [play] or [play] will
     * experience substantially less latency. Call [unload] to release the resources.
     *
     * Also supports legacy directory-based approach, which is now deprecated. See [play] for more
     *
     * @param directoryOrHacPath The path to the .hac file.  Can be an absolute path in the
     * filesystem or a path in the assets directory
     */
    fun preload(
        directoryOrHacPath: String
    ) {
        if (directoryOrHacPath.endsWith(HAC_EXTENSION)) {
            if (hacMap.containsKey(directoryOrHacPath)) {
                // Already loaded
                Log.w(
                    TAG,
                    "Tried to load an already loaded .hac file: $directoryOrHacPath. Call unload() first."
                )
                return
            }

            loadHAC(directoryOrHacPath) {
                // Store the result in the preloaded map
                hacMap[directoryOrHacPath] = it

                // Try to preload the audio and oggs as well
                it.audio?.let { audio ->
                    loadToSoundPool(audio.path)
                }
                it.oggs.forEach { ogg ->
                    preloadUncompressedPathOGG(ogg.uncompressedPath)
                }
            }
        } else {
            // Legacy directory approach
            preloadOGG(directoryPathToOGG(directoryOrHacPath))
        }
    }

    /**
     * Preload an OGG file for lower latency playback.
     *
     * The OGG file can be preloaded only if its uncompressed size is less than 1 MB. In that case,
     * future calls to [playOGG] will experience substantially less latency. Call [unloadOGG] to
     * release the resources.
     *
     * @param oggPath The path to the OGG file. Can be an absolute path in the filesystem or a path
     * in the assets directory
     */
    fun preloadOGG(
        oggPath: String
    ) {
        resolveFile(oggPath)?.let { preloadUncompressedPathOGG(it.absolutePath) }
            ?: Log.e(TAG, "No OGG file found at: $oggPath")
    }

    /**
     * Preload an audio file for lower latency playback with [playLoadedHLA].
     *
     * The audio file can be preloaded only if its uncompressed size is less than 1 MB, such as 5 s
     * of 48 kHz stereo audio. In that case, it starts playing from its start with substantially
     * less latency. Call [unloadAudio] to release the resources.
     *
     * @param audioPath The path to the audio file. Can be an absolute path in the filesystem or a
     * path in the assets directory
     */
    fun preloadAudio(audioPath: String) {
        resolveFile(audioPath)?.let { loadToSoundPool(it.absolutePath) }
            ?: Log.e(TAG, "No audio file found at: $audioPath")
    }

    private fun unloadFromSoundPool(path: String) {
        poolMap.remove(path)?.let {
            soundPool.unload(it.soundId)
            loadedSoundsSet.remove(it.soundId)
        }
    }

    /**
     * Unload a .hac file previously loaded with [preload]
     *
     * Also supports legacy directory-based approach, which is now deprecated. See [play] for more
     *
     * @param directoryOrHacPath The path to the .hac file.  Can be an absolute path in the
     * filesystem or a path in the assets directory
     */
    fun unload(directoryOrHacPath: String) {
        if (directoryOrHacPath.endsWith(HAC_EXTENSION)) {
            hacMap[directoryOrHacPath]?.let {
                // Unload the included audio and oggs
                it.audio?.let { audio ->
                    unloadFromSoundPool(audio.path)
                }
                it.oggs.forEach { ogg ->
                    unloadFromSoundPool(ogg.uncompressedPath)
                }

                // Remove from the preload map
                hacMap.remove(directoryOrHacPath)
            } ?: run {
                Log.w(TAG, "Tried to unload a non-loaded .hac file: $directoryOrHacPath")
            }
        } else {
            // Legacy directory approach
            unloadOGG(directoryPathToOGG(directoryOrHacPath))
        }
    }

    /**
     * Unload an OGG file previously loaded with [preloadOGG]
     *
     * @param oggPath The path to the OGG file. Can be an absolute path in the filesystem or a path
     * in the assets directory
     */
    fun unloadOGG(oggPath: String) {
        resolveFile(oggPath)?.let { unloadFromSoundPool(it.absolutePath) }
    }

    /**
     * Unload an audio file previously loaded with [preloadAudio]
     *
     * @param audioPath The path to the audio file. Can be an absolute path in the filesystem or a
     * path in the assets directory
     */
    fun unloadAudio(audioPath: String) {
        resolveFile(audioPath)?.let { unloadFromSoundPool(it.absolutePath) }
    }

    /**
     * Unload all resources previously loaded with [preload], [preloadOGG] or [preloadAudio]
     */
    fun unloadAll() {
        // Release all loaded sounds
        soundPool.release()

        // Recreate the SoundPool
        setUpSoundPool()

        // The new SoundPool reuses the sound IDs
        poolMap.clear()
        loadedSoundsSet.clear()
    }

    /**
     * @return The sound SoundPool loaded from [path], unless it isn't loaded or still loading
     */
    private fun loadedSoundOf(path: String): PooledSound? =
        poolMap[path]?.takeIf { loadedSoundsSet.contains(it.soundId) }

    /**
     * @return Whether SoundPool loaded the file at [path], so that it plays with little latency
     */
    internal fun isLoadedToSoundPool(path: String): Boolean = loadedSoundOf(path) != null

    /**
     * Loads the file at [path] into SoundPool, unless it's loading or loaded already, or too large
     * for SoundPool.
     */
    private fun loadToSoundPool(path: String) {
        if (poolMap.containsKey(path)) {
            return
        }

        val format = audioFormatOf(path) ?: return
        // Truly, we can't tell its size. So to be safe, we need to assume we can't load it
        val durationMs = durationOf(format) ?: return
        val decodedBytes = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toLong() *
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) *
                SOUNDPOOL_BYTES_PER_SAMPLE * durationMs / MILLISECONDS_PER_SECOND
        if (decodedBytes < SOUNDPOOL_MAX_BYTES) {
            poolMap[path] = PooledSound(soundPool.load(path, 1), durationMs)
        }
    }

    /**
     * Plays an OGG file.
     *
     * Encoded haptic feedback will be routed to the device vibration actuator for best results.
     *
     * @param oggPath The path to the OGG file. Can be an absolute path in the filesystem or a path
     * in the assets directory
     * @param completionCallback A callback to be called when the playback is complete
     */
    fun playOGG(oggPath: String, completionCallback: () -> Unit) {
        resolveAndPlayOGG(oggPath, completionCallback)
    }

    /**
     * Resolves [oggPath] with [resolveFile] and plays the OGG file there.
     *
     * @return A callback that when called aborts the playback
     */
    private fun resolveAndPlayOGG(oggPath: String, completionCallback: () -> Unit): () -> Unit {
        val oggFile = resolveFile(oggPath) ?: run {
            Log.e(TAG, "No OGG file found at: $oggPath")
            completionCallback()
            return {}
        }
        val path = oggFile.absolutePath
        val durationMs = poolMap[path]?.duration ?: audioFormatOf(path)?.let { durationOf(it) } ?: 0
        val oggOnly = LoadedHLA(emptyList(), null, listOf(UncompressedOGGFile(path, 0)), durationMs)
        return playLoadedHLA(oggOnly, completionCallback)
    }

    /**
     * Play a built-in haptic effect.
     *
     * @param name The name of the built-in effect.  Must be one of the following:
     *  - "Click"
     *  - "Double Click"
     *  - "Heavy Click"
     *  - "Tick"
     */
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