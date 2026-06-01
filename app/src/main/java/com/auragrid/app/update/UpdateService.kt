package com.auragrid.app.update

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * GitHubRelease: Represents the release metadata payload parsed from GitHub.
 */
data class GitHubRelease(
    val tag_name: String,
    val body: String?,
    val assets: List<GitHubAsset>?
)

/**
 * GitHubAsset: Represents downloadable APK asset files inside a Release.
 */
data class GitHubAsset(
    val name: String,
    val size: Long,
    val browser_download_url: String
)

object UpdateService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    interface UpdateCallback {
        fun onUpdateAvailable(remoteVersion: String, downloadUrl: String, notes: String, sizeBytes: Long)
        fun onNoUpdate()
        fun onError(error: String)
    }

    /**
     * Checks for new APK versions on GitHub Releases.
     */
    fun checkForUpdates(context: Context, callback: UpdateCallback) {
        val repoUrl = "https://api.github.com/repos/24kbrother/Aura-Grid-Android/releases/latest"

        Thread {
            try {
                val request = Request.Builder()
                    .url(repoUrl)
                    .header("User-Agent", "AuraGridApp/1.1.0 (Android; Mobile)")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w("UpdateService", "GitHub releases returned error: ${response.code}")
                        callback.onError("Server returned code ${response.code}")
                        return@use
                    }

                    val json = response.body?.string() ?: ""
                    val release = gson.fromJson(json, GitHubRelease::class.java)

                    if (release == null || release.tag_name.isEmpty()) {
                        callback.onNoUpdate()
                        return@use
                    }

                    val localVersion = getLocalVersionName(context)
                    val isNewer = isNewerVersion(localVersion, release.tag_name)

                    if (isNewer) {
                        // Find first apk file in assets, or fall back to first asset
                        val asset = release.assets?.firstOrNull { it.name.endsWith(".apk") } 
                            ?: release.assets?.firstOrNull()

                        if (asset != null) {
                            Log.i("UpdateService", "New update available: ${release.tag_name} (Local: $localVersion)")
                            callback.onUpdateAvailable(
                                remoteVersion = release.tag_name,
                                downloadUrl = asset.browser_download_url,
                                notes = release.body ?: "",
                                sizeBytes = asset.size
                            )
                        } else {
                            Log.w("UpdateService", "New release found but no downloadable asset is available.")
                            callback.onNoUpdate()
                        }
                    } else {
                        Log.i("UpdateService", "Device version $localVersion is up-to-date with remote ${release.tag_name}")
                        callback.onNoUpdate()
                    }
                }
            } catch (e: Exception) {
                Log.e("UpdateService", "Connection error during OTA verification: ${e.message}")
                callback.onError(e.localizedMessage ?: "Network connection error")
            }
        }.start()
    }

    /**
     * Extracts active application package version name.
     */
    private fun getLocalVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * SemVer Comparison Algorithm.
     * Strips "v" prefixes and pre-release identifiers, then executes strict numerical comparison.
     */
    fun isNewerVersion(local: String, remote: String): Boolean {
        try {
            val cleanLocal = local.trim().lowercase().removePrefix("v").split("-")[0]
            val cleanRemote = remote.trim().lowercase().removePrefix("v").split("-")[0]

            val localParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }
            val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }

            val maxLength = maxOf(localParts.size, remoteParts.size)
            for (i in 0 until maxLength) {
                val localVal = localParts.getOrElse(i) { 0 }
                val remoteVal = remoteParts.getOrElse(i) { 0 }
                if (remoteVal > localVal) return true
                if (localVal > remoteVal) return false
            }
        } catch (e: Exception) {
            Log.e("UpdateService", "Version comparison failed: ${e.message}")
        }
        return false
    }
}
