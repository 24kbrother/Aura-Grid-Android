package com.auragrid.app

import android.util.Log
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
