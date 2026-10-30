package com.auragrid.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * VoiceInputService: Dual-track hybrid speech recognition service.
 *
 * Track 1: System SpeechRecognizer (Default, zero CPU/memory overhead).
 * Track 2: On-Device Streaming Sherpa-onnx (Offline Zipformer int8 ~32MB, 2-thread low latency).
 *
 * Seamless Fallback Architecture:
 * - If system SpeechRecognizer is missing (no RecognitionService), or throws RECOGNIZER_UNAVAILABLE / ERROR_CLIENT,
 *   it transparently falls back to Sherpa-onnx.
 * - If user prefers offline or system repeatedly fails, preferences automatically persist offline mode.
 * - Outwardly dispatches identical `aura:voice` events (started, level, partial, final, ended).
 * - Vue frontend remains 100% untouched.
 */
class VoiceInputService(
    private val context: Context,
    private val sharedPreferences: SharedPreferences,
    private val emitEvent: (JSONObject) -> Unit
) {
    companion object {
        private const val TAG = "VoiceInputService"
        private const val LEVEL_THROTTLE_MS = 80L
        private const val PARTIAL_THROTTLE_MS = 100L
        private const val MAX_SESSION_DURATION_MS = 55000L // 55s hard limit
        private const val FINAL_FALLBACK_TIMEOUT_MS = 2000L // 2.0s fallback after stop
        private const val PREF_FORCE_OFFLINE_STT = "pref_force_offline_stt"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    // Track 2: Embedded On-Device Recognizer
    private val sherpaRecognizer: SherpaVoiceRecognizer by lazy {
        SherpaVoiceRecognizer(context, emitEvent)
    }

    private enum class ActiveEngine {
        NONE,
        SYSTEM,
        SHERPA
    }

    private var currentEngine = ActiveEngine.NONE
    private var activeSessionId: String? = null
    private var lastPartialText: String = ""
    private var hasEmittedFinal: Boolean = false
    private var isStopping: Boolean = false

    private var lastLevelEmitTime: Long = 0L
    private var lastPartialEmitTime: Long = 0L

    private var maxDurationRunnable: Runnable? = null
    private var finalFallbackRunnable: Runnable? = null

    init {
        // Pre-heat Sherpa-onnx engine in background so first fallback is instantaneous
        mainHandler.postDelayed({
            sherpaRecognizer.initIfNeeded()
        }, 1500)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    // MARK: - Permission Inspection

    fun getPermissionStatus(): String {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) return "authorized"
        val requestedBefore = sharedPreferences.getBoolean("voice_permission_requested", false)
        return if (requestedBefore) "denied" else "notDetermined"
    }

    // MARK: - Session Control

    fun start(localeIdentifier: String, sessionId: String, completion: (JSONObject) -> Unit) {
        runOnMain {
            // 1. Permission check
            val perm = getPermissionStatus()
            if (perm != "authorized") {
                Log.w(TAG, "Cannot start voice session: permission is $perm")
                completion(JSONObject().apply {
                    put("success", false)
                    put("code", "PERMISSION_DENIED")
                })
                return@runOnMain
            }

            // 2. Clean up any existing session
            if (activeSessionId != null) {
                cleanupCurrentSession(reason = "cancelled", emitEnded = true)
            }

            val forceOffline = sharedPreferences.getBoolean(PREF_FORCE_OFFLINE_STT, false)
            if (forceOffline) {
                Log.i(TAG, "Force offline STT mode enabled via preference, routing to Sherpa...")
                startWithSherpa(sessionId, completion)
                return@runOnMain
            }

            // 3. System SpeechRecognizer availability inspection
            val isSystemAvailable = SpeechRecognizer.isRecognitionAvailable(context)
            if (!isSystemAvailable) {
                Log.i(TAG, "System SpeechRecognizer not available on this device, falling back to Sherpa-onnx...")
                startWithSherpa(sessionId, completion)
                return@runOnMain
            }

            // 4. Try starting system SpeechRecognizer
            startWithSystem(localeIdentifier, sessionId, completion)
        }
    }

    private fun startWithSherpa(sessionId: String, completion: (JSONObject) -> Unit) {
        currentEngine = ActiveEngine.SHERPA
        activeSessionId = sessionId
        sherpaRecognizer.start(sessionId) { result ->
            val success = result.optBoolean("success", false)
            if (!success) {
                currentEngine = ActiveEngine.NONE
                activeSessionId = null
            }
            completion(result)
        }
    }

    private fun startWithSystem(localeIdentifier: String, sessionId: String, completion: (JSONObject) -> Unit) {
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer.createSpeechRecognizer failed: ${e.message}, falling back to Sherpa-onnx")
            startWithSherpa(sessionId, completion)
            return
        }

        val recognizer = speechRecognizer
        if (recognizer == null) {
            Log.w(TAG, "SpeechRecognizer is null, falling back to Sherpa-onnx")
            startWithSherpa(sessionId, completion)
            return
        }

        currentEngine = ActiveEngine.SYSTEM
        activeSessionId = sessionId
        lastPartialText = ""
        hasEmittedFinal = false
        isStopping = false
        lastLevelEmitTime = 0L
        lastPartialEmitTime = 0L

        try {
            recognizer.setRecognitionListener(createRecognitionListener(sessionId))

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                val lang = if (localeIdentifier.isNotBlank()) localeIdentifier else "zh-CN"
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            recognizer.startListening(intent)

            // Watchdog for max session duration (55s)
            val timeoutRunnable = Runnable {
                if (activeSessionId == sessionId && currentEngine == ActiveEngine.SYSTEM) {
                    Log.w(TAG, "System session reached max duration limit (55s): $sessionId")
                    emitEvent(JSONObject().apply {
                        put("type", "ended")
                        put("sessionId", sessionId)
                        put("reason", "timeout")
                    })
                    cleanupCurrentSession(reason = "timeout", emitEnded = false)
                }
            }
            maxDurationRunnable = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, MAX_SESSION_DURATION_MS)

            // Emit initial started event
            emitEvent(JSONObject().apply {
                put("type", "started")
                put("sessionId", sessionId)
            })
            completion(JSONObject().apply {
                put("success", true)
            })

        } catch (e: Exception) {
            Log.w(TAG, "System startListening failed: ${e.message}, attempting seamless Sherpa fallback", e)
            cleanupCurrentSession(reason = "error", emitEnded = false)
            startWithSherpa(sessionId, completion)
        }
    }

    fun stop(sessionId: String): JSONObject {
        runOnMain {
            if (activeSessionId != sessionId) {
                return@runOnMain
            }

            if (currentEngine == ActiveEngine.SHERPA) {
                sherpaRecognizer.stop(sessionId)
                return@runOnMain
            }

            if (isStopping) return@runOnMain
            Log.i(TAG, "Stopping system voice input for session: $sessionId")
            isStopping = true

            maxDurationRunnable?.let { mainHandler.removeCallbacks(it) }
            maxDurationRunnable = null

            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "stopListening error: ${e.message}")
            }

            // Fallback timer: wait up to 2.0s for onResults. If none arrives, deliver lastPartialText
            val fallback = Runnable {
                if (activeSessionId == sessionId && !hasEmittedFinal) {
                    hasEmittedFinal = true
                    Log.i(TAG, "Fallback timeout reached, delivering partial as final: '$lastPartialText'")
                    emitEvent(JSONObject().apply {
                        put("type", "final")
                        put("sessionId", sessionId)
                        put("text", lastPartialText)
                    })
                    cleanupCurrentSession(reason = "stopped", emitEnded = true)
                }
            }
            finalFallbackRunnable = fallback
            mainHandler.postDelayed(fallback, FINAL_FALLBACK_TIMEOUT_MS)
        }
        return JSONObject().apply { put("success", true) }
    }

    fun cancel(sessionId: String): JSONObject {
        runOnMain {
            if (currentEngine == ActiveEngine.SHERPA) {
                sherpaRecognizer.cancel(sessionId)
                currentEngine = ActiveEngine.NONE
                activeSessionId = null
                return@runOnMain
            }

            if (activeSessionId == sessionId || sessionId.isEmpty()) {
                Log.i(TAG, "Cancelling voice input for session: $sessionId")
                cleanupCurrentSession(reason = "cancelled", emitEnded = true)
            }
        }
        return JSONObject().apply { put("success", true) }
    }

    fun destroy() {
        runOnMain {
            cleanupCurrentSession(reason = "destroy", emitEnded = false)
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying speechRecognizer: ${e.message}")
            }
            speechRecognizer = null
            sherpaRecognizer.destroy()
        }
    }

    // MARK: - Internal Session & Listener Helpers

    private fun cleanupCurrentSession(reason: String, emitEnded: Boolean) {
        val sid = activeSessionId
        maxDurationRunnable?.let { mainHandler.removeCallbacks(it) }
        maxDurationRunnable = null
        finalFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        finalFallbackRunnable = null

        if (currentEngine == ActiveEngine.SYSTEM) {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                // Ignored
            }
        }

        currentEngine = ActiveEngine.NONE
        activeSessionId = null
        isStopping = false
        lastPartialText = ""

        if (emitEnded && sid != null) {
            emitEvent(JSONObject().apply {
                put("type", "ended")
                put("sessionId", sid)
                put("reason", reason)
            })
        }
    }

    private fun createRecognitionListener(sessionId: String): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (activeSessionId != sessionId) return
                Log.d(TAG, "System onReadyForSpeech")
                emitEvent(JSONObject().apply {
                    put("type", "started")
                    put("sessionId", sessionId)
                })
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "System onBeginningOfSpeech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (activeSessionId != sessionId || isStopping) return
                val now = SystemClock.uptimeMillis()
                if (now - lastLevelEmitTime < LEVEL_THROTTLE_MS) return
                lastLevelEmitTime = now

                // Normalize rmsdB (-2.0 ~ +10.0 dB typical Android range) to 0.0 ~ 1.0
                val power = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                emitEvent(JSONObject().apply {
                    put("type", "level")
                    put("sessionId", sessionId)
                    put("power", (power * 100).toInt() / 100.0)
                })
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "System onEndOfSpeech")
            }

            override fun onError(error: Int) {
                if (activeSessionId != sessionId) return
                Log.w(TAG, "System Recognition onError code=$error, isStopping=$isStopping, lastPartial='$lastPartialText'")

                finalFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                finalFallbackRunnable = null

                // If system error indicates recognizer service is unusable (ERROR_CLIENT / ERROR_RECOGNIZER_BUSY),
                // and user hasn't spoken partial text yet, trigger seamless Sherpa fallback
                if ((error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                    && lastPartialText.isBlank() && !isStopping) {
                    Log.i(TAG, "System SpeechRecognizer error=$error, auto-fallback to on-device Sherpa-onnx")
                    sharedPreferences.edit().putBoolean(PREF_FORCE_OFFLINE_STT, true).apply()
                    cleanupCurrentSession(reason = "fallback", emitEnded = false)
                    startWithSherpa(sessionId) { /* already started */ }
                    return
                }

                // If user stopped or speech timed out and we have partial text, treat as final success
                if ((isStopping || error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    && lastPartialText.isNotBlank() && !hasEmittedFinal) {
                    hasEmittedFinal = true
                    emitEvent(JSONObject().apply {
                        put("type", "final")
                        put("sessionId", sessionId)
                        put("text", lastPartialText)
                    })
                    cleanupCurrentSession(reason = "stopped", emitEnded = true)
                    return
                }

                if (!hasEmittedFinal) {
                    val code = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "AUDIO_ERROR"
                        SpeechRecognizer.ERROR_CLIENT -> "CLIENT_ERROR"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
                        SpeechRecognizer.ERROR_NETWORK -> "NETWORK_ERROR"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                        SpeechRecognizer.ERROR_SERVER -> "SERVER_ERROR"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                        else -> "UNKNOWN_$error"
                    }
                    emitEvent(JSONObject().apply {
                        put("type", "error")
                        put("sessionId", sessionId)
                        put("code", code)
                        put("text", lastPartialText)
                    })
                }
                cleanupCurrentSession(reason = "error", emitEnded = false)
            }

            override fun onResults(results: Bundle?) {
                if (activeSessionId != sessionId) return
                finalFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                finalFallbackRunnable = null

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val finalText = matches?.firstOrNull()?.trim() ?: lastPartialText.trim()
                Log.i(TAG, "System onResults final text: '$finalText'")

                if (!hasEmittedFinal) {
                    hasEmittedFinal = true
                    emitEvent(JSONObject().apply {
                        put("type", "final")
                        put("sessionId", sessionId)
                        put("text", finalText)
                    })
                }
                cleanupCurrentSession(reason = "results", emitEnded = true)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (activeSessionId != sessionId || isStopping) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim() ?: return
                if (partial.isEmpty() || partial == lastPartialText) return

                lastPartialText = partial
                val now = SystemClock.uptimeMillis()
                if (now - lastPartialEmitTime < PARTIAL_THROTTLE_MS) return
                lastPartialEmitTime = now

                emitEvent(JSONObject().apply {
                    put("type", "partial")
                    put("sessionId", sessionId)
                    put("text", partial)
                })
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}
