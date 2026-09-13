package com.auragrid.app

import android.util.Log
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Service that receives Firebase Cloud Messaging (FCM) push notifications.
 * Runs in the background (even when the application is completely killed or in deep sleep),
 * waking up the device for critical alerts (e.g. out-of-home doorbell or emergency events).
 */
class AuraFcmService : FirebaseMessagingService() {

    private lateinit var orchestrator: NotificationOrchestrator

    override fun onCreate() {
        super.onCreate()
        orchestrator = NotificationOrchestrator(this)
    }

    /**
     * Triggered when a new FCM message is received.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("AuraFcmService", "Received incoming FCM message from sender: ${remoteMessage.from}")

        // 1. Process custom data payloads (our NestJS backend sends key-value structured data)
        if (remoteMessage.data.isNotEmpty()) {
            try {
                val data = remoteMessage.data

                // ── Earthquake Early Warning interception ──
                val eventType = data["type"] ?: data["event"] ?: ""
                if (eventType == "earthquake_alert") {
                    handleEarthquakeAlert(data)
                    return
                }

                val id = data["id"] ?: System.currentTimeMillis().toString()
                val title = data["title"] ?: "Aura Emergency Push"
                val message = data["message"] ?: "A critical alert has been dispatched."
                val severity = data["severity"] ?: "CRITICAL"
                val cameraEntityId = data["cameraEntityId"]

                Log.i("AuraFcmService", "FCM Data Payload: $data")
                orchestrator.triggerAlertNotification(id, title, message, severity, cameraEntityId)
            } catch (e: Exception) {
                Log.e("AuraFcmService", "Failed to parse FCM data payload: ${e.message}")
            }
        }

        // 2. Process standard cloud notification payloads if present
        remoteMessage.notification?.let {
            Log.d("AuraFcmService", "FCM Notification Title: ${it.title}, Body: ${it.body}")
            val title = it.title ?: "Aura Cloud Update"
            val message = it.body ?: ""
            // Default standard notification payloads to INFO channel
            orchestrator.triggerAlertNotification(
                System.currentTimeMillis().toString(),
                title,
                message,
                "INFO"
            )
        }
    }

    /**
     * Triggered when Firebase issues a new registration Token (e.g. app installed, updated, cleared data).
     * We should upload this token to the backend server to associate this device with alerts.
     */
    /**
     * Handles earthquake early warning FCM data payload.
     * All values are strings (FCM data-only message constraint).
     */
    private fun handleEarthquakeAlert(data: Map<String, String>) {
        Log.i("AuraFcmService", "🌊 Earthquake alert received via FCM!")

        val eventId     = data["eventId"]     ?: ""
        val latitude    = data["latitude"]?.toDoubleOrNull()    ?: 0.0
        val longitude   = data["longitude"]?.toDoubleOrNull()   ?: 0.0
        val originTime  = data["originTime"]?.toLongOrNull()    ?: 0L  // unix ms
        val magnitude   = data["magnitude"]?.toDoubleOrNull()   ?: 0.0
        val depth       = data["depth"]?.toDoubleOrNull()       ?: 0.0
        val epicenter   = data["epicenter"] ?: "未知震中"
        val distance    = data["distance"]?.toDoubleOrNull()    ?: 0.0
        val countdown   = data["countdown"]?.toDoubleOrNull()   ?: 0.0
        val localIntensity = data["localIntensity"]?.toDoubleOrNull() ?: 0.0

        Log.i("AuraFcmService", "EEW: $epicenter M$magnitude eventId=$eventId distance=$distance km")

        // Launch fullscreen EarthquakeAlertActivity
        val intent = Intent(this, EarthquakeAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("eventId", eventId)
            putExtra("latitude", latitude)
            putExtra("longitude", longitude)
            putExtra("originTime", originTime)
            putExtra("magnitude", magnitude)
            putExtra("depth", depth)
            putExtra("epicenter", epicenter)
            putExtra("distance", distance)
            putExtra("countdown", countdown)
            putExtra("localIntensity", localIntensity)
        }
        startActivity(intent)

        // Also fire critical notification with full-screen intent as fallback
        orchestrator.triggerEarthquakeNotification(
            eventId, epicenter, magnitude, localIntensity
        )
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i("AuraFcmService", "Generated new FCM Registration Token: $token")
        
        // Save the token locally to expose it via our JS Bridge's window.AuraNative.getPushToken()
        val sharedPreferences = getSharedPreferences("AuraGridPreferences", MODE_PRIVATE)
        sharedPreferences.edit().putString("fcm_push_token", token).apply()
        
        // The Vue 3 client will automatically call window.AuraNative.getPushToken()
        // and register it with the back-end via POST /api/v1/device/register
    }
}
