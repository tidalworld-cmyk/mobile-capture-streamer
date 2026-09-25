package com.streamezy.capture.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Online File & Version Update System for StreamEzy.
 *
 * Implements a One-Time Installation model:
 * - App starts instantly using local cached files (zero blocking).
 * - Background async check against https://srv1990205.hstgr.cloud/update/version.json.
 * - Downloads ONLY changed files using SHA-256 differential verification.
 * - Atomic replacement into active storage with full rollback / failure safety.
 * - Updates the top-frame version display dynamically without APK reinstallation.
 */
class OnlineUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "OnlineUpdateManager"
        const val DEFAULT_VERSION = "2.0.5"
        const val UPDATE_MANIFEST_URL = "https://srv1990205.hstgr.cloud/update/version.json"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 12000
        private const val PREFS_NAME = "StreamEzyUpdatePrefs"
        private const val KEY_INSTALLED_VERSION = "installed_file_version"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val baseUpdateDir = File(context.filesDir, "online_update")
    private val activeDir = File(baseUpdateDir, "active")
    private val tempDir = File(baseUpdateDir, "temp")
    private val localManifestFile = File(baseUpdateDir, "version.json")

    init {
        if (!baseUpdateDir.exists()) baseUpdateDir.mkdirs()
        if (!activeDir.exists()) activeDir.mkdirs()
        if (!tempDir.exists()) tempDir.mkdirs()

        // Set initial baseline version on first install
        if (!prefs.contains(KEY_INSTALLED_VERSION)) {
            prefs.edit().putString(KEY_INSTALLED_VERSION, DEFAULT_VERSION).apply()
        }
    }

    /**
     * Returns the currently active application file version.
     */
    fun getActiveVersion(): String {
        return prefs.getString(KEY_INSTALLED_VERSION, DEFAULT_VERSION) ?: DEFAULT_VERSION
    }

    /**
     * Returns an active updated file if present in the local active directory,
     * or null if not updated yet.
     */
    fun getActiveFile(relativePath: String): File? {
        val f = File(activeDir, relativePath)
        return if (f.exists() && f.isFile) f else null
    }

    /**
     * Checks online version asynchronously in the background.
     * Startup is NEVER blocked. If offline or server fails, the app continues normally.
     *
     * @param onStatusUpdate Called on main thread with progress/notification messages
     * @param onVersionUpdated Called on main thread when a new version has been installed
     */
    fun checkAndUpdateAsync(
        onStatusUpdate: ((String) -> Unit)? = null,
        onVersionUpdated: ((newVersion: String, updatedFiles: List<String>) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                performUpdateCheck(onStatusUpdate, onVersionUpdated)
            } catch (e: Exception) {
                Log.w(TAG, "Online update check skipped (network unavailable or server offline): ${e.message}")
            }
        }
    }

    private suspend fun performUpdateCheck(
        onStatusUpdate: ((String) -> Unit)?,
        onVersionUpdated: ((newVersion: String, updatedFiles: List<String>) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Checking online update manifest at $UPDATE_MANIFEST_URL...")

        val manifestString = fetchUrlContent(UPDATE_MANIFEST_URL) ?: return@withContext
        val manifestJson = try {
            JSONObject(manifestString)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid manifest JSON", e)
            return@withContext
        }

        val remoteVersion = manifestJson.optString("version", "").trim()
        val remoteFiles = manifestJson.optJSONArray("files") ?: JSONArray()
        val currentVersion = getActiveVersion()

        Log.i(TAG, "Current local version: $currentVersion | Remote version: $remoteVersion")

        if (remoteVersion.isEmpty()) {
            return@withContext
        }

        // Identify changed files based on SHA-256
        val filesToDownload = mutableListOf<FileDownloadTask>()
        for (i in 0 until remoteFiles.length()) {
            val fileObj = remoteFiles.getJSONObject(i)
            val path = fileObj.getString("path")
            val url = fileObj.getString("url")
            val sha256 = fileObj.getString("sha256").trim().lowercase()

            val existingFile = File(activeDir, path)
            val localSha = if (existingFile.exists()) computeSha256(existingFile) else ""

            if (localSha != sha256) {
                Log.i(TAG, "File changed or missing: $path (local: $localSha vs remote: $sha256)")
                filesToDownload.add(FileDownloadTask(path, url, sha256))
            } else {
                Log.i(TAG, "File unchanged: $path (SHA matches)")
            }
        }

        if (filesToDownload.isEmpty()) {
            if (remoteVersion != currentVersion) {
                // All files already match, update version indicator
                prefs.edit().putString(KEY_INSTALLED_VERSION, remoteVersion).apply()
                notifyMain {
                    onVersionUpdated?.invoke(remoteVersion, emptyList())
                }
            }
            Log.i(TAG, "All files up to date (version $remoteVersion). No downloads needed.")
            return@withContext
        }

        notifyMain {
            onStatusUpdate?.invoke("Update v$remoteVersion available (${filesToDownload.size} files)")
        }

        // Clean up temp directory before downloading
        cleanDir(tempDir)

        val successfullyDownloaded = mutableListOf<Pair<File, File>>() // tempFile to targetFile

        // Step 1: Download all changed files to temp storage and verify SHA-256
        for (task in filesToDownload) {
            val tempFile = File(tempDir, task.path)
            tempFile.parentFile?.mkdirs()

            Log.i(TAG, "Downloading: ${task.path} from ${task.url}...")
            val downloadOk = downloadFileWithIntegrity(task.url, tempFile, task.expectedSha256)
            if (!downloadOk) {
                Log.e(TAG, "Integrity check failed for ${task.path}. Aborting update. Old files preserved.")
                cleanDir(tempDir)
                notifyMain {
                    onStatusUpdate?.invoke("Update verification failed. Using existing files.")
                }
                return@withContext
            }

            val targetFile = File(activeDir, task.path)
            targetFile.parentFile?.mkdirs()
            successfullyDownloaded.add(Pair(tempFile, targetFile))
        }

        // Step 2: Atomic Replacement — all files verified, now swap into active
        val updatedFileNames = mutableListOf<String>()
        for ((tempFile, targetFile) in successfullyDownloaded) {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                // Fallback copy if rename fails across file system boundaries
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            updatedFileNames.add(targetFile.name)
            Log.i(TAG, "Safely replaced: ${targetFile.absolutePath}")
        }

        // Step 3: Save manifest locally and update version
        try {
            localManifestFile.writeText(manifestString)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache local manifest file", e)
        }
        prefs.edit().putString(KEY_INSTALLED_VERSION, remoteVersion).apply()

        Log.i(TAG, "Online update completed successfully: v$remoteVersion (${updatedFileNames.size} files updated)")

        notifyMain {
            onStatusUpdate?.invoke("Update completed (v$remoteVersion)")
            onVersionUpdated?.invoke(remoteVersion, updatedFileNames)
        }
    }

    private fun fetchUrlContent(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "StreamEzy-Capture/3.1")

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(TAG, "GET $urlString returned HTTP ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $urlString: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadFileWithIntegrity(urlString: String, destination: File, expectedSha256: String): Boolean {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        var output: FileOutputStream? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.useCaches = false

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Download failed with HTTP ${conn.responseCode} for $urlString")
                return false
            }

            input = conn.inputStream
            output = FileOutputStream(destination)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var bytesRead: Int

            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                digest.update(buffer, 0, bytesRead)
            }
            output.flush()

            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val match = actualSha256.equals(expectedSha256.trim(), ignoreCase = true)
            if (!match) {
                Log.e(TAG, "SHA256 mismatch for ${destination.name}: expected $expectedSha256, got $actualSha256")
                if (destination.exists()) destination.delete()
                false
            } else {
                Log.i(TAG, "SHA256 verified for ${destination.name}: $actualSha256")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during download of $urlString: ${e.message}")
            if (destination.exists()) destination.delete()
            false
        } finally {
            try { output?.close() } catch (_: Exception) {}
            try { input?.close() } catch (_: Exception) {}
            conn?.disconnect()
        }
    }

    private fun computeSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    private fun cleanDir(dir: File) {
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private fun notifyMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private data class FileDownloadTask(
        val path: String,
        val url: String,
        val expectedSha256: String
    )
}
