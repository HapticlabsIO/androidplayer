package io.hapticlabs.hapticlabsplayer

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SAMPLE_RATE = 48000
private const val BYTES_PER_SAMPLE = 2
private const val CHECK_INTERVAL_MS = 5L

/**
 * @return Whether [condition], checked on the main thread, is true within [timeoutMs]
 */
internal fun becomesTrueWithin(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        var isTrue = false
        instrumentation.runOnMainSync { isTrue = condition() }
        if (isTrue) {
            return true
        }
        Thread.sleep(CHECK_INTERVAL_MS)
    }
    return false
}

/**
 * Writes silent 16-bit mono 48 kHz audio to a WAV file in the cache directory.
 *
 * @return The WAV file, named [name], lasting [durationMs] milliseconds
 */
internal fun silentWav(context: Context, name: String, durationMs: Int): File {
    val dataSize = SAMPLE_RATE * durationMs / 1000 * BYTES_PER_SAMPLE
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        // PCM, mono
        .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
        .putShort(BYTES_PER_SAMPLE.toShort()).putShort((BYTES_PER_SAMPLE * 8).toShort())
        .put("data".toByteArray()).putInt(dataSize)
    return File(context.cacheDir, name).apply { writeBytes(header.array() + ByteArray(dataSize)) }
}
