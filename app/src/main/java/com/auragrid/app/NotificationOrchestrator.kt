package com.auragrid.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Handles all notification orchestration.
 * Configures separate notification channels and manages wake locks
 * to wake up Kiosk tablet displays when critical events (smoke, doorbell) trigger.
 */
class NotificationOrchestrator(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    companion object {
        const val CHANNEL_CRITICAL = "aura_channel_critical"
        const val CHANNEL_WARNING = "aura_channel_warning"
        const val CHANNEL_INFO = "aura_channel_info"
        const val CHANNEL_SERVICE = "aura_channel_service"
        
        const val NOTIFICATION_SERVICE_ID = 9999
    }

    init {
        createNotificationChannels()
    }

    /**
     * Set up Oreo+ Notification Channels with distinct priorities and alerts.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1. Silent Foreground Keepalive Channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Aura Server Monitor",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the real-time background dashboard connection active."
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // 2. Info / Broadcast channel
            val infoChannel = NotificationChannel(
                CHANNEL_INFO,
                "System Broadcasts",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Non-intrusive system notifications and updates."
            }
            notificationManager.createNotificationChannel(infoChannel)

            // 3. Warning Channel (Battery Low, Node Offline)
            val warningChannel = NotificationChannel(
                CHANNEL_WARNING,
                "Device Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Critical alerts about battery level or offline status."
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(warningChannel)

            // 4. Critical Alert Channel (Doorbell Ring, Smoke Detector, Leak Sensor)
            val criticalChannel = NotificationChannel(
                CHANNEL_CRITICAL,
                "Emergency / Doorbell Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time emergency alarms that bypass DND settings."
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500)
                
                // Assign a highly distinct alarm sound
                val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                setSound(alarmSound, audioAttributes)
                
                // Allow bypassing Do Not Disturb (DND)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setBypassDnd(true)
                }
            }
            notificationManager.createNotificationChannel(criticalChannel)
        }
    }

    /**
     * Wakes the screen and fires a high-priority system notification.
     */
    fun triggerAlertNotification(
        id: String,
        title: String,
        message: String,
        severity: String,
        cameraEntityId: String? = null
    ) {
        Log.i("AuraOrchestrator", "Received notification request: $title [$severity]")

        // 1. If CRITICAL and screen is off, request WakeLock to turn screen ON immediately
        if (severity.equals("CRITICAL", ignoreCase = true)) {
            wakeScreen()
        }

        // 2. Prepare intent to open MainActivity when user taps the notification
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ALERT_ID", id)
            putExtra("OPEN_CAMERA_ENTITY", cameraEntityId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Resolve channel & styling based on severity
        val channelId = when (severity.uppercase()) {
            "CRITICAL" -> CHANNEL_CRITICAL
            "WARNING" -> CHANNEL_WARNING
            else -> CHANNEL_INFO
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(
                if (severity.uppercase() == "CRITICAL") NotificationCompat.PRIORITY_MAX 
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(
                if (severity.uppercase() == "CRITICAL") NotificationCompat.CATEGORY_ALARM 
                else NotificationCompat.CATEGORY_MESSAGE
            )

        if (severity.uppercase() == "CRITICAL") {
            builder.setFullScreenIntent(pendingIntent, true) // Force popup on top of current screen
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Make visible on lockscreen
        }

        notificationManager.notify(id.hashCode(), builder.build())
    }

    /**
     * Cancels an active system notification.
     */
    fun cancelNotification(id: String) {
        notificationManager.cancel(id.hashCode())
    }

    /**
     * Point-of-entry to forcefully point-light the tablet screen (WakeLock).
     */
    private fun wakeScreen() {
        try {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "AuraGrid:EmergencyWakeScreen"
            )
            // Acquire wake lock for 30 seconds to allow the user to see the pop-up
            wakeLock.acquire(30000L)
            Log.d("AuraOrchestrator", "WakeLock successfully acquired. Screen turned on.")
        } catch (e: Exception) {
            Log.e("AuraOrchestrator", "Failed to acquire screen WakeLock: ${e.message}")
        }
    }
}
