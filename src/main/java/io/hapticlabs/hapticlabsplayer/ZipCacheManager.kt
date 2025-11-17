package io.hapticlabs.hapticlabsplayer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

object ZipCacheManager {

    private const val TAG = "ZipCacheManager"
    private const val SUCCESS_MARKER_FILE = ".success"

    private const val HASH_KEY = "source_hash"
    private const val PATH_KEY = "source_path"

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
        return unzipSourceIfNeeded(path, context, cacheDirName)
    }

    /**
     * Checks the directory for cache validity.
     * Invalid caches (outdated or missing) are removed.
     *
     * @param source The name of the zip file in 'assets' (e.g., "data.zip") OR a fully qualified path (e.g., "/sdcard/data.zip").
     * @param cachedDirectory The directory to check.
     * @param context The application context.
     *
     */
    fun dropIfInvalidCache(cachedDirectory: File, context: Context) {
        if (!isCacheValid(context, cachedDirectory)) {
            Log.d(TAG, "Cache at '${cachedDirectory.path}' is outdated (hash mismatch). Removing.")
            cachedDirectory.deleteRecursively()
        }
    }

    /**
     * Traverses a specific subdirectory within the app's cache and removes any invalid caches found within it.
     * A cache is considered invalid if its source file is no longer present,
     * or if the source file's hash has changed. This function will not touch files or directories
     * outside of the specified `cacheSubDirName`.
     *
     * @param context The application context.
     * @param cacheSubDirName The name of the subdirectory within the app's cache to scan.
     */
    fun dropInvalidCachesIn(context: Context, cacheSubDirName: String) {
        val subDir = File(context.cacheDir, cacheSubDirName)
        if (!subDir.exists() || !subDir.isDirectory) {
            return
        }

        subDir.listFiles()?.forEach { file ->
            // We only care about directories, as our cache logic creates subdirectories.
            if (file.isDirectory) {
                dropIfInvalidCache(file, context)
            }
        }
    }

    private fun unzipSourceIfNeeded(
        sourcePath: String,
        context: Context,
        cacheDirName: String
    ): File? {
        val cacheDir = File(context.cacheDir, cacheDirName)
        if (isCacheValid(context, cacheDir, sourcePath)) {
            Log.d(TAG, "Cache is valid for '$cacheDirName'. Skipping unzip.")
            return cacheDir
        }

        Log.d(
            TAG,
            "Cache is invalid or missing for '$cacheDirName'. Unzipping from '$sourcePath'..."
        )
        prepareCacheDirectory(cacheDir)

        return try {
            val inputStream = when {
                isPath(sourcePath) -> FileInputStream(File(sourcePath))
                else -> context.assets.open(sourcePath)
            }
            inputStream.use { stream -> unzip(stream, cacheDir, sourcePath, context) }
            cacheDir
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open or unzip source '$sourcePath'", e)
            cleanupFailedAttempt(cacheDir)
            null
        }
    }

    private fun isCacheValid(
        context: Context,
        cacheDir: File,
        sourcePath: String? = null
    ): Boolean {
        val propertiesFile = File(cacheDir, SUCCESS_MARKER_FILE)
        if (!cacheDir.exists() || !propertiesFile.exists()) {
            return false
        }

        val properties = Properties()
        try {
            properties.load(FileInputStream(propertiesFile))
        } catch (e: IOException) {
            Log.w(TAG, "Could not read cache properties file. Assuming invalid.", e)
            return false
        }

        val cachedHash = properties.getProperty(HASH_KEY)
        val cachedPath = properties.getProperty(PATH_KEY)
        if (cachedHash == null || cachedPath == null) {
            return false // Invalid properties
        }

        // For non-asset paths, check if the source file still exists.
        if (isPath(cachedPath) && !File(cachedPath).exists()) {
            Log.d(TAG, "Source file for cache '$cachedPath' has been removed. Invalidating cache.")
            return false
        }

        // If a source path is provided, we must also check that it matches the cached path.
        // This handles cases where the same cache directory name is reused for a different source file.
        if (sourcePath != null && sourcePath != cachedPath) {
            Log.d(
                TAG,
                "Cache source path mismatch. Expected '$sourcePath', found '$cachedPath'. Invalidating cache."
            )
            return false
        }

        val currentHash = computeSourceHash(cachedPath, context)

        return currentHash == cachedHash
    }

    private fun isPath(path: String) = path.startsWith(File.separator)


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
    private fun unzip(
        inputStream: InputStream,
        destinationDir: File,
        sourcePath: String,
        context: Context
    ) {
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
            // If we reach here, unzipping was successful. Create the marker file with hash and path.
            val currentHash = computeSourceHash(sourcePath, context) ?: run {
                throw IOException("Could not compute hash for $sourcePath")
            }

            val properties = Properties()
            properties.setProperty(HASH_KEY, currentHash)
            properties.setProperty(PATH_KEY, sourcePath)

            val propertiesFile = File(destinationDir, SUCCESS_MARKER_FILE)
            properties.store(FileOutputStream(propertiesFile), "Cache metadata")
            Log.d(
                TAG,
                "Successfully unzipped and created success marker in '${destinationDir.name}' for source '$sourcePath'."
            )
        } catch (e: IOException) {
            Log.e(TAG, "Unzip operation failed for directory '${destinationDir.name}'", e)
            // Propagate the exception to be handled by the calling function
            throw e
        }
    }

    private fun computeSourceHash(sourcePath: String, context: Context): String? {
        return if (isPath(sourcePath)) {
            computeFileHash(File(sourcePath))
        } else {
            computeAssetHash(sourcePath, context)
        }
    }

    private fun computeAssetHash(assetName: String, context: Context): String? {
        return try {
            context.assets.open(assetName).use { computeStreamHash(it) }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to compute hash for asset '$assetName'", e)
            null
        }
    }

    private fun computeFileHash(file: File): String? {
        return if (!file.exists()) null else
            try {
                FileInputStream(file).use { computeStreamHash(it) }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to compute hash for file '${file.path}'", e)
                null
            }
    }

    private fun computeStreamHash(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().fold("") { str, it -> str + "%02x".format(it) }
    }
}

/**
 * Represents a directory that may or may not be a zip file.
 * Provides a unified way to access child files, extracting them from a zip archive if necessary.
 *
 * @param source The file that is either a directory or a zip file.
 */
class PossiblyZippedDirectory(
    private val cacheSubDirName: String,
    private val source: String,
    private val isZip: Boolean,
    private val context: Context
) {
    private val effectiveDirectory: File? = if (isZip) {
        // Use the zip file name without extension as the cache directory name
        val cacheDirName = File(cacheSubDirName, File(source).nameWithoutExtension)
        ZipCacheManager.unzipIfNeeded(source, context, cacheDirName.path)
    } else {
        File(source)
    }

    private val isAssetDirectory = !isZip && !source.startsWith(File.separator)

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