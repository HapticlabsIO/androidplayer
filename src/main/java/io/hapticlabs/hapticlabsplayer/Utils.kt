package io.hapticlabs.hapticlabsplayer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import android.os.Build
import java.security.MessageDigest


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

/**
 * @return When the app was last installed or updated, in milliseconds since the epoch
 */
private fun getAppUpdateTime(context: Context): Long {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return packageInfo.lastUpdateTime
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

    // Assets can only change with an app update, so copies made since then are up to date
    if (outFile.exists() && outFile.lastModified() >= getAppUpdateTime(context)) {
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

/**
 * Hashes everything [inputStream] provides with SHA-256.
 *
 * @return The hash as lowercase hex digits
 */
fun sha256Hex(inputStream: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    var bytesRead: Int
    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        digest.update(buffer, 0, bytesRead)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
