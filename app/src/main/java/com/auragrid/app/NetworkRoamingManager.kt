package com.auragrid.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Custom Network Manager that implements smart path roaming.
 * Detects whether the LAN server is reachable; if yes, routes traffic locally
 * to achieve sub-10ms response times. Otherwise, falls back to the WAN cloud endpoint.
 */
class NetworkRoamingManager(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    interface RoamingCallback {
        fun onRouteResolved(resolvedUrl: String, isLocal: Boolean)
    }

    /**
     * Probes local network address to resolve optimal web root.
     */
    fun resolveOptimalRoute(lanUrl: String, wanUrl: String, callback: RoamingCallback) {
        if (!isNetworkConnected()) {
            callback.onRouteResolved(wanUrl, false) // Default back to WAN/offline handler
            return
        }

        if (lanUrl.isEmpty()) {
            Log.d("AuraRoaming", "LAN URL is empty, routing directly to WAN: $wanUrl")
            callback.onRouteResolved(wanUrl, false)
            return
        }

        Log.d("AuraRoaming", "Initiating network path resolution...")
        executor.execute {
            val isLanAvailable = testConnection(lanUrl, timeoutMs = 1500)
            mainHandler.post {
                if (isLanAvailable) {
                    Log.d("AuraRoaming", "Resolved optimal route to LAN: $lanUrl")
                    callback.onRouteResolved(lanUrl, true)
                } else {
                    Log.d("AuraRoaming", "LAN connection timed out or unreachable. Routing to WAN: $wanUrl")
                    callback.onRouteResolved(wanUrl, false)
                }
            }
        }
    }

    /**
     * Determines whether the physical device is connected to any internet/intranet network.
     */
    private fun isNetworkConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Performs a lightweight TCP connection probe (HTTP GET /health or /)
     * to check if local server is online.
     */
    private fun testConnection(urlString: String, timeoutMs: Int): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "HEAD" // Keep request payload ultra-low
            connection.connect()
            
            // Any response code between 200 and 499 indicates the server is alive
            val responseCode = connection.responseCode
            responseCode in 200..499
        } catch (e: IOException) {
            Log.w("AuraRoaming", "Ping failed for $urlString: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }
}
