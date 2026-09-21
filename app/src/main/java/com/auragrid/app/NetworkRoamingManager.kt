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
    private var isMonitoring = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastProbeLanUrl = ""
    private var lastProbeWanUrl = ""
    private var lastCallback: RoamingCallback? = null

    var onWanUrlDiscovered: ((String) -> Unit)? = null
    var onNetworkChangeReProbe: (() -> Unit)? = null

    private val selfHealRunnable = Runnable {
        Log.i("AuraRoaming", "Executing scheduled 4s self-heal probe after network switch/offline...")
        if (lastProbeLanUrl.isNotEmpty() || lastProbeWanUrl.isNotEmpty()) {
            lastCallback?.let { resolveOptimalRoute(lastProbeLanUrl, lastProbeWanUrl, it) }
        }
    }

    interface RoamingCallback {
        fun onRouteResolved(resolvedUrl: String, isLocal: Boolean)
    }

    /**
     * Starts listening for physical network changes (Wi-Fi <-> Cellular) to trigger self-healing roaming.
     */
    fun startNetworkMonitoring() {
        if (isMonitoring) return
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.d("AuraRoaming", "Physical network connection acquired. Scheduling re-probe...")
                    scheduleSelfHealRetry(1500L)
                }

                override fun onLost(network: android.net.Network) {
                    Log.d("AuraRoaming", "Physical network connection lost (e.g. Wi-Fi disconnected). Scheduling 4s cellular self-heal retry...")
                    scheduleSelfHealRetry(4000L)
                }
            }
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            isMonitoring = true
            Log.d("AuraRoaming", "Network change listener registered successfully.")
        } catch (e: Exception) {
            Log.w("AuraRoaming", "Unable to register default network callback: ${e.message}")
        }
    }

    /**
     * Stops listening for network changes.
     */
    fun stopNetworkMonitoring() {
        if (!isMonitoring) return
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            networkCallback = null
            isMonitoring = false
        } catch (e: Exception) {
            Log.w("AuraRoaming", "Error unregistering network callback: ${e.message}")
        }
    }

    /**
     * Schedules a delayed self-heal probe to allow cellular connection / WiFi DNS to settle.
     */
    fun scheduleSelfHealRetry(delayMs: Long = 4000L) {
        mainHandler.removeCallbacks(selfHealRunnable)
        mainHandler.postDelayed(selfHealRunnable, delayMs)
    }

    /**
     * Probes local network address to resolve optimal web root.
     */
    fun resolveOptimalRoute(lanUrl: String, wanUrl: String, callback: RoamingCallback) {
        lastProbeLanUrl = lanUrl
        lastProbeWanUrl = wanUrl
        lastCallback = callback

        if (!isNetworkConnected()) {
            Log.w("AuraRoaming", "Physical device has no active internet/intranet connection. Scheduling retry in 4s...")
            scheduleSelfHealRetry(4000L)
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
                    mainHandler.removeCallbacks(selfHealRunnable)
                    callback.onRouteResolved(lanUrl, true)
                } else {
                    Log.d("AuraRoaming", "LAN connection timed out or unreachable. Routing to WAN: $wanUrl")
                    // If LAN failed while we have WAN, also schedule self-heal check in case cellular just connected
                    if (wanUrl.isNotEmpty()) {
                        scheduleSelfHealRetry(4000L)
                    }
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
     * Performs an intelligent probe:
     * 1. GET /api/v1/auth/status (Zero-auth endpoint, checks health & dynamically extracts external_url)
     * 2. Fallback: GET /health (Standard health check)
     * 3. Fallback: HEAD / (Lightweight ping)
     */
    private fun testConnection(urlString: String, timeoutMs: Int): Boolean {
        val cleanBase = urlString.trimEnd('/')

        // 1. Primary probe: /api/v1/auth/status
        var statusConn: HttpURLConnection? = null
        try {
            val statusUrl = URL("$cleanBase/api/v1/auth/status")
            statusConn = statusUrl.openConnection() as HttpURLConnection
            statusConn.connectTimeout = timeoutMs
            statusConn.readTimeout = timeoutMs
            statusConn.requestMethod = "GET"
            statusConn.setRequestProperty("User-Agent", "AuraGridApp/2.2.5 (Android; Mobile)")
            statusConn.setRequestProperty("Accept", "application/json")
            statusConn.useCaches = false
            statusConn.connect()

            if (statusConn.responseCode == 200) {
                val body = statusConn.inputStream.bufferedReader().use { it.readText() }
                try {
                    val json = org.json.JSONObject(body)
                    if (json.has("external_url")) {
                        val extUrl = json.getString("external_url")
                        if (!extUrl.isNullOrBlank()) {
                            mainHandler.post {
                                onWanUrlDiscovered?.invoke(extUrl.trim())
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore JSON parse error, status 200 is sufficient proof of liveness
                }
                return true
            }
        } catch (e: Exception) {
            // Fallthrough to /health
        } finally {
            statusConn?.disconnect()
        }

        // 2. Secondary probe: /health
        var healthConn: HttpURLConnection? = null
        try {
            val healthUrl = URL("$cleanBase/health")
            healthConn = healthUrl.openConnection() as HttpURLConnection
            healthConn.connectTimeout = timeoutMs
            healthConn.readTimeout = timeoutMs
            healthConn.requestMethod = "GET"
            healthConn.setRequestProperty("User-Agent", "AuraGridApp/2.2.5 (Android; Mobile)")
            healthConn.useCaches = false
            healthConn.connect()

            val code = healthConn.responseCode
            if (code in 200..499) {
                return true
            }
        } catch (e: Exception) {
            // Fallthrough to HEAD ping
        } finally {
            healthConn?.disconnect()
        }

        // 3. Fallback: HEAD /
        var headConn: HttpURLConnection? = null
        return try {
            val rootUrl = URL(urlString)
            headConn = rootUrl.openConnection() as HttpURLConnection
            headConn.connectTimeout = timeoutMs
            headConn.readTimeout = timeoutMs
            headConn.requestMethod = "HEAD"
            headConn.setRequestProperty("User-Agent", "AuraGridApp/2.2.5 (Android; Mobile)")
            headConn.useCaches = false
            headConn.connect()

            val responseCode = headConn.responseCode
            responseCode in 200..499
        } catch (e: IOException) {
            Log.w("AuraRoaming", "Ping failed for $urlString: ${e.message}")
            false
        } finally {
            headConn?.disconnect()
        }
    }
}
