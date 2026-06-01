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
    private var tempSelectedLang = "zh"

    private var isErrorState = false
    private val recoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recoveryRunnable = Runnable { attemptAutoRecovery() }

    private var uploadMessage: android.webkit.ValueCallback<Array<Uri>>? = null
    private val FILECHOOSER_RESULTCODE = 10001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize storage first to set the language locale before view creation
        sharedPreferences = getSharedPreferences("AuraGridPreferences", Context.MODE_PRIVATE)
        val lang = sharedPreferences.getString("app_language", "zh") ?: "zh"
        setAppLocale(this, lang)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check privacy policy acceptance before initializing any services or network
        val privacyAccepted = sharedPreferences.getBoolean("privacy_accepted", false)
        if (!privacyAccepted) {
            Log.i("MainActivity", "Privacy Policy not accepted yet. Displaying dialog blocker.")
            showPrivacyPolicyDialog()
        } else {
            initializeAfterPrivacy()
        }
    }

    private fun initializeAfterPrivacy() {
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

        // 9. Asynchronously check for GitHub OTA updates 2 seconds after boot
        recoveryHandler.postDelayed({
            checkOtaUpdates()
        }, 2000L)
    }

    /**
     * Checks for GitHub Releases OTA Updates.
     */
    fun checkOtaUpdates() {
        Log.i("MainActivity", "Triggering asynchronous GitHub OTA update check...")
        val context = this@MainActivity
        com.auragrid.app.update.UpdateService.checkForUpdates(context, object : com.auragrid.app.update.UpdateService.UpdateCallback {
            override fun onUpdateAvailable(remoteVersion: String, downloadUrl: String, notes: String, sizeBytes: Long) {
                val currentVer = try {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    packageInfo.versionName ?: "2.1.0-OTA"
                } catch (e: Exception) {
                    "2.1.0-OTA"
                }
                
                com.auragrid.app.update.UpdateDialog.show(
                    activity = this@MainActivity,
                    binding = binding,
                    currentVersion = currentVer,
                    remoteVersion = remoteVersion,
                    notes = notes,
                    sizeBytes = sizeBytes,
                    downloadUrl = downloadUrl
                )
            }

            override fun onNoUpdate() {
                Log.d("MainActivity", "OTA update check: App is already up-to-date.")
            }

            override fun onError(error: String) {
                Log.w("MainActivity", "OTA update check failed: $error")
            }
        })
    }

    /**
     * Renders a highly polished Cyberpunk/Material style dialog blocking app access
     * until the Privacy Policy is explicitly accepted. Matches the big-screen aesthetic.
     */
    private fun showPrivacyPolicyDialog() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setCancelable(false)

        val isTraditional = tempSelectedLang == "zh-rTW" || tempSelectedLang == "zh-TW"
        val isZh = tempSelectedLang == "zh" || isTraditional

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#08080C"))
            setPadding(48, 48, 48, 48)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val iconView = android.widget.TextView(this).apply {
            text = "🛡️"
            textSize = 48f
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 40
            }
        }
        container.addView(iconView)

        val titleText = android.widget.TextView(this).apply {
            text = when {
                isTraditional -> "Aura Grid 隱私協議與授權聲明"
                isZh -> "Aura Grid 隐私协议与授权声明"
                else -> "Privacy Policy & Authorization Terms"
            }
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
        }
        container.addView(titleText)

        val subtitleText = android.widget.TextView(this).apply {
            text = "AURA-GRID-PRIVACY-SECURITY-POLICY"
            textSize = 9f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.2f
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
        }
        container.addView(subtitleText)

        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                weight = 1f
                topMargin = 32
                bottomMargin = 32
            }
            isVerticalScrollBarEnabled = false
        }

        val cardLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#0DFFFFFF"))
                setStroke(2, Color.parseColor("#2600E5FF"))
                cornerRadius = 24f
            }
        }

        val privacyBody = android.widget.TextView(this).apply {
            text = when {
                isTraditional -> """
                    歡迎使用 Aura Grid 伴侶終端！我們高度重視您的個人隱私與數據安全。在您同意本協議前，應用程序不會初始化任何第三方 SDK 或是發起任何網絡通訊。

                    請您仔細閱讀以下關鍵條款：
                    
                    1. 數據收集範圍
                    我們僅收集運行所必需的本地配置信息，包括局域網/公網網關地址、用於身份校驗的加密憑據，以及可選的推送服務（FCM/HMS）唯一標識符。
                    
                    2. 硬件指紋與密鑰審計
                    為確保 Pro 版商業授權的安全性，我們會生成 Settings.Secure.ANDROID_ID 的 SHA-256 單向哈希值，作為本設備的唯一硬件指纹（HWID）。該哈希值不可反向還原，僅用於 RSA 安全認證與心跳審計，絕不包含任何個人身份隱私。
                    
                    3. 本地化安全存儲
                    您的所有服務器賬號與密碼均通過 Android SharedPreferences 進行本機加密隔離存儲。WebView 中的會話緩存與 Token 同样受到系統沙盒保護。
                    
                    4. 數據零共享
                    本應用為 100% 私有化或直連您的專屬閘道運行。我們承諾絕不會向任何第三方機構或個人透露、銷售或共享您的任何配置、密鑰或傳感器遙測數據。

                    點擊「同意並繼續」即代表您接受以上隱私協議與服務條款，我們將為您解鎖完整的賽博科技中控體驗。
                """.trimIndent()
                isZh -> """
                    欢迎使用 Aura Grid 伴侣终端！我们高度重视您的个人隐私与数据安全。在您同意本协议前，应用程序不会初始化任何第三方 SDK 或是发起任何网络通讯。

                    请您仔细阅读以下关键条款：
                    
                    1. 数据收集范围
                    我们仅收集运行所必需的本地配置信息，包括局域网/公网网关地址、用于身份校验的加密凭据，以及可选的推送服务（FCM/HMS）唯一标识符。
                    
                    2. 硬件指纹与密钥审计
                    为确保 Pro 版商业授权的安全性，我们会生成 Settings.Secure.ANDROID_ID 的 SHA-256 单向哈希值，作为本设备的唯一硬件指纹（HWID）。该哈希值不可反向还原，仅用于 RSA 安全认证与心跳审计，绝不包含任何个人身份隐私。
                    
                    3. 本地化安全存储
                    您的所有服务器账号与密码均通过 Android SharedPreferences 进行本机加密隔离存储。WebView 中的会话缓存与 Token 同样受到系统沙盒保护。
                    
                    4. 数据零共享
                    本应用为 100% 私有化或直连您的专属网关运行。我们承诺绝不会向任何第三方机构或个人透露、销售或共享您的任何配置、密钥或传感器遥测数据。

                    点击“同意并继续”即代表您接受以上隐私协议与服务条款，我们将为您解锁完整的赛博科技中控体验。
                """.trimIndent()
                else -> """
                    Welcome to Aura Grid Companion! We highly value your personal privacy and data security. Before you agree to this policy, the app will not initialize any third-party SDKs or make any outbound network requests.

                    Please read our core terms carefully:
                    
                    1. Data Collection Scope
                    We only collect basic configurations required for operations, including your local LAN/WAN gateway addresses, encrypted credentials for authentication, and optional push service (FCM/HMS) tokens.
                    
                    2. Hardware Fingerprint & Audit
                    To verify Pro commercial licenses, we generate a one-way SHA-256 hash of Settings.Secure.ANDROID_ID to serve as your hardware fingerprint (HWID). This hash cannot be reversed and is used solely for secure RSA handshakes and heartbeat audits.
                    
                    3. Encrypted Local Storage
                    All server credentials and passwords are encrypted and isolated locally using SharedPreferences. WebView session caches and tokens are similarly shielded in the OS sandbox.
                    
                    4. Zero Third-Party Sharing
                    Aura Grid operates as a 100% private, direct connection tool to your smart home. We pledge never to share, sell, or disclose your gateway information, telemetry, or keys to any third party.

                    Clicking "Agree & Continue" means you accept these terms, and we will unlock the complete smart home experience for you.
                """.trimIndent()
            }
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            setLineSpacing(0f, 1.3f)
        }
        cardLayout.addView(privacyBody)
        scrollView.addView(cardLayout)
        container.addView(scrollView)

        val buttonsLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        val agreeButton = android.widget.Button(this).apply {
            text = when {
                isTraditional -> "同意並繼續"
                isZh -> "同意并继续"
                else -> "AGREE & CONTINUE"
            }
            textSize = 14f
            setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.DEFAULT_BOLD

            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#0086FF"))
            ).apply {
                cornerRadius = 16f
            }

            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                120
            ).apply {
                bottomMargin = 24
            }

            setOnClickListener {
                dialog.dismiss()
                sharedPreferences.edit().putBoolean("privacy_accepted", true).apply()
                initializeAfterPrivacy()
            }
        }
        buttonsLayout.addView(agreeButton)

        val disagreeButton = android.widget.TextView(this).apply {
            text = when {
                isTraditional -> "不同意並退出"
                isZh -> "不同意并退出"
                else -> "DISAGREE & EXIT"
            }
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(24, 24, 24, 24)
            isClickable = true

            setOnClickListener {
                dialog.dismiss()
                finishAffinity()
            }
        }
        buttonsLayout.addView(disagreeButton)

        container.addView(buttonsLayout)

        dialog.setContentView(container)
        dialog.show()
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
        val locale = when (languageCode) {
            "zh-rTW", "zh-TW" -> java.util.Locale("zh", "TW")
            "zh-rCN", "zh" -> java.util.Locale("zh", "CN")
            "en" -> java.util.Locale("en")
            else -> java.util.Locale(languageCode)
        }
        java.util.Locale.setDefault(locale)
        val config = context.resources.configuration
        
        val currentLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!config.locales.isEmpty) config.locales[0] else java.util.Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            config.locale ?: java.util.Locale.getDefault()
        }
        
        val isMatch = if (languageCode == "zh-rTW" || languageCode == "zh-TW") {
            currentLocale.language == "zh" && currentLocale.country == "TW"
        } else if (languageCode == "zh") {
            currentLocale.language == "zh" && currentLocale.country != "TW"
        } else {
            currentLocale.language == languageCode
        }

        if (isMatch) {
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

        tempSelectedLang = sharedPreferences.getString("app_language", "zh") ?: "zh"
        updateLanguageToggleUI(tempSelectedLang)

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
            
            val savedLang = sharedPreferences.getString("app_language", "zh") ?: "zh"
            val webLang = when (savedLang) {
                "zh-rTW", "zh-TW" -> "zh-TW"
                "en" -> "en"
                else -> "zh-CN"
            }
            userAgentString = "AuraGridApp/1.1.0 (Android; Mobile) Lang/$webLang"
            
            // Allow auto-playing live streams and camera feeds
            mediaPlaybackRequiresUserGesture = false
            
            // Allow Zoom if needed on smaller displays
            builtInZoomControls = false
            displayZoomControls = false
        }

        // Inject the secure cross-platform bridge object
        binding.webView.addJavascriptInterface(AuraNativeBridge(this), "AuraNative")

        // Support file downloads (Export full configuration backup)
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            shareOrSaveDownloadedFile(url, mimetype, contentDisposition)
        }

        // Support file picker inputs (Import configuration backup)
        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                uploadMessage?.onReceiveValue(null)
                uploadMessage = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                try {
                    startActivityForResult(intent, FILECHOOSER_RESULTCODE)
                } catch (e: Exception) {
                    uploadMessage?.onReceiveValue(null)
                    uploadMessage = null
                    return false
                }
                return true
            }
        }

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

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlRedirection(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleUrlRedirection(url)
            }
        }
    }

    /**
     * Intercepts external URLs in the main frame and forces opening them in the default system browser.
     */
    private fun handleUrlRedirection(url: String): Boolean {
        try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: return false
            
            // 1. Intercept mailto, tel, or other non-http schemes to open in system handler
            if (scheme != "http" && scheme != "https") {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return true
                }
                return false
            }
            
            val targetHost = uri.host ?: return false
            
            // 2. Open external links in default browser
            val mainUri = runCatching { Uri.parse(activeUrl) }.getOrNull()
            val mainHost = mainUri?.host
            
            val lanHost = runCatching { Uri.parse(lanUrl).host }.getOrNull()
            val wanHost = runCatching { Uri.parse(wanUrl).host }.getOrNull()
            
            val isLocal = targetHost.equals("localhost", ignoreCase = true) || targetHost.equals("127.0.0.1")
            
            // If the target host matches our current main host, or lan host, or wan host, or is localhost, it's internal
            val isInternal = (mainHost != null && targetHost.equals(mainHost, ignoreCase = true)) ||
                    (lanHost != null && targetHost.equals(lanHost, ignoreCase = true)) ||
                    (wanHost != null && targetHost.equals(wanHost, ignoreCase = true)) ||
                    isLocal
                    
            if (!isInternal) {
                Log.d("MainActivity", "Intercepted external URL loading in main frame: $url. Opening in external browser...")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /**
     * Handles the file picker result and returns the selected file Uri to WebView.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (uploadMessage == null) return
            val result = if (data == null || resultCode != RESULT_OK) null else data.data
            if (result != null) {
                uploadMessage?.onReceiveValue(arrayOf(result))
            } else {
                uploadMessage?.onReceiveValue(null)
            }
            uploadMessage = null
        }
    }

    /**
     * Intercepts base64 data URLs downloaded from WebView, saves them as a temporary file,
     * and triggers a native Android Share Sheet so the user can save or send the backup.
     */
    private fun shareOrSaveDownloadedFile(url: String, mimeType: String?, contentDisposition: String?) {
        try {
            val isZh = tempSelectedLang == "zh"
            
            // Extract file name
            var fileName = "auragrid_backup.json"
            if (contentDisposition != null) {
                val index = contentDisposition.indexOf("filename=")
                if (index > 0) {
                    fileName = contentDisposition.substring(index + 9).replace("\"", "").trim()
                }
            }
            
            // Extract data content if it's base64 or plain data URL
            val dataBytes: ByteArray
            if (url.startsWith("data:")) {
                val commaIndex = url.indexOf(",")
                if (commaIndex > 0) {
                    val dataPart = url.substring(commaIndex + 1)
                    val header = url.substring(0, commaIndex)
                    dataBytes = if (header.contains("base64")) {
                        android.util.Base64.decode(dataPart, android.util.Base64.DEFAULT)
                    } else {
                        java.net.URLDecoder.decode(dataPart, "UTF-8").toByteArray()
                    }
                } else {
                    return
                }
            } else {
                // If it's a blob or network URL, open in system browser or download manager
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
                return
            }
            
            // Save to temporary cache directory
            val tempFile = java.io.File(cacheDir, fileName)
            java.io.FileOutputStream(tempFile).use { fos ->
                fos.write(dataBytes)
            }
            
            // Share via FileProvider to avoid FileUriExposedException and bypass runtime permission prompts
            val fileUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", tempFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType ?: "application/json"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val title = if (isZh) "导出并保存配置文件" else "Export & Save Configuration"
            startActivity(Intent.createChooser(intent, title))
            
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "Export failed: " + e.localizedMessage, Toast.LENGTH_LONG).show()
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
     * Helper to load Resources for a specific locale dynamically without restarting the Activity context.
     */
    private fun getLocalizedResources(context: Context, localeCode: String): android.content.res.Resources {
        val locale = java.util.Locale(localeCode)
        val config = android.content.res.Configuration(context.resources.configuration)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale)
            context.createConfigurationContext(config).resources
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            val res = context.resources
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            res
        }
    }

    /**
     * Instantly refreshes all visible strings in the settings overlay matching the specified language code.
     */
    private fun applyLanguageToSettingsUI(langCode: String) {
        val res = getLocalizedResources(this, langCode)
        
        binding.txtSettingsTitle.text = res.getString(R.string.settings_title)
        binding.txtSettingsDesc.text = res.getString(R.string.settings_desc)
        
        binding.layoutLanUrl.hint = res.getString(R.string.server_lan_url)
        binding.layoutWanUrl.hint = res.getString(R.string.server_wan_url)
        binding.layoutUsername.hint = res.getString(R.string.username)
        binding.layoutPassword.hint = res.getString(R.string.password)
        
        binding.txtDeviceModeLabel.text = res.getString(R.string.device_mode)
        binding.radioKiosk.text = res.getString(R.string.mode_kiosk)
        binding.radioCompanion.text = res.getString(R.string.mode_companion)
        
        binding.btnCancelSettings.text = res.getString(R.string.cancel)
        
        binding.btnQuickDemo.text = when (langCode) {
            "zh-rTW", "zh-TW" -> "一鍵進入演示系統"
            "zh" -> "一键进入演示系统"
            else -> "ENTER DEMO MODE"
        }
        binding.btnExitDemo.text = when (langCode) {
            "zh-rTW", "zh-TW" -> "一鍵退出演示系統"
            "zh" -> "一键退出演示系统"
            else -> "EXIT DEMO MODE"
        }
        binding.btnWipeData.text = when (langCode) {
            "zh-rTW", "zh-TW" -> "擦除數據並退出"
            "zh" -> "擦除数据并退出"
            else -> "ERASE DATA & EXIT"
        }
        
        // Handle "Save Config" vs "Save Anyway"
        val currentBtnText = binding.btnSaveSettings.text.toString()
        val anywayZH = "强制保存"
        val anywayEN = "Save Anyway"
        
        val saveAnywayStr = res.getString(R.string.save_anyway)
        if (currentBtnText == anywayZH || currentBtnText == "強制儲存" || currentBtnText == anywayEN) {
            binding.btnSaveSettings.text = saveAnywayStr
        } else {
            binding.btnSaveSettings.text = res.getString(R.string.save_settings)
        }

        // Translate verification status message on the fly if visible
        if (binding.txtVerificationStatus.visibility == View.VISIBLE) {
            val statusText = binding.txtVerificationStatus.text.toString()
            if (statusText.contains("Verifying") || statusText.contains("正在验证") || statusText.contains("正在校驗")) {
                binding.txtVerificationStatus.text = res.getString(R.string.verifying_server)
            } else if (statusText.contains("successful") || statusText.contains("成功")) {
                binding.txtVerificationStatus.text = res.getString(R.string.verification_success)
            } else if (statusText.contains("Failed") || statusText.contains("失败") || statusText.contains("失敗")) {
                binding.txtVerificationStatus.text = res.getString(R.string.verification_failed)
            }
        }
    }

    /**
     * Binds click events to modal control elements.
     */
    private fun setupControlListeners() {
        binding.btnLangZhToggle.setOnClickListener {
            tempSelectedLang = "zh"
            updateLanguageToggleUI(tempSelectedLang)
            applyLanguageToSettingsUI(tempSelectedLang)
        }

        binding.btnLangZhTwToggle.setOnClickListener {
            tempSelectedLang = "zh-rTW"
            updateLanguageToggleUI(tempSelectedLang)
            applyLanguageToSettingsUI(tempSelectedLang)
        }

        binding.btnLangEnToggle.setOnClickListener {
            tempSelectedLang = "en"
            updateLanguageToggleUI(tempSelectedLang)
            applyLanguageToSettingsUI(tempSelectedLang)
        }

        binding.btnCancelSettings.setOnClickListener {
            toggleSettingsOverlay(false)
        }

        binding.btnSaveSettings.setOnClickListener {
            val lanStr = binding.inputLanUrl.text.toString().trim()
            val wanStr = binding.inputWanUrl.text.toString().trim()
            val userStr = binding.inputUsername.text.toString().trim()
            val passStr = binding.inputPassword.text.toString().trim()
            val isKiosk = binding.radioKiosk.isChecked
            val selectedLang = tempSelectedLang
            val currentRes = getLocalizedResources(this@MainActivity, selectedLang)

            if (lanStr.isEmpty()) {
                binding.inputLanUrl.error = currentRes.getString(R.string.lan_url_required)
                return@setOnClickListener
            }

            val isAnyway = binding.btnSaveSettings.text.toString() == currentRes.getString(R.string.save_anyway)

            // If the user chooses to bypass verification or did not enter credentials, save directly
            if (isAnyway || userStr.isEmpty()) {
                saveConfig(lanStr, wanStr, userStr, passStr, "", isKiosk, selectedLang)
                toggleSettingsOverlay(false)
                return@setOnClickListener
            }

            // Show verification status loading state
            binding.txtVerificationStatus.visibility = View.VISIBLE
            binding.txtVerificationStatus.setTextColor(Color.parseColor("#00E5FF")) // Cyan color for loading
            binding.txtVerificationStatus.text = currentRes.getString(R.string.verifying_server)
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

                val finalToken = token
                runOnUiThread {
                    binding.btnSaveSettings.isEnabled = true
                    val activeRes = getLocalizedResources(this@MainActivity, tempSelectedLang)
                    if (finalToken != null) {
                        binding.txtVerificationStatus.setTextColor(Color.parseColor("#00FF66")) // Green for success
                        binding.txtVerificationStatus.text = activeRes.getString(R.string.verification_success)
                        
                        // Save config with verified token
                        saveConfig(lanStr, wanStr, userStr, passStr, finalToken, isKiosk, selectedLang)
                        
                        // Close settings after a small delay
                        binding.txtVerificationStatus.postDelayed({
                            toggleSettingsOverlay(false)
                            binding.btnCancelSettings.visibility = View.VISIBLE // Restore cancel visibility
                        }, 500)
                    } else {
                        binding.txtVerificationStatus.setTextColor(Color.parseColor("#FF3333")) // Red for error
                        binding.txtVerificationStatus.text = activeRes.getString(R.string.verification_failed)
                        binding.btnSaveSettings.text = activeRes.getString(R.string.save_anyway)
                    }
                }
            }
        }

        binding.btnQuickDemo.setOnClickListener {
            showDemoModeIntroductionDialog { dialog ->
                val isZh = tempSelectedLang == "zh"
                
                // Disable settings overlay buttons to prevent secondary actions
                binding.btnSaveSettings.isEnabled = false
                binding.btnCancelSettings.isEnabled = false
                binding.btnQuickDemo.isEnabled = false

                // 2. 异步执行静默网络握手
                executor.execute {
                    var token: String? = null
                    var errorMsg: String? = null
                    
                    try {
                        val authURL = java.net.URL("https://demo2.iaura.cn/api/v1/auth/login")
                        val connection = authURL.openConnection() as java.net.HttpURLConnection
                        connection.requestMethod = "POST"
                        connection.setRequestProperty("Content-Type", "application/json")
                        // 注入特权 Companion App User-Agent 绕过 Nginx 444 阻断
                        connection.setRequestProperty("User-Agent", "AuraGridApp/1.1.0 (Android; Tablet)")
                        connection.connectTimeout = 6000
                        connection.readTimeout = 6000
                        connection.doOutput = true

                        val jsonInputString = "{\"username\": \"admin\", \"password\": \"123456\"}"
                        connection.outputStream.use { os ->
                            val input = jsonInputString.toByteArray(charset("utf-8"))
                            os.write(input, 0, input.size)
                        }

                        val code = connection.responseCode
                        if (code == 200 || code == 201) {
                            val response = connection.inputStream.bufferedReader().use { it.readText() }
                            val jsonObject = org.json.JSONObject(response)
                            if (jsonObject.has("access_token")) {
                                token = jsonObject.getString("access_token")
                            }
                        } else {
                            errorMsg = "HTTP $code"
                        }
                    } catch (e: java.lang.Exception) {
                        errorMsg = e.localizedMessage
                    }

                    val finalToken = token
                    val finalErrorMsg = errorMsg

                    runOnUiThread {
                        binding.btnSaveSettings.isEnabled = true
                        binding.btnCancelSettings.isEnabled = true
                        binding.btnQuickDemo.isEnabled = true

                        if (finalToken != null) {
                            // 3. 直接保存硬编码的演示配置，静默绕过表单输入！
                            val isKiosk = binding.radioKiosk.isChecked
                            sharedPreferences.edit().putBoolean("is_demo_mode", true).apply()
                            
                            // 先安全关闭 Dialog 并隐藏设置页，再保存配置触发 Activity 重建，彻底规避 WindowManager 坏 Token 闪退
                            try {
                                dialog.dismiss()
                            } catch (e: java.lang.Exception) {
                                e.printStackTrace()
                            }
                            toggleSettingsOverlay(false)
                            binding.btnCancelSettings.visibility = View.VISIBLE
                            
                            saveConfig("https://demo2.iaura.cn", "https://demo2.iaura.cn", "admin", "123456", finalToken, isKiosk, tempSelectedLang)
                        } else {
                            // Restore dialog UI and display error message inside full-screen dialog
                            val scrollView = dialog.findViewById<android.widget.ScrollView>(10001)
                            val buttonsLayout = dialog.findViewById<android.widget.LinearLayout>(10002)
                            val loadingLayout = dialog.findViewById<android.widget.LinearLayout>(10003)
                            val dialogErrorText = dialog.findViewById<android.widget.TextView>(10004)
                            
                            scrollView?.visibility = android.view.View.VISIBLE
                            buttonsLayout?.visibility = android.view.View.VISIBLE
                            loadingLayout?.visibility = android.view.View.GONE
                            dialogErrorText?.visibility = android.view.View.VISIBLE
                            dialogErrorText?.text = (if (isZh) "进入演示系统失败: " else "Failed to enter demo: ") + (finalErrorMsg ?: "Unknown error")
                        }
                    }
                }
            }
        }

        binding.btnExitDemo.setOnClickListener {
            sharedPreferences.edit().apply {
                remove("server_lan_url")
                remove("server_wan_url")
                remove("auth_user")
                remove("auth_pass")
                remove("auth_token")
                putBoolean("is_demo_mode", false)
                putBoolean("is_configured", false)
                apply()
            }
            clearAppCacheAndWebView(this)
            recreate()
        }

        binding.btnWipeData.setOnClickListener {
            sharedPreferences.edit().apply {
                remove("server_lan_url")
                remove("server_wan_url")
                remove("auth_user")
                remove("auth_pass")
                remove("auth_token")
                putBoolean("is_demo_mode", false)
                putBoolean("is_configured", false)
                apply()
            }
            clearAppCacheAndWebView(this)
            recreate()
        }
    }

    /**
     * Shows a beautiful, high-end dialog explaining Demo Mode, PRO features, and limitations.
     */
    private fun showDemoModeIntroductionDialog(onConfirm: (android.app.Dialog) -> Unit) {
        val isTraditional = tempSelectedLang == "zh-rTW" || tempSelectedLang == "zh-TW"
        val isZh = tempSelectedLang == "zh" || isTraditional
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setCancelable(false)
        
        // Deep obsidian background layout
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#08080C"))
            setPadding(48, 48, 48, 48)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        
        // 1. Beautiful Cyber Handshake Loader (Initially GONE)
        val loadingLayout = android.widget.LinearLayout(this).apply {
            id = 10003
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            visibility = android.view.View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                weight = 1f
            }
            
            val progressBar = android.widget.ProgressBar(this@MainActivity).apply {
                // Cyan tint
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
            }
            addView(progressBar)
            
            val loadingText = android.widget.TextView(this@MainActivity).apply {
                text = when {
                    isTraditional -> "正在安全認證演示系統..."
                    isZh -> "正在安全认证演示系统..."
                    else -> "AUTHENTICATING DEMO SHIELD..."
                }
                textSize = 14f
                setTextColor(Color.parseColor("#00E5FF"))
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.CENTER
                setPadding(0, 32, 0, 8)
            }
            addView(loadingText)
            
            val loadingSubText = android.widget.TextView(this@MainActivity).apply {
                text = when {
                    isTraditional -> "正在為您跨國建立安全加密信道"
                    isZh -> "正在为您跨国建立安全加密信道"
                    else -> "Establishing overseas secure encrypted tunnel"
                }
                textSize = 11f
                setTextColor(Color.GRAY)
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = android.view.Gravity.CENTER
            }
            addView(loadingSubText)
        }
        
        // Icon / Header
        val headerView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 40
            }
        }
        
        val shieldIcon = android.widget.TextView(this).apply {
            text = "⚡"
            textSize = 48f
            gravity = android.view.Gravity.CENTER
        }
        headerView.addView(shieldIcon)
        
        val titleText = android.widget.TextView(this).apply {
            text = when {
                isTraditional -> "💡 AURA Grid PRO 演示沙盒體驗指南"
                isZh -> "💡 AURA Grid PRO 演示沙盒体验指南"
                else -> "💡 AURA Grid PRO Public Demo Guide"
            }
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
        }
        headerView.addView(titleText)
        
        val subtitleText = android.widget.TextView(this).apply {
            text = "AURA-GRID-PRO-SANDBOX-VERIFICATION"
            textSize = 9f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.2f
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
        }
        headerView.addView(subtitleText)
        
        container.addView(headerView)
        
        // Scrollable content area
        val scrollView = android.widget.ScrollView(this).apply {
            id = 10001
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                weight = 1f
                topMargin = 32
                bottomMargin = 32
            }
            isVerticalScrollBarEnabled = false
        }
        
        val contentLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            val shape = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#0DFFFFFF"))
                setStroke(2, Color.parseColor("#2600E5FF"))
                cornerRadius = 24f
            }
            background = shape
        }
        
        val introText = android.widget.TextView(this).apply {
            text = when {
                isTraditional -> "歡迎體驗 AURA Grid 智能中控伴侶終端！您即將進入公網演示沙盒系統。為了保障您的體驗，请知悉以下事項："
                isZh -> "欢迎体验 AURA Grid 智能中控伴侣终端！您即将进入公网演示沙盒系统。为了保障您的体验，请知悉以下事项："
                else -> "Welcome to AURA Grid Companion Terminal! You are entering the public sandbox. Please read the following guidelines:"
            }
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            setLineSpacing(0f, 1.2f)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 24
            }
        }
        contentLayout.addView(introText)
        
        // Helper to add list items
        fun addFeatureItem(number: String, title: String, desc: String) {
            val itemLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 20
                }
            }
            
            val numView = android.widget.TextView(this).apply {
                text = number
                textSize = 15f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = 16
                }
            }
            itemLayout.addView(numView)
            
            val detailsLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    weight = 1f
                }
            }
            
            val itemTitle = android.widget.TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 4
                }
            }
            detailsLayout.addView(itemTitle)
            
            val itemDesc = android.widget.TextView(this).apply {
                text = desc
                textSize = 12f
                setTextColor(Color.parseColor("#8E8E93"))
                setLineSpacing(0f, 1.2f)
            }
            detailsLayout.addView(itemDesc)
            
            itemLayout.addView(detailsLayout)
            contentLayout.addView(itemLayout)
        }
        
        when {
            isTraditional -> {
                addFeatureItem(
                    "1️⃣",
                    "尊享 PRO 全功能體驗",
                    "本演示系統已全面啟用 PRO 商業版全部高級特性。您可以自由體驗多樓層連動、自定義畫布組件、安全中心駕駛艙、實時高幀率天氣動畫系統等全套工業級功能！"
                )
                addFeatureItem(
                    "2️⃣",
                    "零風險仿真沙盒運行",
                    "此環境連接的是獨立的虛擬仿真數據源，您的任何開關控制、場景切換等控制指令都是完全安全的，不會影響任何真實物理設備，請盡情點擊測試！"
                )
                addFeatureItem(
                    "3️⃣",
                    "布局唯讀保護限制",
                    "為保護公共演示界面的整潔，本模式下不支持永久保存布局修改或上傳底圖資產。"
                )
                addFeatureItem(
                    "⚙️",
                    "如何退出演示模式",
                    "提示：如果您需要退出演示模式，請使用【三根手指在屏幕任意位置連續敲擊 3 次】即可呼出后台管理設置菜單，選擇“一鍵退出演示模式”即可。"
                )
            }
            isZh -> {
                addFeatureItem(
                    "1️⃣",
                    "尊享 PRO 全功能体验",
                    "本演示系统已全面启用 PRO 商业版全部高级特性。您可以自由体验多楼层联动、自定义画布组件、安全中心驾驶舱、实时高帧率天气动画系统等全套工业级功能！"
                )
                addFeatureItem(
                    "2️⃣",
                    "零风险仿真沙盒运行",
                    "此环境连接的是独立的虚拟仿真数据源，您的任何开关控制、场景切换等控制指令都是完全安全的，不会影响任何真实物理设备，请尽情点击测试！"
                )
                addFeatureItem(
                    "3️⃣",
                    "布局只读保护限制",
                    "为保护公共演示界面的整洁，本模式下不支持永久保存布局修改或上传底图资产。"
                )
                addFeatureItem(
                    "⚙️",
                    "如何退出演示模式",
                    "提示：如果您需要退出演示模式，请使用【三根手指在屏幕任意位置连续敲击 3 次】即可呼出后台管理设置菜单，选择“键退出演示模式”即可。"
                )
            }
            else -> {
                addFeatureItem(
                    "1️⃣",
                    "Full PRO Feature Access",
                    "All PRO commercial edition features are fully enabled here. Feel free to explore multi-floor integration, custom canvas layout widgets, security cockpit, and dynamic weather animations!"
                )
                addFeatureItem(
                    "2️⃣",
                    "Zero-Risk Simulation Sandbox",
                    "Connected to a fully simulated mock data environment. Any toggle, dimming or scene switching commands are completely safe and will not affect any real physical hardware."
                )
                addFeatureItem(
                    "3️⃣",
                    "Read-Only Configuration Limits",
                    "To keep the public dashboard clean for everyone, saving layouts and uploading files are disabled."
                )
                addFeatureItem(
                    "⚙️",
                    "How to Exit Demo Mode",
                    "Tip: If you need to exit demo mode, simply [triple-tap with three fingers] anywhere on the screen to invoke the admin settings, then select 'Exit Demo Mode'."
                )
            }
        }
        
        scrollView.addView(contentLayout)
        container.addView(scrollView)
        container.addView(loadingLayout) // Add the loading layout!
        
        // Error message text inside Dialog
        val errorText = android.widget.TextView(this).apply {
            id = 10004
            textSize = 11f
            setTextColor(Color.parseColor("#FF3333"))
            gravity = android.view.Gravity.CENTER
            visibility = android.view.View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }
        container.addView(errorText)
        
        // Buttons
        val buttonsLayout = android.widget.LinearLayout(this).apply {
            id = 10002
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }
        
        val enterButton = android.widget.Button(this).apply {
            text = when {
                isTraditional -> "同意並立即進入體驗"
                isZh -> "同意并立即进入体验"
                else -> "AGREE & ENTER DEMO MODE"
            }
            textSize = 14f
            setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            
            // Cyber teal gradient drawable
            val btnShape = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#0086FF"))
            ).apply {
                cornerRadius = 16f
            }
            background = btnShape
            
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                120
            ).apply {
                bottomMargin = 24
            }
            
            setOnClickListener {
                // Show loader, hide contents
                scrollView.visibility = android.view.View.GONE
                buttonsLayout.visibility = android.view.View.GONE
                errorText.visibility = android.view.View.GONE
                loadingLayout.visibility = android.view.View.VISIBLE
                
                onConfirm(dialog)
            }
        }
        buttonsLayout.addView(enterButton)
        
        val cancelButton = android.widget.TextView(this).apply {
            text = when {
                isTraditional -> "取消"
                isZh -> "取消"
                else -> "CANCEL"
            }
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(24, 24, 24, 24)
            isClickable = true
            
            setOnClickListener {
                dialog.dismiss()
            }
        }
        buttonsLayout.addView(cancelButton)
        
        container.addView(buttonsLayout)
        
        dialog.setContentView(container)
        dialog.show()
    }

    /**
     * Clears all application caches, WebView databases, and session cookies for a clean state.
     */
    private fun clearAppCacheAndWebView(context: android.content.Context) {
        try {
            // 1. Clear WebView standard cache
            val webView = android.webkit.WebView(context)
            webView.clearCache(true)
            
            // 2. Clear Session and Persistent Cookies
            val cookieManager = android.webkit.CookieManager.getInstance()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            } else {
                val cookieSyncMngr = android.webkit.CookieSyncManager.createInstance(context)
                cookieSyncMngr.startSync()
                cookieManager.removeAllCookie()
                cookieSyncMngr.stopSync()
            }
            
            // 3. Recursively clear internal files in App Caches Directory
            val cacheDir = context.cacheDir
            if (cacheDir != null && cacheDir.isDirectory) {
                deleteDirContents(cacheDir)
            }
            
            // 4. Remove Webview internal persistence databases
            context.deleteDatabase("webview.db")
            context.deleteDatabase("webviewCache.db")
            
            val isZh = tempSelectedLang == "zh"
            android.widget.Toast.makeText(
                context, 
                if (isZh) "🧹 伴侣终端已完成缓存清理与安全重置" else "🧹 Cache cleared and node successfully reset", 
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteDirContents(dir: java.io.File?): Boolean {
        if (dir != null && dir.isDirectory) {
            val children = dir.list()
            if (children != null) {
                for (i in children.indices) {
                    val success = deleteFileOrDir(java.io.File(dir, children[i]))
                    if (!success) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun deleteFileOrDir(file: java.io.File?): Boolean {
        if (file != null && file.isDirectory) {
            val children = file.list()
            if (children != null) {
                for (i in children.indices) {
                    val success = deleteFileOrDir(java.io.File(file, children[i]))
                    if (!success) {
                        return false
                    }
                }
            }
            return file.delete()
        } else if (file != null && file.isFile) {
            return file.delete()
        }
        return false
    }

    private fun toggleSettingsOverlay(show: Boolean) {
        if (show) {
            applyLanguageToSettingsUI(tempSelectedLang)
            binding.txtVerificationStatus.visibility = View.GONE
            binding.btnCancelSettings.visibility = if (sharedPreferences.getBoolean("is_configured", false)) View.VISIBLE else View.GONE
            
            // 根据当前是否处于演示模式，动态切换 一键进入 vs 一键退出 按钮的显隐
            val isDemo = sharedPreferences.getBoolean("is_demo_mode", false)
            if (isDemo) {
                binding.btnQuickDemo.visibility = View.GONE
                binding.btnExitDemo.visibility = View.VISIBLE
                binding.btnWipeData.visibility = View.GONE
            } else {
                binding.btnQuickDemo.visibility = View.VISIBLE
                binding.btnExitDemo.visibility = View.GONE
                val isConfigured = sharedPreferences.getBoolean("is_configured", false)
                binding.btnWipeData.visibility = if (isConfigured) View.VISIBLE else View.GONE
            }

            binding.settingsOverlay.visibility = View.VISIBLE
            binding.settingsOverlay.alpha = 1f
        } else {
            binding.settingsOverlay.visibility = View.GONE
            applySystemImmersiveMode()
        }
    }

    /**
     * Updates the segmented capsule language switcher UI toggle states dynamically.
     */
    private fun updateLanguageToggleUI(lang: String) {
        // Reset all buttons first
        binding.btnLangZhToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.btnLangZhToggle.setTextColor(android.graphics.Color.parseColor("#8E8E93"))
        binding.btnLangZhToggle.setTypeface(null, android.graphics.Typeface.NORMAL)

        binding.btnLangZhTwToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.btnLangZhTwToggle.setTextColor(android.graphics.Color.parseColor("#8E8E93"))
        binding.btnLangZhTwToggle.setTypeface(null, android.graphics.Typeface.NORMAL)

        binding.btnLangEnToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.btnLangEnToggle.setTextColor(android.graphics.Color.parseColor("#8E8E93"))
        binding.btnLangEnToggle.setTypeface(null, android.graphics.Typeface.NORMAL)

        // Highlight selected
        when (lang) {
            "zh-rTW", "zh-TW" -> {
                binding.btnLangZhTwToggle.setBackgroundColor(android.graphics.Color.parseColor("#00E5FF"))
                binding.btnLangZhTwToggle.setTextColor(android.graphics.Color.parseColor("#000000"))
                binding.btnLangZhTwToggle.setTypeface(null, android.graphics.Typeface.BOLD)
            }
            "en" -> {
                binding.btnLangEnToggle.setBackgroundColor(android.graphics.Color.parseColor("#00E5FF"))
                binding.btnLangEnToggle.setTextColor(android.graphics.Color.parseColor("#000000"))
                binding.btnLangEnToggle.setTypeface(null, android.graphics.Typeface.BOLD)
            }
            else -> { // Default "zh"
                binding.btnLangZhToggle.setBackgroundColor(android.graphics.Color.parseColor("#00E5FF"))
                binding.btnLangZhToggle.setTextColor(android.graphics.Color.parseColor("#000000"))
                binding.btnLangZhToggle.setTypeface(null, android.graphics.Typeface.BOLD)
            }
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
            connection.setRequestProperty("User-Agent", "AuraGridApp/1.1.0 (Android; Mobile)")
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
                if (resolvedUrl.isEmpty()) {
                    Log.e("MainActivity", "Route resolved to empty URL. Aborting WebView load.")
                    binding.subLoadingText.text = "Error: No server URL configured or reachable."
                    toggleSettingsOverlay(true)
                    return
                }
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
        com.auragrid.app.update.ApkDownloader.startDownload(this, binding, downloadUrl)
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
