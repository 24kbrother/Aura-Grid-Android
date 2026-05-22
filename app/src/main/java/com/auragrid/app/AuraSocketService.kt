package com.auragrid.app

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

/**
 * Foreground Service that maintains an active local Socket.IO connection
 * to the Grid-Pro NestJS backend, providing millisecond-level responsiveness
 * for critical alarms and doorbell alerts in local networks.
 */
class AuraSocketService : Service() {

    private var mSocket: Socket? = null
    private lateinit var orchestrator: NotificationOrchestrator
    private var serverUrl = "http://10.0.0.90:3001"

    override fun onCreate() {
        super.onCreate()
        orchestrator = NotificationOrchestrator(this)
        Log.i("AuraSocketService", "Aura Background Monitor Service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newUrl = intent?.getStringExtra("SERVER_URL")
        if (newUrl != null && newUrl != serverUrl) {
            serverUrl = newUrl
            Log.i("AuraSocketService", "Switching service target server connection to: $serverUrl")
            disconnectSocket()
        }

        // Establish constant foreground notification presence to avoid OS thread suspension
        val notification = createForegroundNotification()
        startForeground(NotificationOrchestrator.NOTIFICATION_SERVICE_ID, notification)

        // Connect the WebSocket
        connectSocket()

        // Keep the service alive even if system kills it momentarily
        return START_STICKY
    }

    private fun createForegroundNotification(): Notification {
        // Prepare intent to open settings if user clicks service notification
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationOrchestrator.CHANNEL_SERVICE)
            .setContentTitle("Aura Grid Monitor")
            .setContentText("Listening for real-time smart home alerts...")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Spawns Socket.IO connection thread.
     */
    @Synchronized
    private fun connectSocket() {
        if (serverUrl.isEmpty()) {
            Log.w("AuraSocketService", "Server URL is empty, skipping Socket.IO connection.")
            return
        }

        if (mSocket != null && mSocket!!.connected()) {
            return
        }

        try {
            Log.d("AuraSocketService", "Initializing Socket.IO client targeting: $serverUrl")
            val opts = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionDelay = 2000
                timeout = 10000
            }
            mSocket = IO.socket(serverUrl, opts)
        } catch (e: URISyntaxException) {
            Log.e("AuraSocketService", "Invalid WebSocket URL format: ${e.message}")
            return
        }

        // Hook Socket.IO Events
        mSocket?.on(Socket.EVENT_CONNECT) {
            Log.i("AuraSocketService", "Socket.IO fully connected to Grid-Pro backend.")
        }

        mSocket?.on(Socket.EVENT_DISCONNECT) {
            Log.w("AuraSocketService", "Socket.IO disconnected from Grid-Pro backend.")
        }

        mSocket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = if (args.isNotEmpty()) args[0].toString() else "Unknown connection error"
            Log.w("AuraSocketService", "Socket.IO Connection Error: $error")
        }

        // Real-time Critical Alerts (Doorbell, Leak, Smoke)
        mSocket?.on("alert_event") { args ->
            if (args.isNotEmpty()) {
                try {
                    val payload = args[0] as JSONObject
                    Log.i("AuraSocketService", "Received real-time critical alert payload: $payload")
                    
                    val id = payload.optString("id", System.currentTimeMillis().toString())
                    val title = payload.optString("title", "Aura Alert Triggered")
                    val message = payload.optString("message", "A sensor has detected an anomaly.")
                    val severity = payload.optString("severity", "CRITICAL")
                    val cameraEntity = payload.optString("cameraEntityId", null)

                    orchestrator.triggerAlertNotification(id, title, message, severity, cameraEntity)
                } catch (e: Exception) {
                    Log.e("AuraSocketService", "Failed to parse alert_event: ${e.message}")
                }
            }
        }

        // Non-intrusive Broadcast Events (Aura Central news/updates)
        mSocket?.on("broadcast_event") { args ->
            if (args.isNotEmpty()) {
                try {
                    val payload = args[0] as JSONObject
                    Log.i("AuraSocketService", "Received system broadcast event: $payload")
                    
                    val id = payload.optString("id", System.currentTimeMillis().toString())
                    val title = payload.optString("title", "Aura Central Broadcast")
                    val message = payload.optString("message", "New administrative update posted.")
                    
                    orchestrator.triggerAlertNotification(id, title, message, "INFO")
                } catch (e: Exception) {
                    Log.e("AuraSocketService", "Failed to parse broadcast_event: ${e.message}")
                }
            }
        }

        // Standard notification event fallback
        mSocket?.on("notification") { args ->
            if (args.isNotEmpty()) {
                try {
                    val payload = args[0] as JSONObject
                    val id = payload.optString("id", System.currentTimeMillis().toString())
                    val title = payload.optString("title", "Notification")
                    val message = payload.optString("message", "")
                    val severity = payload.optString("severity", "INFO")

                    orchestrator.triggerAlertNotification(id, title, message, severity)
                } catch (e: Exception) {
                    Log.e("AuraSocketService", "Failed to parse generic notification: ${e.message}")
                }
            }
        }

        mSocket?.connect()
    }

    @Synchronized
    private fun disconnectSocket() {
        mSocket?.disconnect()
        mSocket?.off()
        mSocket = null
        Log.i("AuraSocketService", "Socket.IO client disconnected and cleaned.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectSocket()
        Log.i("AuraSocketService", "Aura Background Monitor Service destroyed.")
    }
}
