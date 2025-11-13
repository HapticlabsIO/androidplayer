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
import android.util.Log
import android.os.vibrator.VibratorEnvelopeEffectInfo
import android.os.vibrator.VibratorFrequencyProfile
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import java.io.File
import java.nio.charset.StandardCharsets
import com.google.gson.Gson
import com.google.gson.JsonObject
import androidx.mediarouter.media.MediaRouter
import com.google.gson.JsonSyntaxException
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
    val hapticSupportLevel: Int
)

data class LegacyHlaAudio(
    val Time: Int,
    val Filename: String
)

// Original HLA format before v2
data class LegacyHLA(
    val ProjectName: String,
    val TrackName: String,
    val Duration: Long,
    val RequiredAudioFiles: List<String>,
    val Audios: List<LegacyHlaAudio>,
    val Timings: LongArray,
    val Amplitudes: IntArray,
    val Repeat: Int
)


interface HasDuration {
    val duration: Long
}

interface HasOffset {
    val startOffset: Long
}

data class HlaAudio(
    override val startOffset: Long,
    val filename: String
) : HasOffset

interface ReferencesAudio {
    val audios: List<HlaAudio>
    val requiredAudioFiles: List<String>
}

data class PWLEPoint(
    val priority: Int,
    val frequency: Float,
    val amplitude: Float,
    val time: Long
)

data class AmplitudeWaveform(
    val timings: LongArray,
    val amplitudes: IntArray,
    val repeat: Int,
    override val startOffset: Long
) : HasOffset

data class HapticPrimitive(
    val name: String,
    val scale: Float,
    override val startOffset: Long
) : HasOffset

data class OGGFile(
    val name: String,
    override val startOffset: Long
) : HasOffset

data class PWLEEnvelope(
    val initialFrequency: Float,
    val points: List<PWLEPoint>,
    override val startOffset: Long
) : HasOffset


data class WaveformSignal(
    val primitives: List<HapticPrimitive>,
    val amplitudes: List<AmplitudeWaveform>,
    override val duration: Long,
    override val requiredAudioFiles: List<String>,
    override val audios: List<HlaAudio>
) : HasDuration, ReferencesAudio

data class OGGSignal(
    val primitives: List<HapticPrimitive>,
    val amplitudes: List<AmplitudeWaveform>,
    val oggs: List<OGGFile>,
    override val duration: Long,
    override val requiredAudioFiles: List<String>,
    override val audios: List<HlaAudio>
) : HasDuration, ReferencesAudio

data class PWLESignal(
    val primitives: List<HapticPrimitive>,
    val amplitudes: List<AmplitudeWaveform>,
    val oggs: List<OGGFile>,
    val envelopes: List<PWLEEnvelope>,
    override val duration: Long,
    override val requiredAudioFiles: List<String>,
    override val audios: List<HlaAudio>
) : HasDuration, ReferencesAudio

data class HLA2(
    val version: Int,
    val projectName: String,
    val trackName: String,
    val onOffSignal: WaveformSignal,
    val amplitudeSignal: WaveformSignal,
    val oggSignal: OGGSignal,
    val pwleSignal: PWLESignal
)


data class LoadedOGG(
    val soundId: Int,
    val duration: Int,
)

data class LoadedEffect(
    val effect: VibrationEffect,
    override val startOffset: Long
) : HasOffset

data class LoadedAudio(
    val audio: LowLatencyAudioPlayer,
    override val startOffset: Long
) : HasOffset

data class UncompressedOGGFile(
    val uncompressedPath: String,
    override val startOffset: Long
) : HasOffset

data class LoadedHLA(
    val effects: List<LoadedEffect>,
    val audio: List<LoadedAudio>,
    val oggs: List<UncompressedOGGFile>,
    override val duration: Long
) : HasDuration

class HapticlabsPlayer(private val context: Context) {
    private val TAG = "HapticlabsPlayer"

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

    private fun loadPrimitives(primitives: List<HapticPrimitive>): List<LoadedEffect> {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // @TODO Need to fallback here
            Log.w(TAG, "Primitives are not supported on this SDK version, falling back.")
            emptyList()
        } else {
            primitives.map {
                LoadedEffect(
                    VibrationEffect.startComposition().addPrimitive(
                        when (it.name) {
                            "click" -> VibrationEffect.Composition.PRIMITIVE_CLICK
                            "thud" -> VibrationEffect.Composition.PRIMITIVE_THUD
                            "spin" -> VibrationEffect.Composition.PRIMITIVE_SPIN
                            "quickRise" -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                            "slowRise" -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
                            "quickFall" -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
                            "tick" -> VibrationEffect.Composition.PRIMITIVE_TICK
                            "lowTick" -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                            // Default to click if the name is unknown
                            else -> VibrationEffect.Composition.PRIMITIVE_CLICK
                        }, it.scale
                    ).compose(),
                    it.startOffset
                )
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

    private fun loadPWLEWaveforms(pwles: List<PWLEEnvelope>): List<LoadedEffect> {
        // Extracting envelopes array
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA
            || ((
                    hapticsCapabilities.envelopeEffectInfo?.maxSize
                        ?: 0) <= 0)
        ) {
            Log.e(TAG, "No envelope effects supported on this device")
            emptyList<LoadedEffect>()
        } else {
            pwles.map { envelopeData ->
                val startFrequency = envelopeData.initialFrequency.coerceIn(
                    hapticsCapabilities.frequencyResponse?.minFrequencyHz ?: 0f,
                    hapticsCapabilities.frequencyResponse?.maxFrequencyHz ?: 500f
                )

                // Filter those points that have a priority < max supported point count
                val mostRelevantPoints = envelopeData.points.filter { point ->
                    point.priority < (
                            hapticsCapabilities.envelopeEffectInfo?.maxSize
                                ?: 0)
                }

                val envelope = WaveformEnvelopeBuilder().setInitialFrequencyHz(startFrequency)
                var currentTimeInEnvelope = 0L

                mostRelevantPoints.forEach { p ->
                    val safeFrequency = p.frequency.coerceIn(
                        hapticsCapabilities.frequencyResponse?.minFrequencyHz ?: 0f,
                        hapticsCapabilities.frequencyResponse?.maxFrequencyHz ?: 500f
                    )

                    envelope.addControlPoint(
                        p.amplitude,
                        safeFrequency,
                        p.time - currentTimeInEnvelope
                    )
                    currentTimeInEnvelope = p.time
                }

                LoadedEffect(
                    envelope.build(), envelopeData.startOffset
                )
            }
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
                Log.e(TAG, "Failed to find audio file: ${it.filename} in directory: $audioDirectory")
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
                        loadPrimitives(hla.pwleSignal.primitives) + loadAmplitudeWaveforms(hla.pwleSignal.amplitudes) + loadPWLEWaveforms(
                            hla.pwleSignal.envelopes
                        ),
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

    fun playHLA(hlaPath: String, completionCallback: () -> Unit) {
        val uncompressedPath = getUncompressedPath(hlaPath, context)
        File(hlaPath).parent?.let {
            val parentDir = PossiblyZippedDirectory(it, false, context)
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
        val hacDirectory = PossiblyZippedDirectory(hacPath, true, context)
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
        val gson = Gson()
        val jsonObject = gson.fromJson(data, JsonObject::class.java)

        if (jsonObject.has("version")) {
            if (jsonObject.get("version").asInt != 2) {
                // Invalid version
                Log.e(TAG, "Unknown HLA version: ${jsonObject.get("version").asInt}")
                return
            }
            // It's an HLA2 file
            try {
                val hla2 = gson.fromJson(jsonObject, HLA2::class.java)
                loadHLA2(audioDirectoryPath, hla2, completionCallback)
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "Failed to parse the file as HLA2", e)
            }
        } else {
            // It's likely a LegacyHLA file
            try {
                val legacyHLA = gson.fromJson(data, LegacyHLA::class.java)
                loadLegacyHLA(audioDirectoryPath, legacyHLA, completionCallback)
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "Failed to parse the file as LegacyHLA", e)
            }
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

    fun unload(directoryPath: String) {
        if (directoryPath.endsWith(HAC_EXTENSION)) {
            hacMap[directoryPath]?.let {
                // Unload the included oggs
                it.oggs.forEach { ogg ->
                    unloadUncompressedPathOGG(ogg.uncompressedPath)
                }

                // Remove from the preload map
                hacMap.remove(directoryPath)
            } ?: {
                Log.w(TAG, "Tried to unload a non-loaded .hac file: $directoryPath")
            }
        } else {
            // Legacy directory approach
            unloadOGG(directoryPathToOGG(directoryPath))
        }
    }

    fun unloadOGG(oggPath: String) {
        val uncompressedPath = getUncompressedPath(oggPath, context)
        unloadUncompressedPathOGG(uncompressedPath.absolutePath)
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
                LoadedOGG(oggPool.load(uncompressedPath, 1), durationMs)
        }

    }

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