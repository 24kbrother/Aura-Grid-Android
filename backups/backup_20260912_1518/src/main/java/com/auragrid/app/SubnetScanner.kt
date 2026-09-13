package com.auragrid.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredHost(
    val url: String,
    val displayAddress: String,
    val latencyMs: Long
)

/**
 * Lightweight concurrent subnet probe service for Android.
 *
 * Scans the local /24 WiFi subnet using a prioritized host sequence and target ports.
 * Employs a multi-tier recognition engine (/api/v1/auth/status, /health, /)
 * to reliably identify active Aura Grid instances.
 */
class SubnetScanner(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanExecutor = Executors.newFixedThreadPool(25)
    private val isScanning = AtomicBoolean(false)

    // Dedicated fast-timeout OkHttp client for local probing
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // Priority ports: 8125 (standard production port), 5173/5174 (dev), 8500 (backend), 3000, 80
    private val targetPorts = listOf(8125, 5173, 5174, 8500, 3000, 80)

    interface ScanCallback {
        fun onHostDiscovered(host: DiscoveredHost)
        fun onScanProgress(current: Int, total: Int)
        fun onScanCompleted(candidates: List<DiscoveredHost>)
    }

    fun isScanning(): Boolean = isScanning.get()

    fun stopScan() {
        if (isScanning.compareAndSet(true, false)) {
            try {
                scanExecutor.shutdownNow()
            } catch (e: Exception) {
                Log.w("SubnetScanner", "Error shutting down scanner: ${e.message}")
            }
            scanExecutor = Executors.newFixedThreadPool(25)
        }
    }

    fun startScan(callback: ScanCallback) {
        stopScan()
        isScanning.set(true)

        val localIp = getLocalWifiIp()
        if (localIp.isNullOrEmpty()) {
            Log.w("SubnetScanner", "Unable to determine local WiFi IP. Cannot scan subnet.")
            isScanning.set(false)
            callback.onScanCompleted(emptyList())
            return
        }

        val subnetPrefix = deriveSubnet(localIp)
        Log.i("SubnetScanner", "Starting prioritized subnet scan on $subnetPrefix.1-254...")

        val candidates = Collections.newSetFromMap(ConcurrentHashMap<DiscoveredHost, Boolean>())
        val seenUrls = ConcurrentHashMap.newKeySet<String>()

        // Generate prioritized candidate hosts
        val prioritizedHosts = buildPrioritizedHostList(localIp)

        // Generate prioritized probe URL list
        val probeUrls = mutableListOf<String>()
        // Pass 1: Standard & Dev ports (8125, 5173, 5174) on priority hosts
        for (port in listOf(8125, 5173, 5174)) {
            for (host in prioritizedHosts) {
                probeUrls.add("http://$subnetPrefix.$host:$port")
            }
        }
        // Pass 2: Secondary ports (8500, 3000, 80)
        for (port in listOf(8500, 3000, 80)) {
            for (host in prioritizedHosts) {
                probeUrls.add("http://$subnetPrefix.$host:$port")
            }
        }

        val totalTasks = probeUrls.size
        var completedTasks = 0

        val backgroundRunner = Runnable {
            for (url in probeUrls) {
                if (!isScanning.get()) break

                scanExecutor.execute {
                    if (!isScanning.get()) return@execute

                    val host = probeHost(url)
                    if (host != null && seenUrls.add(host.url)) {
                        candidates.add(host)
                        mainHandler.post {
                            callback.onHostDiscovered(host)
                        }
                    }

                    synchronized(this) {
                        completedTasks++
                        val current = completedTasks
                        if (current % 15 == 0 || current == totalTasks) {
                            mainHandler.post {
                                callback.onScanProgress(current, totalTasks)
                            }
                        }
                        if (current >= totalTasks && isScanning.compareAndSet(true, false)) {
                            val sortedResults = candidates.toList().sortedBy { it.latencyMs }
                            mainHandler.post {
                                callback.onScanCompleted(sortedResults)
                            }
                        }
                    }
                }
            }
        }

        Thread(backgroundRunner, "AuraSubnetScanner").start()
    }

    /**
     * Three-tier probe logic matching iOS architecture.
     */
    private fun probeHost(baseUrl: String): DiscoveredHost? {
        val start = System.currentTimeMillis()

        // 1. Primary probe: /api/v1/auth/status (Standard zero-auth status endpoint)
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/v1/auth/status")
                .header("User-Agent", "AuraGridApp/1.1.0 (Android; Scanner)")
                .build()
            probeClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.contains("initialized")) {
                        val latency = System.currentTimeMillis() - start
                        return DiscoveredHost(baseUrl, cleanDisplayAddress(baseUrl), latency)
                    }
                }
            }
        } catch (_: IOException) {}

        // 2. Secondary probe: /health (Lite returns 200, Pro unactivated returns 401)
        try {
            val req = Request.Builder()
                .url("$baseUrl/health")
                .header("User-Agent", "AuraGridApp/1.1.0 (Android; Scanner)")
                .build()
            probeClient.newCall(req).execute().use { response ->
                val code = response.code
                if (code == 200 || code == 401) {
                    val body = response.body?.string() ?: ""
                    if (body.contains("ha_connected") || body.contains("未激活") || body.contains("auragrid", ignoreCase = true) || body.contains("aura grid", ignoreCase = true)) {
                        val latency = System.currentTimeMillis() - start
                        return DiscoveredHost(baseUrl, cleanDisplayAddress(baseUrl), latency)
                    }
                }
            }
        } catch (_: IOException) {}

        // 3. Tertiary probe: root / (SPA index.html with <title>Aura Grid</title>)
        try {
            val req = Request.Builder()
                .url(baseUrl)
                .header("User-Agent", "AuraGridApp/1.1.0 (Android; Scanner)")
                .build()
            probeClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.contains("<title>aura grid</title>", ignoreCase = true) || body.contains("auragrid", ignoreCase = true)) {
                        val latency = System.currentTimeMillis() - start
                        return DiscoveredHost(baseUrl, cleanDisplayAddress(baseUrl), latency)
                    }
                }
            }
        } catch (_: IOException) {}

        return null
    }

    private fun cleanDisplayAddress(url: String): String {
        return url.replace("http://", "").replace("https://", "")
    }

    /**
     * Builds a scan sequence placing high-probability LAN hosts first:
     * 1. Default gateway (.1)
     * 2. Current device neighborhood (localHost ± 5)
     * 3. Common server static IPs (.2~.15, .50, .60, .70, .80, .90, .100, .150, .200, .254)
     * 4. Remaining hosts in 1...254
     */
    private fun buildPrioritizedHostList(localIp: String): List<Int> {
        val prioritySet = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()

        fun addHost(h: Int) {
            if (h in 1..254 && seen.add(h)) {
                prioritySet.add(h)
            }
        }

        // 1. Gateway
        addHost(1)

        // 2. Local device cluster
        val localHost = localIp.split(".").lastOrNull()?.toIntOrNull() ?: 100
        for (delta in -5..5) {
            addHost(localHost + delta)
        }

        // 3. Common static server addresses
        val commonServers = intArrayOf(2, 3, 4, 5, 10, 20, 30, 50, 60, 70, 73, 80, 90, 100, 101, 120, 150, 188, 200, 250, 254)
        for (s in commonServers) {
            addHost(s)
        }

        // 4. Remaining hosts
        for (h in 1..254) {
            addHost(h)
        }

        return prioritySet
    }

    private fun getLocalWifiIp(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                // Look for active wlan0 / eth0 interfaces
                val name = intf.name.lowercase()
                if (!name.contains("wlan") && !name.contains("eth") && !name.contains("en")) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrEmpty() && !ip.startsWith("127.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("SubnetScanner", "Error getting local IP: ${e.message}")
        }
        return null
    }

    private fun deriveSubnet(ip: String): String {
        val parts = ip.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" else "192.168.1"
    }
}
