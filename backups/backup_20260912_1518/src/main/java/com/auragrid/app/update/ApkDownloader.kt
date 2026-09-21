package com.auragrid.app.update

import android.app.Activity
import android.util.Log
import android.view.View
import android.widget.Toast
import com.auragrid.app.MainActivity
import com.auragrid.app.databinding.ActivityMainBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * ApkDownloader: Dynamic downloader with dual-channel fault-tolerance.
 * Integrates directly with MainActivity overlay controls.
 */
object ApkDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun startDownload(activity: MainActivity, binding: ActivityMainBinding, downloadUrl: String) {
        activity.runOnUiThread {
            binding.downloadOverlay.visibility = View.VISIBLE
            binding.downloadProgressBar.isIndeterminate = true
            binding.downloadProgressBar.progress = 0
            binding.downloadPercent.text = "0%"
            binding.downloadSpeed.text = "Connecting..."
            binding.downloadStatus.text = "Initializing download..."
        }

        val apkFile = File(activity.cacheDir, "aura_grid_update.apk")
        if (apkFile.exists()) {
            apkFile.delete()
        }

        Thread {
            var success = performDownload(activity, binding, downloadUrl, apkFile)
            if (!success) {
                // Dual-channel fallback acceleration
                val ghpUrl = "https://ghp.ci/$downloadUrl"
                Log.w("ApkDownloader", "Direct download failed. Retrying with acceleration channel: $ghpUrl")
                activity.runOnUiThread {
                    binding.downloadStatus.text = "Direct failed. Initializing accelerator channel..."
                    binding.downloadSpeed.text = "Retrying..."
                }
                success = performDownload(activity, binding, ghpUrl, apkFile)
            }

            activity.runOnUiThread {
                binding.downloadOverlay.visibility = View.GONE
                if (success) {
                    InstallUtil.installApk(activity, apkFile)
                } else {
                    Toast.makeText(activity, "Upgrade download failed after dual-channel attempts.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun performDownload(activity: Activity, binding: ActivityMainBinding, url: String, destFile: File): Boolean {
        var input: InputStream? = null
        var output: FileOutputStream? = null
        var response: okhttp3.Response? = null

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AuraGridApp/1.1.0 (Android; Mobile)")
                .build()

            response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("ApkDownloader", "Server returned error: ${response.code}")
                return false
            }

            val body = response.body ?: return false
            val fileLength = body.contentLength()
            input = body.byteStream()
            output = FileOutputStream(destFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            val startTime = System.currentTimeMillis()

            activity.runOnUiThread {
                binding.downloadProgressBar.isIndeterminate = fileLength <= 0
                binding.downloadStatus.text = "Downloading OTA package..."
            }

            while (input.read(data).also { count = it } != -1) {
                total += count
                output.write(data, 0, count)

                if (fileLength > 0) {
                    val progress = (total * 100 / fileLength).toInt()
                    val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0
                    val speedKbps = if (elapsedTime > 0) (total / 1024.0 / elapsedTime).toInt() else 0
                    val speedText = if (speedKbps > 1024) String.format("%.2f MB/s", speedKbps / 1024.0) else "$speedKbps KB/s"

                    activity.runOnUiThread {
                        binding.downloadProgressBar.progress = progress
                        binding.downloadPercent.text = "$progress%"
                        binding.downloadSpeed.text = speedText
                    }
                } else {
                    val downloadedMb = total / 1024.0 / 1024.0
                    activity.runOnUiThread {
                        binding.downloadPercent.text = String.format("%.1f MB", downloadedMb)
                        binding.downloadSpeed.text = "Downloading..."
                    }
                }
            }

            output.flush()
            Log.i("ApkDownloader", "Download completed successfully: ${destFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e("ApkDownloader", "Download failed for URL $url: ${e.message}")
            return false
        } finally {
            try {
                output?.close()
                input?.close()
                response?.close()
            } catch (ignored: IOException) {}
        }
    }
}
