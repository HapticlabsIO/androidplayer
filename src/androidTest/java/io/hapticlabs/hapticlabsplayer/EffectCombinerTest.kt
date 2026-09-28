package io.hapticlabs.hapticlabsplayer

import android.os.Build
import android.os.VibrationEffect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EffectCombinerTest {

    private val isBuilderSupported =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2

    private fun oneShot(durationMs: Long, startOffset: Long) =
        LoadedEffect(VibrationEffect.createOneShot(durationMs, 255), startOffset)

    private fun basicEnvelope(durationMs: Long, startOffset: Long) = LoadedEffect(
        VibrationEffect.BasicEnvelopeBuilder()
            .addControlPoint(1f, 0.5f, durationMs / 2)
            .addControlPoint(0f, 0.5f, durationMs / 2)
            .build(),
        startOffset
    )

    @Test
    fun combine_singleEffect_returnsItUnchanged() {
        val effects = listOf(oneShot(50, 100))

        assertEquals(effects, EffectCombiner.combine(effects))
    }

    @Test
    fun combine_withoutBuilderSupport_returnsEffectsUnchanged() {
        assumeFalse(isBuilderSupported)
        val effects = listOf(oneShot(50, 0), oneShot(50, 200))

        assertEquals(effects, EffectCombiner.combine(effects))
    }

    @Test
    fun combine_sequentialEffects_returnsOneEffectStartingAtFirstOffset() {
        assumeTrue(isBuilderSupported)
        val early = oneShot(50, 100)
        val late = oneShot(50, 300)

        val combined = EffectCombiner.combine(listOf(late, early))

        val expectedEffect = VibrationEffect.Builder()
            .addEvents(0, early.effect.events)
            .addEvents(200, late.effect.events)
            .build()
        assertEquals(listOf(LoadedEffect(expectedEffect, 100)), combined)
    }

    @Test
    fun combine_overlappingEffects_returnsEffectsUnchanged() {
        assumeTrue(isBuilderSupported)
        val effects = listOf(basicEnvelope(500, 0), basicEnvelope(500, 100))

        assertEquals(effects, EffectCombiner.combine(effects))
    }

    @Test
    fun combine_repeatingEffect_returnsEffectsUnchanged() {
        assumeTrue(isBuilderSupported)
        val repeating = LoadedEffect(
            VibrationEffect.createWaveform(longArrayOf(50, 50), intArrayOf(255, 0), 0),
            0
        )
        val effects = listOf(repeating, oneShot(50, 200))

        assertEquals(effects, EffectCombiner.combine(effects))
    }
}
