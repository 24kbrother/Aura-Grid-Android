package com.auragrid.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.*

/**
 * Full-screen Earthquake Early Warning takeover activity.
 *
 * Displays a countdown clock, epicenter info grid, safety guidance,
 * and a slide-to-dismiss safety lock bar.
 */
class EarthquakeAlertActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "EarthquakeAlert"
        private const val S_WAVE_SPEED = 3.4 // km/s
    }

    // Earthquake data
    private var eventId: String = ""
    private var eqLatitude: Double = 0.0
    private var eqLongitude: Double = 0.0
    private var originTime: Long = 0L // unix ms
    private var magnitude: Double = 0.0
    private var depth: Double = 0.0
    private var epicenter: String = "未知"
    private var pushDistance: Double = 0.0
    private var pushCountdown: Double = 0.0
    private var localIntensity: Double = 0.0

    // Timers
    private var countdown: Double = 0.0
    private var elapsedShaking: Double = 0.0
    private val handler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    // UI references
    private lateinit var tvCountdown: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvEpicenter: TextView
    private lateinit var tvMagnitude: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvIntensity: TextView
    private lateinit var slideTrack: View
    private lateinit var slideHandle: View
    private var slideProgress: Float = 0f

    // Audio / Vibration
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    // WakeLock
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on and show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Hide system bars
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        setContentView(R.layout.activity_earthquake_alert)

        // Acquire WakeLock to turn on screen
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "AuraGrid:EEWWakeLock"
        )
        wakeLock?.acquire(120_000L) // hold for 2 minutes max

        parseIntent()
        bindViews()
        calibrateCountdown()
        setupSlideToDismiss()
        startTimers()
        startAlarm()
    }

    private fun parseIntent() {
        val extras = intent.extras ?: return
        eventId       = extras.getString("eventId", "")
        eqLatitude    = extras.getDouble("latitude", 0.0)
        eqLongitude   = extras.getDouble("longitude", 0.0)
        originTime    = extras.getLong("originTime", 0L)
        magnitude     = extras.getDouble("magnitude", 0.0)
        depth         = extras.getDouble("depth", 0.0)
        epicenter     = extras.getString("epicenter", "未知震中") ?: "未知震中"
        pushDistance  = extras.getDouble("distance", 0.0)
        pushCountdown = extras.getDouble("countdown", 0.0)
        localIntensity = extras.getDouble("localIntensity", 0.0)
    }

    private fun bindViews() {
        tvCountdown = findViewById(R.id.eew_countdown)
        tvStatus    = findViewById(R.id.eew_status)
        tvEpicenter = findViewById(R.id.eew_epicenter)
        tvMagnitude = findViewById(R.id.eew_magnitude)
        tvDistance  = findViewById(R.id.eew_distance)
        tvIntensity = findViewById(R.id.eew_intensity)
        slideTrack  = findViewById(R.id.slide_track)
        slideHandle = findViewById(R.id.slide_handle)

        tvEpicenter.text = epicenter
        tvMagnitude.text = "M${String.format("%.1f", magnitude)}"
        tvIntensity.text = String.format("%.1f", localIntensity)

        // Color intensity based on severity
        if (localIntensity >= 6.0) {
            tvIntensity.setTextColor(Color.parseColor("#EF4444"))
        } else if (localIntensity >= 4.0) {
            tvIntensity.setTextColor(Color.parseColor("#FB923C"))
        }
    }

    /**
     * Calibrate countdown: compute local distance using GPS/home coords,
     * correct for FCM transmission delay using originTime timestamp.
     */
    private fun calibrateCountdown() {
        // Try to get phone GPS location or fall back to saved home coordinates
        val prefs = getSharedPreferences("AuraGridPreferences", Context.MODE_PRIVATE)
        val homeLat = prefs.getFloat("home_latitude", 0f).toDouble()
        val homeLon = prefs.getFloat("home_longitude", 0f).toDouble()

        var phoneLat: Double = 0.0
        var phoneLon: Double = 0.0
        try {
            val locManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val lastLoc = locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastLoc != null) {
                phoneLat = lastLoc.latitude
                phoneLon = lastLoc.longitude
            } else {
                phoneLat = homeLat
                phoneLon = homeLon
            }
        } catch (e: SecurityException) {
            phoneLat = homeLat
            phoneLon = homeLon
        }

        // Compute Haversine distance
        val localDist = if (phoneLat != 0.0 && phoneLon != 0.0) {
            haversine(phoneLat, phoneLon, eqLatitude, eqLongitude)
        } else {
            pushDistance
        }

        tvDistance.text = "${String.format("%.0f", localDist)} km"

        // Time calibration: S-wave travel time minus elapsed transmission time
        val tTravel = localDist / S_WAVE_SPEED
        val nowMs = System.currentTimeMillis()
        val deltaT = (nowMs - originTime) / 1000.0
        countdown = tTravel - deltaT

        Log.i(TAG, "Calibrated: distance=$localDist km, tTravel=$tTravel s, deltaT=$deltaT s, countdown=$countdown s")

        // Fall back to push countdown if calibration yields absurd result
        if (countdown < -120 && pushCountdown > 0) {
            countdown = pushCountdown
            Log.w(TAG, "Calibration result unreasonable, using push countdown: $pushCountdown")
        }
    }

    private fun startTimers() {
        tickRunnable = object : Runnable {
            override fun run() {
                if (countdown > 0) {
                    countdown = max(0.0, countdown - 1)
                    tvCountdown.text = "${countdown.toInt()}"
                    tvStatus.text = "S波横波即将到达"
                    tvCountdown.setTextColor(Color.WHITE)
                } else {
                    elapsedShaking += 1
                    tvCountdown.text = "${elapsedShaking.toInt()}"
                    tvStatus.text = "横波已到达，避险中 已避险${elapsedShaking.toInt()}秒"
                    tvCountdown.setTextColor(Color.parseColor("#FBBF24"))
                    if (elapsedShaking >= 60) {
                        finish()
                        return
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(tickRunnable!!)
    }

    private fun stopTimers() {
        tickRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun setupSlideToDismiss() {
        var startX = 0f
        var initialProgress = 0f

        slideHandle.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    initialProgress = slideProgress
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val trackWidth = slideTrack.width - view.width
                    if (trackWidth > 0) {
                        slideProgress = max(0f, min(1f, initialProgress + dx / trackWidth))
                        updateSlideUI()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (slideProgress >= 0.95f) {
                        slideProgress = 1f
                        updateSlideUI()
                        dismissAlert()
                    } else {
                        slideProgress = 0f
                        updateSlideUI()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateSlideUI() {
        val trackWidth = slideTrack.width - slideHandle.width
        slideHandle.translationX = trackWidth * slideProgress

        // Update fill
        val fill = slideTrack.findViewById<View>(R.id.slide_fill)
        fill?.layoutParams?.width = (slideTrack.width * slideProgress).toInt()
        fill?.requestLayout()
    }

    private fun dismissAlert() {
        stopTimers()
        stopAlarm()
        wakeLock?.let { if (it.isHeld) it.release() }
        finish()
    }

    private fun startAlarm() {
        // Vibrate
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibrator = vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 500, 200, 500, 200, 500),
                        0 // repeat from start
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration not available: ${e.message}")
        }

        // Play alarm sound
        try {
            val alarmUri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@EarthquakeAlertActivity, alarmUri)
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .build()
                )
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Alarm sound not available: ${e.message}")
        }
    }

    private fun stopAlarm() {
        vibrator?.cancel()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    override fun onDestroy() {
        stopTimers()
        stopAlarm()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ─────────────────────────────────────────────
    // Haversine Formula
    // ─────────────────────────────────────────────

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = (lat2 - lat1) * PI / 180
        val dLon = (lon2 - lon1) * PI / 180
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * PI / 180) * cos(lat2 * PI / 180) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}
