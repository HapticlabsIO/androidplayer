package io.hapticlabs.hapticlabsplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibrationEffect.WaveformEnvelopeBuilder
import android.util.Log
import androidx.annotation.RequiresApi
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import io.hapticlabs.hapticlabsplayer.core.HapticCapabilities
import io.hapticlabs.hapticlabsplayer.core.HapticDevice
import io.hapticlabs.hapticlabsplayer.core.HapticMediaPlayer
import io.hapticlabs.hapticlabsplayer.core.HapticPrimitive
import io.hapticlabs.hapticlabsplayer.core.HasOffset
import io.hapticlabs.hapticlabsplayer.core.LoadedAudio
import io.hapticlabs.hapticlabsplayer.core.LoadedEffect
import io.hapticlabs.hapticlabsplayer.core.UncompressedOGGFile
import io.hapticlabs.hapticlabsplayer.core.getVibrator
import java.io.File
import java.nio.charset.StandardCharsets

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

/** A haptic primitive of an HLA file, see [HapticPrimitive] */
@JsonClass(generateAdapter = true)
internal data class HlaPrimitive(
    val name: String,
    val scale: Float,
    override val startOffset: Long
) : HasOffset {
    fun toHapticPrimitive() = HapticPrimitive(name, scale, startOffset)
}

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
    val primitives: List<HlaPrimitive>,
    val amplitudes: List<AmplitudeWaveform>,
    override val duration: Long,
    override val audio: String? = null,
    override val audios: List<HlaAudio> = emptyList()
) : HasDuration, ReferencesAudio

@JsonClass(generateAdapter = true)
internal data class OGGSignal(
    val primitives: List<HlaPrimitive>,
    val amplitudes: List<AmplitudeWaveform>,
    val oggs: List<OGGFile>,
    override val duration: Long,
    override val audio: String? = null,
    override val audios: List<HlaAudio> = emptyList()
) : HasDuration, ReferencesAudio

@JsonClass(generateAdapter = true)
internal data class PWLESignal(
    val primitives: List<HlaPrimitive>,
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

    private val device = HapticDevice(context)
    private val hapticMediaPlayer = HapticMediaPlayer(context)
    private val vibrator = getVibrator(context)

    /**
     * Describes the haptic capabilities of the current device.
     */
    val hapticsCapabilities: HapticCapabilities = device.capabilities

    private val mediaPlayer: MediaPlayer

    private var hacMap: HashMap<String, LoadedHLA> = HashMap()
    private val handler = Handler(Looper.getMainLooper())

    private val HAC_EXTENSION = ".hac"

    private val EMPTY_LOADED_HLA = LoadedHLA(emptyList(), null, emptyList(), 0)

    /**
     * Initialize the player.
     *
     * Removes outdated caches.
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

        ZipCacheManager.dropInvalidCachesIn(context, ZIP_CACHE_SUBDIRECTORY)
    }

    protected fun finalize() {
        mediaPlayer.release()
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

    /**
     * Load primitives as vibration effects, see [HapticDevice.loadPrimitives].
     */
    fun loadPrimitives(primitives: List<HapticPrimitive>): List<LoadedEffect> = device.loadPrimitives(primitives)

    private fun loadHlaPrimitives(primitives: List<HlaPrimitive>): List<LoadedEffect> =
        device.loadPrimitives(primitives.map { it.toHapticPrimitive() })

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
                loadHlaPrimitives(hla.onOffSignal.primitives) + loadAmplitudeWaveforms(hla.onOffSignal.amplitudes),
                loadAudio(resourcesDirectoryPath, hla.onOffSignal),
                emptyList(),
                hla.onOffSignal.duration
            )

            2 -> LoadedHLA(
                loadHlaPrimitives(hla.amplitudeSignal.primitives) + loadAmplitudeWaveforms(
                    hla.amplitudeSignal.amplitudes
                ),
                loadAudio(resourcesDirectoryPath, hla.amplitudeSignal),
                emptyList(),
                hla.amplitudeSignal.duration
            )

            3 -> LoadedHLA(
                loadHlaPrimitives(hla.oggSignal.primitives) + loadAmplitudeWaveforms(hla.oggSignal.amplitudes),
                loadAudio(resourcesDirectoryPath, hla.oggSignal),
                loadOGGs(resourcesDirectoryPath, hla.oggSignal.oggs),
                hla.oggSignal.duration
            )

            4 -> LoadedHLA(
                loadHlaPrimitives(hla.pwleSignal.primitives)
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
     * Audio and OGGs that aren't preloaded, or are too long to preload, play with MediaPlayers,
     * which take a while to be heard. The effects then start once those are, so that everything
     * plays in sync.
     *
     * @param completionCallback A callback to be called when the playback is complete
     * @return A callback that when called aborts the playback
     */
    fun playLoadedHLA(loadedHLA: LoadedHLA, completionCallback: () -> Unit): () -> Unit {
        // Identifies what this playback scheduled, so that aborting drops it
        val playbackToken = Any()
        val stopMedia = hapticMediaPlayer.play(loadedHLA.audio, loadedHLA.oggs) { startUptime ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                for (oneEffect in loadedHLA.effects) {
                    handler.postAtTime(
                        { vibrator.vibrate(oneEffect.effect) },
                        playbackToken,
                        startUptime + oneEffect.startOffset
                    )
                }
            }
            handler.postAtTime({ completionCallback() }, playbackToken, startUptime + loadedHLA.duration)
        }

        return {
            handler.removeCallbacksAndMessages(playbackToken)
            stopMedia()
            // Leaves the vibrations of other playbacks be
            if (loadedHLA.effects.isNotEmpty()) {
                vibrator.cancel()
            }
            completionCallback()
        }
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
                    hapticMediaPlayer.preloadAudio(audio.path)
                }
                it.oggs.forEach { ogg ->
                    hapticMediaPlayer.preloadOGG(ogg.uncompressedPath)
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
        resolveFile(oggPath)?.let { hapticMediaPlayer.preloadOGG(it.absolutePath) }
            ?: Log.e(TAG, "No OGG file found at: $oggPath")
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
                    hapticMediaPlayer.unload(audio.path)
                }
                it.oggs.forEach { ogg ->
                    hapticMediaPlayer.unload(ogg.uncompressedPath)
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
        resolveFile(oggPath)?.let { hapticMediaPlayer.unload(it.absolutePath) }
    }

    /**
     * Unload all resources previously loaded with [preload] or [preloadOGG]
     */
    fun unloadAll() {
        hapticMediaPlayer.unloadAll()
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
        val durationMs = hapticMediaPlayer.durationMsOf(path) ?: 0
        val oggOnly = LoadedHLA(emptyList(), null, listOf(UncompressedOGGFile(path, 0)), durationMs)
        return playLoadedHLA(oggOnly, completionCallback)
    }

    /**
     * Play a built-in haptic effect, see [HapticDevice.playBuiltIn].
     */
    fun playBuiltIn(name: String) {
        device.playBuiltIn(name)
    }
}
