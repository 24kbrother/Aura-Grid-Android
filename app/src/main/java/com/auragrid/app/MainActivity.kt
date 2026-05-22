package com.auragrid.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import com.auragrid.app.databinding.ActivityMainBinding
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.security.MessageDigest
import androidx.core.content.FileProvider
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * MainActivity: Represents the industrial Kiosk display window.
 * Hosts the fully-accelerated, high-performance WebView, binds the native JS Bridge interface,
 * manages immersive full-screen display overlays, and handles connection auto-recovery.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var roamingManager: NetworkRoamingManager
    private lateinit var orchestrator: NotificationOrchestrator
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var lanUrl = ""
    private var wanUrl = ""
    private var isKioskMode = true
    private var activeUrl = ""

    private var isErrorState = false
    private val recoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recoveryRunnable = Runnable { attemptAutoRecovery() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Initialize storage first to set the language locale before view creation
        sharedPreferences = getSharedPreferences("AuraGridPreferences", Context.MODE_PRIVATE)
        val lang = sharedPreferences.getString("app_language", "zh") ?: "zh"
        setAppLocale(this, lang)

        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load other configuration values
        loadSavedConfig()

        roamingManager = NetworkRoamingManager(this)
        orchestrator = NotificationOrchestrator(this)

        // 2. Configure hardware screen locking based on mode
        updateScreenLocking()

        // 3. Configure full system immersive UI
        applySystemImmersiveMode()

        // 4. Initialize WebView components
        setupWebView()

        // 5. Setup Action Buttons & Gesture detection for Settings Config Panel
        setupGestureInterceptors()
        setupControlListeners()

        // 6. Connect to back-end services
        startAuraServices()

        // Check if configuration is set; if not (or if it's default), force showing the Setup screen
        val isConfigured = sharedPreferences.getBoolean("is_configured", false)
        if (!isConfigured || lanUrl.isEmpty() || lanUrl == "http://10.0.0.90:3001") {
            Log.i("MainActivity", "App not configured or has default dummy URL. Forcing Setup dialog.")
            toggleSettingsOverlay(true)
        } else {
            // 7. Route & Load Optimal URL
            routeAndLoadUrl()
        }

        // 8. Handle any incoming deep-link intents (e.g., notification clicks)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applySystemImmersiveMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Helper to set language configuration dynamically at runtime.
     */
    private fun setAppLocale(context: Context, languageCode: String) {
        val locale = java.util.Locale(languageCode)
        java.util.Locale.setDefault(locale)
        val config = context.resources.configuration
        
        val currentLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0]
        } else {
            @Suppress("DEPRECATION")
            config.locale
        }
        if (currentLocale.language == languageCode) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    /**
     * Reads saved server configuration from secure local storage.
     */
    private fun loadSavedConfig() {
        lanUrl = sharedPreferences.getString("server_lan_url", "") ?: ""
        wanUrl = sharedPreferences.getString("server_wan_url", "") ?: ""
        isKioskMode = sharedPreferences.getBoolean("is_kiosk_mode", true)

        val savedUser = sharedPreferences.getString("auth_user", "") ?: ""
        val savedPass = sharedPreferences.getString("auth_pass", "") ?: ""

        binding.inputLanUrl.setText(lanUrl)
        binding.inputWanUrl.setText(wanUrl)
        binding.inputUsername.setText(savedUser)
        binding.inputPassword.setText(savedPass)

        val currentLang = sharedPreferences.getString("app_language", "zh") ?: "zh"
        if (currentLang == "zh") {
            binding.radioLangZh.isChecked = true
        } else {
            binding.radioLangEn.isChecked = true
        }

        if (isKioskMode) {
            binding.radioKiosk.isChecked = true
        } else {
            binding.radioCompanion.isChecked = true
        }
    }

    /**
     * Commits configuration values to local storage.
     */
    private fun saveConfig(newLan: String, newWan: String, user: String, pass: String, token: String, newKiosk: Boolean, newLang: String) {
        lanUrl = newLan
        wanUrl = newWan
        isKioskMode = newKiosk

        sharedPreferences.edit().apply {
            putString("server_lan_url", lanUrl)
            putString("server_wan_url", wanUrl)
            putString("auth_user", user)
            putString("auth_pass", pass)
            putString("auth_token", token)
            putBoolean("is_kiosk_mode", isKioskMode)
            putString("app_language", newLang)
            putBoolean("is_configured", true)
            apply()
        }

        // Apply new language
        setAppLocale(this, newLang)

        updateScreenLocking()
        
        // Recreate the activity to apply language changes instantly
        recreate()
        
        // Restart the WebSocket background monitoring service with the new address
        startAuraServices()
    }

    /**
     * Keeps screen on persistently if the app runs in Wall Kiosk mode.
     */
    private fun updateScreenLocking() {
        if (isKioskMode) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d("MainActivity", "Kiosk Mode Active: screen constant wake-lock activated.")
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d("MainActivity", "Companion Mode Active: screen timeout delegated to OS.")
        }
    }

    /**
     * Forces system navigation and status bars into absolute hidden, immersive modes.
     * Swiping from the edges shows translucent transient bars that hide again automatically.
     */
    private fun applySystemImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    /**
     * Core configuration for accelerated Chromium engine.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        // Enforce maximum GPU and composition rendering acceleration
        binding.webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // Allow auto-playing live streams and camera feeds
            mediaPlaybackRequiresUserGesture = false
            
            // Allow Zoom if needed on smaller displays
            builtInZoomControls = false
            displayZoomControls = false
        }

        // Inject the secure cross-platform bridge object
        binding.webView.addJavascriptInterface(AuraNativeBridge(this), "AuraNative")

        // Hook system client events
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isErrorState) {
                    // Inject credentials into localStorage if they exist in SharedPreferences
                    val token = sharedPreferences.getString("auth_token", "") ?: ""
                    val username = sharedPreferences.getString("auth_user", "") ?: ""
                    if (token.isNotEmpty()) {
                        Log.d("MainActivity", "Injecting authentication token into WebView localStorage.")
                        val js = """
                            (function() {
                                if (localStorage.getItem('auth_token') !== '$token') {
                                    localStorage.setItem('auth_token', '$token');
                                    localStorage.setItem('auth_user', '$username');
                                    window.location.reload();
                                }
                            })();
                        """.trimIndent()
                        binding.webView.evaluateJavascript(js, null)
                    }

                    binding.loadingOverlay.visibility = View.GONE
                    binding.webView.visibility = View.VISIBLE
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Filter out non-main-frame asset loading errors
                if (request?.isForMainFrame == true) {
                    handlePageLoadFailure()
                }
            }
        }
    }

    /**
     * Intercepts gestures. A double-finger double-tap triggers
     * the system administrator configuration overlay dashboard!
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureInterceptors() {
        var threeFingerTapCount = 0
        var lastThreeFingerTapTime = 0L
        var cornerClickCount = 0
        var lastCornerClickTime = 0L

        binding.webView.setOnTouchListener { _, event ->
            val actionMasked = event.actionMasked
            
            // 1. Detect 3-Finger Tap (Any Down event where pointer count is 3)
            if ((actionMasked == MotionEvent.ACTION_POINTER_DOWN || actionMasked == MotionEvent.ACTION_DOWN) && event.pointerCount == 3) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastThreeFingerTapTime < 1000L) {
                    // Debounce: ensure it is a separate touch event (> 150ms) and not a single messy touch frame
                    if (currentTime - lastThreeFingerTapTime > 150L) {
                        threeFingerTapCount++
                        Log.d("MainActivity", "Three-finger tap registered. Count: $threeFingerTapCount")
                    }
                } else {
                    threeFingerTapCount = 1
                    Log.d("MainActivity", "Three-finger tap registered. Resetted Count to 1")
                }
                lastThreeFingerTapTime = currentTime
                
                if (threeFingerTapCount >= 3) {
                    Log.i("MainActivity", "Three-finger Triple Tap detected! Opening settings.")
                    threeFingerTapCount = 0
                    toggleSettingsOverlay(true)
                    return@setOnTouchListener true // Intercept!
                }
            }
            
            // 2. Fallback: Top-Right Corner 5-Click (100% single-touch compatible)
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                val screenWidth = resources.displayMetrics.widthPixels
                
                // Define top-right corner hot zone: extreme top-right 100x100 pixels
                if (x >= screenWidth - 100 && y <= 100) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastCornerClickTime < 1500L) {
                        cornerClickCount++
                        Log.d("MainActivity", "Corner click registered. Count: $cornerClickCount")
                    } else {
                        cornerClickCount = 1
                        Log.d("MainActivity", "Corner click registered. Resetted Count to 1")
                    }
                    lastCornerClickTime = currentTime
                    
                    if (cornerClickCount >= 5) {
                        Log.i("MainActivity", "Top-Right Corner 5-Click detected! Opening settings.")
                        cornerClickCount = 0
                        toggleSettingsOverlay(true)
                        return@setOnTouchListener true // Intercept!
                    }
                }
            }
            false // Let WebView process standard gestures (scroll, pinch, zoom, etc.)
        }
        
        binding.loadingOverlay.setOnTouchListener { _, event ->
            // In loading overlay, double tap is still helpful in case configuration is wrong on first launch
            val doubleTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    toggleSettingsOverlay(true)
                    return true
                }
            })
            doubleTapDetector.onTouchEvent(event)
            true
        }
    }

    /**
     * Binds click events to modal control elements.
     */
    private fun setupControlListeners() {
        binding.btnCancelSettings.setOnClickListener {
            toggleSettingsOverlay(false)
        }

        binding.btnSaveSettings.setOnClickListener {
            val lanStr = binding.inputLanUrl.text.toString().trim()
            val wanStr = binding.inputWanUrl.text.toString().trim()
            val userStr = binding.inputUsername.text.toString().trim()
            val passStr = binding.inputPassword.text.toString().trim()
            val isKiosk = binding.radioKiosk.isChecked
            val selectedLang = if (binding.radioLangZh.isChecked) "zh" else "en"

            if (lanStr.isEmpty()) {
                binding.inputLanUrl.error = "LAN URL is required"
                return@setOnClickListener
            }

            // If the user chooses to bypass verification or did not enter credentials, save directly
            if (binding.btnSaveSettings.text == "Save Anyway" || userStr.isEmpty()) {
                saveConfig(lanStr, wanStr, userStr, passStr, "", isKiosk, selectedLang)
                toggleSettingsOverlay(false)
                return@setOnClickListener
            }

            // Show verification status loading state
            binding.txtVerificationStatus.visibility = View.VISIBLE
            binding.txtVerificationStatus.setTextColor(Color.parseColor("#00E5FF")) // Cyan color for loading
            binding.txtVerificationStatus.text = "Verifying server connection and credentials..."
            binding.btnSaveSettings.isEnabled = false

            executor.execute {
                // Try logging in via LAN first
                var token = performLoginRequest(lanStr, userStr, passStr)
                var resolvedBaseUrl = lanStr
                
                // If LAN fails, try WAN if it is configured
                if (token == null && wanStr.isNotEmpty() && !wanStr.contains("yourdomain.com")) {
                    token = performLoginRequest(wanStr, userStr, passStr)
                    resolvedBaseUrl = wanStr
                }

                runOnUiThread {
                    binding.btnSaveSettings.isEnabled = true
                    if (token != null) {
                        binding.txtVerificationStatus.setTextColor(Color.parseColor("#00FF66")) // Green for success
                        binding.txtVerificationStatus.text = "Verification successful!"
                        
                        // Save config with verified token
                        saveConfig(lanStr, wanStr, userStr, passStr, token, isKiosk, selectedLang)
                        
                        // Close settings after a small delay
                        binding.txtVerificationStatus.postDelayed({
                            toggleSettingsOverlay(false)
                            binding.btnCancelSettings.visibility = View.VISIBLE // Restore cancel visibility
                        }, 500)
                    } else {
                        binding.txtVerificationStatus.setTextColor(Color.parseColor("#FF3333")) // Red for error
                        binding.txtVerificationStatus.text = "Failed to connect or authenticate. Check your inputs or press 'Save Anyway' to force save."
                        binding.btnSaveSettings.text = "Save Anyway"
                    }
                }
            }
        }
    }

    private fun toggleSettingsOverlay(show: Boolean) {
        if (show) {
            binding.btnSaveSettings.text = getString(R.string.save_settings)
            binding.txtVerificationStatus.visibility = View.GONE
            binding.btnCancelSettings.visibility = if (sharedPreferences.getBoolean("is_configured", false)) View.VISIBLE else View.GONE
            
            binding.settingsOverlay.visibility = View.VISIBLE
            binding.settingsOverlay.alpha = 1f
        } else {
            binding.settingsOverlay.visibility = View.GONE
            applySystemImmersiveMode()
        }
    }

    /**
     * Helper to perform programmatic login against the backend NestJS endpoint
     */
    private fun performLoginRequest(baseUrl: String, user: String, pass: String): String? {
        var connection: java.net.HttpURLConnection? = null
        return try {
            val cleanUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
            val url = java.net.URL("$cleanUrl/api/v1/auth/login")
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val jsonParam = org.json.JSONObject().apply {
                put("username", user)
                put("password", pass)
            }

            val os = connection.outputStream
            val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(os, "UTF-8"))
            writer.write(jsonParam.toString())
            writer.flush()
            writer.close()
            os.close()

            connection.connect()
            if (connection.responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = org.json.JSONObject(response)
                jsonObj.optString("access_token", null)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Login verification failed for $baseUrl: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Decides whether to route traffic to the local network or external internet.
     */
    private fun routeAndLoadUrl() {
        isErrorState = false
        binding.webView.visibility = View.INVISIBLE
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.loadingOverlay.alpha = 1f
        binding.loadingText.text = getString(R.string.connecting)
        binding.subLoadingText.text = "Checking network health and server availability..."

        roamingManager.resolveOptimalRoute(lanUrl, wanUrl, object : NetworkRoamingManager.RoamingCallback {
            override fun onRouteResolved(resolvedUrl: String, isLocal: Boolean) {
                activeUrl = resolvedUrl
                binding.subLoadingText.text = if (isLocal) "LAN Server online. Launching local profile..." else "WAN Server online. Launching cloud profile..."
                Log.i("MainActivity", "Optimal route resolved. Loading URL: $activeUrl")
                binding.webView.loadUrl(activeUrl)
            }
        })
    }

    /**
     * Intercepts page load errors and replaces standard Chromium error screens
     * with our gorgeous industrial connection-lost custom view layout.
     */
    private fun handlePageLoadFailure() {
        isErrorState = true
        recoveryHandler.removeCallbacks(recoveryRunnable)
        
        // Show our offline layout screen
        setContentView(R.layout.activity_offline)
        
        // Bind actions to offline screen elements
        findViewById<View>(R.id.btnConfigOffline)?.setOnClickListener {
            // Re-render main layout to expose configuration modal
            setContentView(binding.root)
            toggleSettingsOverlay(true)
        }

        findViewById<View>(R.id.btnRetryOffline)?.setOnClickListener {
            setContentView(binding.root)
            routeAndLoadUrl()
        }

        // Schedule background recovery reconnection checks every 3 seconds
        recoveryHandler.postDelayed(recoveryRunnable, 3000L)
    }

    private fun attemptAutoRecovery() {
        Log.d("MainActivity", "Attempting automatic network path recovery...")
        roamingManager.resolveOptimalRoute(lanUrl, wanUrl, object : NetworkRoamingManager.RoamingCallback {
            override fun onRouteResolved(resolvedUrl: String, isLocal: Boolean) {
                // If ping succeeds, recover page
                if (resolvedUrl.isNotEmpty()) {
                    recoveryHandler.removeCallbacks(recoveryRunnable)
                    setContentView(binding.root)
                    routeAndLoadUrl()
                } else {
                    // Retry again in 3 seconds
                    recoveryHandler.postDelayed(recoveryRunnable, 3000L)
                }
            }
        })
    }

    /**
     * Starts background Foreground Services for active Socket.IO connection monitoring.
     */
    private fun startAuraServices() {
        try {
            val serviceIntent = Intent(this, AuraSocketService::class.java).apply {
                putExtra("SERVER_URL", lanUrl) // Feed local server URL for local WebSocket routing
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i("MainActivity", "Foreground AuraSocketService registered successfully.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start foreground service: ${e.message}")
        }
    }

    /**
     * Intercepts intent payloads (like clicking system notifications) and routes
     * parameters directly into the Vue 3 WebView components.
     */
    private fun handleIntent(intent: Intent?) {
        val openCameraEntity = intent?.getStringExtra("OPEN_CAMERA_ENTITY")
        val alertId = intent?.getStringExtra("ALERT_ID")

        if (openCameraEntity != null) {
            Log.i("MainActivity", "Deep link triggered: targeting camera entity -> $openCameraEntity")
            // Evaluate javascript when page is fully loaded to trigger frontend popup
            binding.webView.post {
                binding.webView.evaluateJavascript(
                    "if (window.__AURA_ALERT_ROUTER__) { window.__AURA_ALERT_ROUTER__('$alertId', '$openCameraEntity'); }",
                    null
                )
            }
        }
    }

    /**
     * Prevents system back button click from crashing or closing Kiosk shell.
     */
    override fun onBackPressed() {
        if (binding.settingsOverlay.visibility == View.VISIBLE) {
            toggleSettingsOverlay(false)
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            // Intercept in Kiosk mode to prevent exiting app on Wall Tablet
            if (!isKioskMode) {
                super.onBackPressed()
            }
        }
    }

    private fun installApk(file: File) {
        val context = this@MainActivity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                runOnUiThread {
                    Toast.makeText(context, "Please allow unknown app installation and retry", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Installation failed: ${e.message}")
            runOnUiThread {
                Toast.makeText(context, "Installation failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startUpgradeFlow(downloadUrl: String) {
        runOnUiThread {
            binding.downloadOverlay.visibility = View.VISIBLE
            binding.downloadProgressBar.isIndeterminate = true
            binding.downloadProgressBar.progress = 0
            binding.downloadPercent.text = "0%"
            binding.downloadSpeed.text = "Connecting..."
            binding.downloadStatus.text = "Initializing download..."
        }

        executor.submit {
            var connection: java.net.HttpURLConnection? = null
            var input: java.io.InputStream? = null
            var output: java.io.FileOutputStream? = null
            val apkFile = File(cacheDir, "aura_grid_update.apk")

            try {
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                val url = URL(downloadUrl)
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    throw java.io.IOException("Server returned HTTP ${connection.responseCode}")
                }

                val fileLength = connection.contentLength
                input = connection.inputStream
                output = FileOutputStream(apkFile)

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                val startTime = System.currentTimeMillis()

                runOnUiThread {
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
                        
                        runOnUiThread {
                            binding.downloadProgressBar.progress = progress
                            binding.downloadPercent.text = "$progress%"
                            binding.downloadSpeed.text = speedText
                        }
                    } else {
                        val downloadedMb = total / 1024.0 / 1024.0
                        runOnUiThread {
                            binding.downloadPercent.text = String.format("%.1f MB", downloadedMb)
                            binding.downloadSpeed.text = "Downloading..."
                        }
                    }
                }

                output.flush()
                
                runOnUiThread {
                    binding.downloadOverlay.visibility = View.GONE
                    installApk(apkFile)
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Download failed: ${e.message}")
                runOnUiThread {
                    binding.downloadOverlay.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Download failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                try {
                    output?.close()
                    input?.close()
                } catch (ignored: Exception) {}
                connection?.disconnect()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recoveryHandler.removeCallbacks(recoveryRunnable)
    }

    /**
     * AuraNativeBridge: Safe, sandboxed JavaScript Interface
     * mapped to "window.AuraNative" inside the WebView.
     */
    inner class AuraNativeBridge(private val context: Context) {

        /**
         * Returns SHA-256 hash of Settings.Secure.ANDROID_ID to serve as a hardware fingerprint (HWID)
         * for the Pro version security license center. Fits beautifully with the RSA handshake.
         */
        @JavascriptInterface
        fun getHardwareFingerprint(): String {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "DEFAULT-AURA-KEY"
            return sha256(androidId)
        }

        /**
         * Returns high-fidelity device telemetry back to the Vue 3 state engine.
         */
        @JavascriptInterface
        fun getDeviceInfo(): String {
            return JSONObject().apply {
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("osVersion", Build.VERSION.SDK_INT)
                put("appVersion", getAppVersion())
                put("isKioskMode", isKioskMode)
                put("platform", "android")
            }.toString()
        }

        /**
         * Returns current companion app version.
         */
        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.versionName ?: "2.1.0-OTA"
            } catch (e: Exception) {
                "2.1.0-OTA"
            }
        }

        /**
         * Triggers one-click background APK download and install
         */
        @JavascriptInterface
        fun startUpgrade(downloadUrl: String) {
            Log.i("AuraJSBridge", "Upgrade requested: $downloadUrl")
            startUpgradeFlow(downloadUrl)
        }

        /**
         * Returns current Google Cloud Messaging (FCM) Token for backend push registration.
         */
        @JavascriptInterface
        fun getPushToken(): String {
            var token = ""
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        token = task.result
                    }
                }
            } catch (e: Exception) {
                Log.e("AuraJSBridge", "Failed to retrieve FCM token: ${e.message}")
            }
            return token
        }

        /**
         * Returns active Kiosk work state.
         */
        @JavascriptInterface
        fun getAppMode(): String {
            return if (isKioskMode) "kiosk" else "companion"
        }

        /**
         * Native player that executes audible tones corresponding to alert levels.
         */
        @JavascriptInterface
        fun playAlertSound(severity: String) {
            try {
                val soundUri = if (severity.equals("CRITICAL", ignoreCase = true)) {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                } else {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }
                val ringtone = RingtoneManager.getRingtone(context, soundUri)
                ringtone.play()
            } catch (e: Exception) {
                Log.e("AuraJSBridge", "Sound playback failed: ${e.message}")
            }
        }

        /**
         * Triggers system orchestrator to dim or clear the corresponding notification bar element.
         */
        @JavascriptInterface
        fun acknowledgeAlert(alertId: String) {
            orchestrator.cancelNotification(alertId)
        }

        private fun sha256(base: String): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(base.toByteArray(Charsets.UTF_8))
                hash.joinToString("") { "%02x".format(it) }
            } catch (ex: Exception) {
                base
            }
        }
    }
}
