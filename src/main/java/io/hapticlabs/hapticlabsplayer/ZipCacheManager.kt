package io.hapticlabs.hapticlabsplayer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

object ZipCacheManager {

    private const val TAG = "ZipCacheManager"
    private const val SUCCESS_MARKER_FILE = ".success"

    /**
     * Smart-unzips a file from either the assets folder or a fully qualified path.
     * It auto-detects if the path is a simple asset name or a full file path.
     * If the content is already cached, this method does nothing.
     *
     * @param path The name of the zip file in 'assets' (e.g., "data.zip") OR a fully qualified path (e.g., "/sdcard/data.zip").
     * @param context The application context.
     * @param cacheDirName The name of the subdirectory to create in the app's cache.
     * @return The File object pointing to the cache directory where contents are located, or null on failure.
     */
    fun unzipIfNeeded(path: String, context: Context, cacheDirName: String): File? {
        // A simple heuristic: if the path contains a separator, it's treated as a full path.
        // Otherwise, it's treated as an asset name.
        return if (path.startsWith(File.separator)) {
            unzipFromPathIfNeeded(path, context, cacheDirName)
        } else {
            unzipFromAssetsIfNeeded(path, context, cacheDirName)
        }
    }

    /**
     * Unzips a file from the assets folder into a specific cache directory.
     * If the content is already cached and up-to-date, this method does nothing.
     *
     * @param assetZipFileName The name of the zip file in the 'assets' folder (e.g., "my_data.zip").
     * @param cacheDirName The name of the subdirectory to create in the app's cache (e.g., "my_data_cache").
     * @return The File object pointing to the cache directory where contents are located.
     */
    fun unzipFromAssetsIfNeeded(
        assetZipFileName: String,
        context: Context,
        cacheDirName: String
    ): File? {
        val cacheDir = File(context.cacheDir, cacheDirName)
        if (isCacheValid(cacheDir)) {
            Log.d(TAG, "Cache is valid for '$cacheDirName'. Skipping unzip.")
            return cacheDir
        }

        Log.d(TAG, "Cache is invalid or missing for '$cacheDirName'. Unzipping from assets...")
        prepareCacheDirectory(cacheDir)

        return try {
            context.assets.open(assetZipFileName).use { inputStream ->
                unzip(inputStream, cacheDir)
            }
            cacheDir
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open asset file '$assetZipFileName'", e)
            cleanupFailedAttempt(cacheDir)
            null
        }
    }

    /**
     * Unzips a file from a fully qualified path into a specific cache directory.
     * If the content is already cached and up-to-date, this method does nothing.
     *
     * @param zipFilePath The fully qualified path to the zip file (e.g., "/sdcard/Download/my_data.zip").
     * @param context The application context.
     * @param cacheDirName The name of the subdirectory to create in the app's cache (e.g., "my_data_cache").
     * @return The File object pointing to the cache directory where contents are located.
     */
    fun unzipFromPathIfNeeded(zipFilePath: String, context: Context, cacheDirName: String): File? {
        val zipFile =
            File(zipFilePath) // Note: context is passed for consistency but not used here for file path ops
        if (!zipFile.exists()) {
            Log.e(TAG, "Zip file does not exist at path: $zipFilePath")
            return null
        }

        val cacheDir = File(context.cacheDir, cacheDirName)
        if (isCacheValid(cacheDir)) {
            Log.d(TAG, "Cache is valid for '$cacheDirName'. Skipping unzip.")
            return cacheDir
        }

        Log.d(TAG, "Cache is invalid or missing for '$cacheDirName'. Unzipping from path...")
        prepareCacheDirectory(cacheDir)

        return try {
            FileInputStream(zipFile).use { inputStream ->
                unzip(inputStream, cacheDir)
            }
            cacheDir
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open file from path '$zipFilePath'", e)
            cleanupFailedAttempt(cacheDir)
            null
        }
    }

    private fun isCacheValid(cacheDir: File): Boolean {
        val successMarker = File(cacheDir, SUCCESS_MARKER_FILE)
        return cacheDir.exists() && successMarker.exists()
    }

    private fun prepareCacheDirectory(cacheDir: File) {
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
        cacheDir.mkdirs()
    }

    private fun cleanupFailedAttempt(cacheDir: File) {
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
    }

    /**
     * Core logic to unzip an InputStream into a destination directory.
     */
    private fun unzip(inputStream: InputStream, destinationDir: File) {
        try {
            ZipInputStream(inputStream.buffered()).use { zipInputStream ->
                var zipEntry = zipInputStream.nextEntry
                while (zipEntry != null) {
                    val entryFile = File(destinationDir, zipEntry.name)

                    if (zipEntry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        FileOutputStream(entryFile).use { fileOutputStream ->
                            zipInputStream.copyTo(fileOutputStream)
                        }
                    }
                    zipInputStream.closeEntry()
                    zipEntry = zipInputStream.nextEntry
                }
            }
            // If we reach here, unzipping was successful. Create the marker file.
            val successMarker = File(destinationDir, SUCCESS_MARKER_FILE)
            successMarker.createNewFile()
            Log.d(
                TAG,
                "Successfully unzipped and created success marker in '${destinationDir.name}'."
            )
        } catch (e: IOException) {
            Log.e(TAG, "Unzip operation failed for directory '${destinationDir.name}'", e)
            // Propagate the exception to be handled by the calling function
            throw e
        }
    }
}

/**
 * Represents a directory that may or may not be a zip file.
 * Provides a unified way to access child files, extracting them from a zip archive if necessary.
 *
 * @param directory The file that is either a directory or a zip file.
 */
class PossiblyZippedDirectory(
    private val directory: String,
    private val isZip: Boolean,
    private val context: Context
) {
    private val effectiveDirectory: File? = if (isZip) {
        // Use the zip file name without extension as the cache directory name
        val cacheDirName = File(directory).nameWithoutExtension
        ZipCacheManager.unzipIfNeeded(directory, context, cacheDirName)
    } else {
        File(directory)
    }

    private val isAssetDirectory = !isZip && !directory.startsWith(File.separator)

    fun getChild(childName: String): File? {
        val childFile = effectiveDirectory?.let {
            if (isAssetDirectory) {
                getUncompressedAssetPath(
                    // Cut off the leading / produced by absolutePath because it's an asset directory
                    File(it, childName).absolutePath.substring(1),
                    context
                )
            } else {
                File(it, childName)
            }
        }
        return if (childFile?.exists() == true) childFile else null
    }
}