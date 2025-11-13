package io.hapticlabs.hapticlabsplayer

import android.content.Context
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import android.os.Build
import java.nio.file.Paths


fun isAssetPath(path: String, context: Context): Boolean {
    return try {
        context.assets.open(path).close()
        true
    } catch (e: IOException) {
        false
    }
}

fun getVibrator(context: Context): Vibrator {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
}

fun getUncompressedPath(path: String, context: Context): File {
    // Try to normalize the path
    val normalizedPath =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Paths.get(path).normalize().toString()
        } else {
            path
        }
    return if (isAssetPath(normalizedPath, context)) {
        getUncompressedAssetPath(normalizedPath, context)
    } else {
     File(normalizedPath)
    }
}

fun getUncompressedAssetPath(assetName: String, context: Context): File {
    val uncompressedDir = File(context.cacheDir, "hapticlabsplayer_uncompressed")
    if (!uncompressedDir.exists()) {
        uncompressedDir.mkdirs()
    }

    val outFile = File(uncompressedDir, assetName)
    val outDir = outFile.parentFile
    if (outDir != null && !outDir.exists()) {
        outDir.mkdirs()
    }

    if (outFile.exists()) {
        return outFile
    }

    // Copy the asset to the uncompressed directory
    try {
        val inputStream: InputStream = context.assets.open(assetName)
        val outputStream = FileOutputStream(outFile)

        val buffer = ByteArray(1024)
        var length: Int
        while (inputStream.read(buffer).also { length = it } > 0) {
            outputStream.write(buffer, 0, length)
        }

        inputStream.close()
        outputStream.close()
    } catch (e: IOException) {
        e.printStackTrace()
        // Handle error
    }

    return outFile
}