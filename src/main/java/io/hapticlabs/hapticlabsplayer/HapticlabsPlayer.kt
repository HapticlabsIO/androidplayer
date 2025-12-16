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
import android.os.Vibrator
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
import kotlin.math.abs
import kotlin.math.min
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
private data class LegacyHlaAudio(
    val Time: Int,
    val Filename: String
)

@JsonClass(generateAdapter = true)
// Original HLA format before v2
private data class LegacyHLA(
    val ProjectName: String,
    val TrackName: String,
    val Duration: Long,
    val RequiredAudioFiles: List<String>,
    val Audios: List<LegacyHlaAudio>,
    val Timings: LongArray,
    val Amplitudes: IntArray,
    val Repeat: Int
)


private interface HasDuration {
    val duration: Long
}

private interface HasOffset {
    val startOffset: Long
}

@JsonClass(generateAdapter = true)
private data class HlaAudio(
    override val startOffset: Long,
    val filename: String
) : HasOffset

private interface ReferencesAudio {
    val audios: List<HlaAudio>
    val requiredAudioFiles: List<String>
}

@JsonClass(generateAdapter = true)
private data class PWLEPoint(
    val priority: Int,
    val frequency: Float,
    val amplitude: Float,
    val time: Long
)

@JsonClass(generateAdapter = true)
private data class BasicPWLEPoint(
    val priority: Int,
    val sharpness: Float,
    val intensity: Float,
    val time: Long
)

@JsonClass(generateAdapter = true)
private data class AmplitudeWaveform(
    val timings: LongArray,
    val amplitudes: IntArray,
    val repeat: Int,
    override val startOffset: Long
) : HasOffset

@JsonClass(generateAdapter = true)
private data class HapticPrimitive(
    val name: String,
    val scale: Float,
    override val startOffset: Long
) : HasOffset

@JsonClass(generateAdapter = true)
private data class OGGFile(
    val name: String,
    override val startOffset: Long
) : HasOffset

@JsonClass(generateAdapter = true)
private data class PWLEEnvelope(
    val initialFrequency: Float,
    val points: List<PWLEPoint>,
    override val startOffset: Long
) : HasOffset

@JsonClass(generateAdapter = true)
private data class BasicPWLEEnvelope(
    val initialSharpness: Float,
    val points: List<BasicPWLEPoint>,
    override val startOffset: Long
) : HasOffset

@JsonClass(generateAdapter = true)
private data class WaveformSignal(
    val primitives: List<HapticPrimitive>,
    val amplitudes: List<AmplitudeWaveform>,
    override val duration: Long,
    override val requiredAudioFiles: List<String>,
    override val audios: List<HlaAudio>
) : HasDuration, ReferencesAudio

@JsonClass(generateAdapter = true)
private data class OGGSignal(
    val primitives: List<HapticPrimitive>,
    val amplitudes: List<AmplitudeWaveform>,
    val oggs: List<OGGFile>,
    override val duration: Long,
    override val requiredAudioFiles: List<String>,
    override val audios: List<HlaAudio>
) : HasDuration, ReferencesAudio

@JsonClass(generateAdapter = true)
private data class PWLESignal(
    val primitives: List<HapticPrimitive>,
    val amplitudes: List<AmplitudeWaveform>,
    val oggs: List<OGGFile>,
    val envelopes: List<PWLEEnvelope>,
    val basicEnvelopes: List<BasicPWLEEnvelope>,
    override val duration: Long,
    override val requiredAudioFiles: List<String>,
    override val audios: List<HlaAudio>
) : HasDuration, ReferencesAudio

@JsonClass(generateAdapter = true)
private data class HLA2(
    val version: Int,
    val projectName: String,
    val trackName: String,
    val onOffSignal: WaveformSignal,
    val amplitudeSignal: WaveformSignal,
    val oggSignal: OGGSignal,
    val pwleSignal: PWLESignal
)


private data class LoadedOGG(
    val soundId: Int,
    val duration: Int,
)

private data class LoadedEffect(
    val effect: VibrationEffect,
    override val startOffset: Long
) : HasOffset

private data class LoadedAudio(
    val audio: LowLatencyAudioPlayer,
    override val startOffset: Long
) : HasOffset

private data class UncompressedOGGFile(
    val uncompressedPath: String,
    override val startOffset: Long
) : HasOffset

private data class LoadedHLA(
    val effects: List<LoadedEffect>,
    val audio: List<LoadedAudio>,
    val oggs: List<UncompressedOGGFile>,
    override val duration: Long
) : HasDuration

class HapticlabsPlayer(private val context: Context) {
    private val TAG = "HapticlabsPlayer"

    private val CACHE_SUBDIRECTORY = "hapticlabsPlayerCache"

    val hapticsCapabilities = determineHapticCapabilities()

    private var mediaPlayer: MediaPlayer
    private var audioPlayer: MediaPlayer
    private var oggPool: SoundPool
    private var poolMap: HashMap<String, LoadedOGG> = HashMap()


    private var hacMap: HashMap<String, LoadedHLA> = HashMap()
    private var loadedSoundsSet: HashSet<Int> = HashSet()
    private var handler: Handler
    private var isBuiltInSpeakerSelected: Boolean = true

    private val SOUNDPOOL_BITS_PER_SAMPLE = 16

    private val HAC_EXTENSION = ".hac"

    /**
     * Initialize the player.
     *
     * Starts observing audio output device changes to handle OGG routing and removes outdated
     * caches.
     */
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
                try {
                    val generator = HapticGenerator.create(mediaPlayer.audioSessionId)
                    generator.setEnabled(false)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Failed to create haptic generator to disable it", e)
                }
            }
        }

        // For the compiler
        oggPool = SoundPool.Builder().build()
        oggPool.release()

        setUpSoundPool()

        // Listen for device speaker selection to determine whether or not haptic playback must
        // be routed to the device speaker explicitly
        handler = Handler(Looper.getMainLooper())
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

        ZipCacheManager.dropInvalidCachesIn(context, CACHE_SUBDIRECTORY)
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
    fun play(directoryOrHACPath: String, completionCallback: () -> Unit) {
        // Check if it's a .hac file
        if (directoryOrHACPath.endsWith(HAC_EXTENSION)) {
            playHAC(directoryOrHACPath, completionCallback)
            return
        }

        // Not a .hac file -> Legacy directory approach

        // Switch by hapticSupportLevel
        when (hapticsCapabilities.hapticSupportLevel) {
            0 -> {
                return // Do nothing
            }

            1 -> {
                val hlaDirectoryPath = File("$directoryOrHACPath/lvl1")
                val hlaFile = File(hlaDirectoryPath, "main.hla")
                return loadHLAImpl(
                    PossiblyZippedDirectory(
                        CACHE_SUBDIRECTORY,
                        hlaDirectoryPath.absolutePath,
                        false,
                        context
                    ), hlaFile
                ) { loadedHLA ->
                    playLoadedHLA2(loadedHLA, completionCallback)
                }
            }

            2 -> {
                val hlaDirectoryPath = File("$directoryOrHACPath/lvl2")
                val hlaFile = File(hlaDirectoryPath, "main.hla")
                return loadHLAImpl(
                    PossiblyZippedDirectory(
                        CACHE_SUBDIRECTORY,
                        hlaDirectoryPath.absolutePath,
                        false,
                        context
                    ), hlaFile
                ) { loadedHLA ->
                    playLoadedHLA2(loadedHLA, completionCallback)
                }
            }

            3, 4 -> {
                val path = directoryPathToOGG(directoryOrHACPath)
                return playOGGImpl(
                    getUncompressedPath(path, context).absolutePath,
                    completionCallback
                )
            }
        }
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
            "lowtick" -> VibrationEffect.createWaveform(
                longArrayOf(12),
                intArrayOf((255 * primitive.scale / 4).toInt()),
                -1
            )

            else -> null
        }
    }

    private fun loadPrimitives(primitives: List<HapticPrimitive>): List<LoadedEffect> {
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
                    if (getVibrator(context).areEffectsSupported(primitiveId)[0] == Vibrator.VIBRATION_EFFECT_SUPPORT_YES) {
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

    private fun loadAudios(
        audioDirectory: PossiblyZippedDirectory,
        audios: List<HlaAudio>
    ): List<LoadedAudio> {
        return audios.mapNotNull {
            audioDirectory.getChild(it.filename)?.let { audioFile ->
                val player = LowLatencyAudioPlayer(audioFile, context)
                player.preload()

                LoadedAudio(
                    player,
                    it.startOffset
                )
            } ?: run {
                Log.e(
                    TAG,
                    "Failed to find audio file: ${it.filename} in directory: $audioDirectory"
                )
                null
            }
        }
    }

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

    private fun loadHLA2(
        resourcesDirectoryPath: PossiblyZippedDirectory,
        hla: HLA2,
        completionCallback: (loadedHLA: LoadedHLA) -> Unit
    ) {
        when (hapticsCapabilities.hapticSupportLevel) {
            0 -> completionCallback(LoadedHLA(emptyList(), emptyList(), emptyList(), 0))
            1 -> {
                completionCallback(
                    LoadedHLA(
                        loadPrimitives(hla.onOffSignal.primitives) + loadAmplitudeWaveforms(hla.onOffSignal.amplitudes),
                        loadAudios(resourcesDirectoryPath, hla.onOffSignal.audios),
                        emptyList(),
                        hla.onOffSignal.duration
                    )
                )
            }

            2 -> {
                completionCallback(
                    LoadedHLA(
                        loadPrimitives(hla.amplitudeSignal.primitives) + loadAmplitudeWaveforms(
                            hla.amplitudeSignal.amplitudes
                        ),
                        loadAudios(resourcesDirectoryPath, hla.amplitudeSignal.audios),
                        emptyList(),
                        hla.amplitudeSignal.duration
                    )
                )
            }

            3 -> {
                completionCallback(
                    LoadedHLA(
                        loadPrimitives(hla.oggSignal.primitives) + loadAmplitudeWaveforms(hla.oggSignal.amplitudes),
                        loadAudios(resourcesDirectoryPath, hla.oggSignal.audios),
                        loadOGGs(resourcesDirectoryPath, hla.oggSignal.oggs),
                        hla.oggSignal.duration
                    )
                )
            }

            4 -> {
                completionCallback(
                    LoadedHLA(
                        loadPrimitives(hla.pwleSignal.primitives)
                                + loadAmplitudeWaveforms(hla.pwleSignal.amplitudes)
                                + loadPWLEWaveforms(hla.pwleSignal.envelopes)
                                + loadBasicPWLEWaveforms(hla.pwleSignal.basicEnvelopes),
                        loadAudios(resourcesDirectoryPath, hla.pwleSignal.audios),
                        loadOGGs(resourcesDirectoryPath, hla.pwleSignal.oggs),
                        hla.pwleSignal.duration
                    )
                )
            }
        }
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

        val requiredAudioFiles = hla.RequiredAudioFiles
        val audios = hla.Audios.map { HlaAudio(it.Time.toLong(), it.Filename) }

        return loadHLA2(
            audioDirectoryPath,
            HLA2(
                2,
                hla.ProjectName,
                hla.TrackName,
                WaveformSignal(
                    emptyList(),
                    listOf(amplitudeWaveform),
                    hla.Duration,
                    requiredAudioFiles,
                    audios
                ),
                WaveformSignal(
                    emptyList(),
                    listOf(amplitudeWaveform),
                    hla.Duration,
                    requiredAudioFiles,
                    audios
                ),
                OGGSignal(
                    emptyList(),
                    listOf(amplitudeWaveform),
                    emptyList(),
                    hla.Duration,
                    requiredAudioFiles,
                    audios
                ),
                PWLESignal(
                    emptyList(),
                    listOf(amplitudeWaveform),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    hla.Duration,
                    requiredAudioFiles,
                    audios
                )
            ),
            completionCallback
        )
    }

    private fun playLoadedHLA2(loadedHLA: LoadedHLA, completionCallback: () -> Unit) {
        // Schedule everything to play back
        val syncDelay = 0

        val startTime = SystemClock.uptimeMillis() + syncDelay

        for (oneAudio in loadedHLA.audio) {
            handler.postAtTime({
                oneAudio.audio.playAudio()
            }, startTime + oneAudio.startOffset)
        }

        for (oneOGG in loadedHLA.oggs) {
            handler.postAtTime({
                this.playOGGImpl(oneOGG.uncompressedPath) {}
            }, startTime + oneOGG.startOffset)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            for (oneEffect in loadedHLA.effects) {
                handler.postAtTime({
                    getVibrator(context).vibrate(oneEffect.effect)
                }, startTime + oneEffect.startOffset)
            }
        }

        handler.postAtTime({
            completionCallback()
        }, startTime + loadedHLA.duration)
    }

    /**
     * Play a .hla file.
     *
     * @param hlaPath The path to the .hla file. Can be an absolute path in the filesystem or a path
     * in the assets directory
     * @param completionCallback A callback to be called when the playback is complete
     */
    fun playHLA(hlaPath: String, completionCallback: () -> Unit) {
        val uncompressedPath = getUncompressedPath(hlaPath, context)
        File(hlaPath).parent?.let {
            val parentDir = PossiblyZippedDirectory(
                CACHE_SUBDIRECTORY, it, false, context
            )
            loadHLAImpl(parentDir, uncompressedPath) { loadedHLA ->
                playLoadedHLA2(loadedHLA, completionCallback)
            }
        } ?: {
            // Failed to obtain parent directory
            Log.e(TAG, "No parent directory found for .hla file: $hlaPath")
            completionCallback()
        }
    }

    private fun loadHAC(hacPath: String, completionCallback: (loadedHLA: LoadedHLA) -> Unit) {
        val hacDirectory = PossiblyZippedDirectory(
            CACHE_SUBDIRECTORY, hacPath, true, context
        )
        val hlaFile = hacDirectory.getChild("main.hla")
        hlaFile?.let {
            loadHLAImpl(hacDirectory, it, completionCallback)
        } ?: {
            // No .hla file found
            Log.e(TAG, "No .hla file found in .hac file: $hacPath")
            completionCallback(
                LoadedHLA(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    0
                )
            )
        }
    }

    /**
     * Play a .hac file.
     *
     * @param hacPath The path to the .hac file. Can be an absolute path in the filesystem or a path
     * in the assets directory
     * @param completionCallback A callback to be called when the playback is complete
     */
    fun playHAC(hacPath: String, completionCallback: () -> Unit) {
        loadHAC(hacPath) {
            playLoadedHLA2(it, completionCallback)
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
                if (version != 2) {
                    // Invalid version
                    throw Exception("Unknown HLA version: $version")
                }
                // It's an HLA2 file
                val hla2Adapter = moshi.adapter(HLA2::class.java)
                val hla2 = hla2Adapter.fromJson(data)
                    ?: throw Exception("Failed to parse the file as HLA2")

                loadHLA2(audioDirectoryPath, hla2, completionCallback)
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
            completionCallback(LoadedHLA(emptyList(), emptyList(), emptyList(), 0))
        }
    }

    private fun canOGGBeLoadedToSoundPool(
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

    private fun preloadUncompressedPathOGG(
        uncompressedPath: String
    ) {
        if (hapticsCapabilities.supportsAudioCoupled) {
            loadOGG(
                uncompressedPath
            )
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

                // Try to preload the oggs as well
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
        val uncompressedPath = getUncompressedPath(oggPath, context)
        preloadUncompressedPathOGG(uncompressedPath.absolutePath)
    }

    private fun unloadUncompressedPathOGG(uncompressedPath: String) {
        val loaded = poolMap[uncompressedPath]
        loaded?.soundId?.let {
            oggPool.unload(it)
            poolMap.remove(uncompressedPath)
            loadedSoundsSet.remove(it)
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
                // Unload the included oggs
                it.oggs.forEach { ogg ->
                    unloadUncompressedPathOGG(ogg.uncompressedPath)
                }

                // Remove from the preload map
                hacMap.remove(directoryOrHacPath)
            } ?: {
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
        val uncompressedPath = getUncompressedPath(oggPath, context)
        unloadUncompressedPathOGG(uncompressedPath.absolutePath)
    }

    /**
     * Unload all resources previously loaded with [preload] or [preloadOGG]
     */
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
        return poolMap[uncompressedPath]?.let {
            // Already loading or loaded
            if (loadedSoundsSet.contains(it.soundId)) it else null
        }
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
                LoadedOGG(oggPool.load(uncompressedPath, 1), durationMs)
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
        val uncompressedPath = getUncompressedPath(oggPath, context)
        playOGGImpl(uncompressedPath.absolutePath, completionCallback)
    }

    private fun playOGGImpl(uncompressedPath: String, completionCallback: () -> Unit) {
        val loadedSound = getOGGSoundId(uncompressedPath)

        if (isBuiltInSpeakerSelected && loadedSound != null) {
            // SoundPool approach
            oggPool.play(loadedSound.soundId, 1f, 1f, 1, 0, 1.0f)
            Log.d(TAG, "Playing OGG from SoundPool: $uncompressedPath")
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
        Log.d(TAG, "Playing OGG from MediaPlayer: $uncompressedPath")

        mediaPlayer.setOnCompletionListener { _ ->
            // Playback completed
            completionCallback()
        }
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