package io.hapticlabs.hapticlabsplayer

import android.os.Build
import android.os.VibrationEffect
import android.util.Log

internal object EffectCombiner {
    private const val TAG = "EffectCombiner"

    /**
     * Combines [effects] into a single effect whose timing is handled by the platform through
     * [VibrationEffect.Builder], which is more precise than scheduling each effect separately.
     *
     * Returns [effects] unchanged on platforms without [VibrationEffect.Builder] (before Android
     * 17.2) or if they cannot be combined, e.g. because they overlap or repeat.
     */
    fun combine(effects: List<LoadedEffect>): List<LoadedEffect> {
        // SDK_INT_FULL does not exist before Android 16
        if (effects.size < 2 ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA ||
            Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.CINNAMON_BUN_2
        ) {
            return effects
        }

        val sortedEffects = effects.sortedBy { it.startOffset }
        val firstStartOffset = sortedEffects.first().startOffset
        return try {
            val builder = VibrationEffect.Builder()
            sortedEffects.forEach {
                builder.addEvents(it.startOffset - firstStartOffset, it.effect.events)
            }
            listOf(LoadedEffect(builder.build(), firstStartOffset))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to combine effects, scheduling them separately.", e)
            effects
        }
    }
}
