package com.example.wearhealthdatahub.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.example.wearhealthdatahub.BuildConfig
import com.example.wearhealthdatahub.data.HealthDataRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit

/** 時計固有の認証情報で受信サーバーに接続し、健康データを送信する。 */
class HealthDataWebSocketClient private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val pendingMessages = ArrayDeque<String>()
    private val deviceSecret = loadOrCreateDeviceSecret()
    private val deviceId = MessageDigest.getInstance("SHA-256")
        .digest(Base64.decode(deviceSecret, Base64.URL_SAFE or Base64.NO_WRAP))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val mutablePairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = mutablePairingCode.asStateFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isConnecting = false
    @Volatile private var authenticated = false
    @Volatile private var foregroundActive = false
    @Volatile private var nextConnectionAttemptAtMillis = 0L

    @Synchronized
    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
        if (active) {
            connectIfNeeded()
        } else {
            mutablePairingCode.value = null
            if (pendingMessages.isEmpty()) {
                webSocket?.close(1000, "画面終了")
                webSocket = null
                authenticated = false
            }
        }
    }

    fun send(records: List<HealthDataRecord>) {
        if (records.isEmpty() || BuildConfig.HEALTH_WEBSOCKET_URL.isBlank()) return
        val message = JSONObject()
            .put("type", "health-data")
            .put("deviceId", deviceId)
            .put("sentAt", Instant.now().toString())
            .put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
            .toString()
        synchronized(this) {
            val socket = webSocket
            if (authenticated && socket != null && socket.send(message)) return
            if (pendingMessages.size >= MAX_PENDING_MESSAGES) {
                pendingMessages.removeFirst()
                Log.w(TAG, "送信キュー上限のため最古のメッセージを破棄した")
            }
            pendingMessages.addLast(message)
            connectIfNeeded()
        }
    }

    @Synchronized
    private fun connectIfNeeded() {
        if (BuildConfig.HEALTH_WEBSOCKET_URL.isBlank() || webSocket != null || isConnecting) return
        val retryDelay = nextConnectionAttemptAtMillis - SystemClock.elapsedRealtime()
        if (retryDelay > 0) {
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, retryDelay)
            return
        }
        isConnecting = true
        runCatching {
            client.newWebSocket(
                Request.Builder().url(BuildConfig.HEALTH_WEBSOCKET_URL.toOkHttpUrl()).build(),
                listener,
            )
        }.onFailure {
            isConnecting = false
            scheduleReconnect()
            Log.e(TAG, "WebSocket URL が不正: ${BuildConfig.HEALTH_WEBSOCKET_URL}", it)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@HealthDataWebSocketClient) {
                if (!foregroundActive && pendingMessages.isEmpty()) {
                    isConnecting = false
                    webSocket.close(1000, "画面終了")
                    return
                }
                this@HealthDataWebSocketClient.webSocket = webSocket
                isConnecting = false
                authenticated = false
                nextConnectionAttemptAtMillis = 0L
                webSocket.send(
                    JSONObject()
                        .put("type", "device-hello")
                        .put("deviceId", deviceId)
                        .put("deviceSecret", deviceSecret)
                        .toString(),
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (message.optString("type")) {
                "device-ready" -> synchronized(this@HealthDataWebSocketClient) {
                    if (this@HealthDataWebSocketClient.webSocket !== webSocket) return
                    authenticated = true
                    mutablePairingCode.value = message.optString("pairingCode").takeIf { it.length == 8 }
                    while (pendingMessages.isNotEmpty()) {
                        if (!webSocket.send(pendingMessages.first())) break
                        pendingMessages.removeFirst()
                    }
                    if (!foregroundActive && pendingMessages.isEmpty()) {
                        webSocket.close(1000, "送信完了")
                    }
                    Log.i(TAG, "受信サーバーへ接続した")
                }
                "paired" -> mutablePairingCode.value = null
                "error" -> Log.e(TAG, "サーバー拒否: ${message.optString("message")}")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect(webSocket, "切断: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDisconnect(webSocket, "接続失敗: ${t.message}")
        }
    }

    private fun handleDisconnect(disconnectedSocket: WebSocket, message: String) {
        synchronized(this) {
            if (webSocket === disconnectedSocket || webSocket == null) {
                webSocket = null
                isConnecting = false
                authenticated = false
                mutablePairingCode.value = null
            }
        }
        Log.w(TAG, message)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        synchronized(this) {
            if (!foregroundActive && pendingMessages.isEmpty()) return
            nextConnectionAttemptAtMillis = SystemClock.elapsedRealtime() + RECONNECT_DELAY_MILLIS
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, RECONNECT_DELAY_MILLIS)
        }
    }

    private val reconnect = Runnable {
        synchronized(this) {
            if (foregroundActive || pendingMessages.isNotEmpty()) connectIfNeeded()
        }
    }

    private fun loadOrCreateDeviceSecret(): String {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.getString(KEY_DEVICE_SECRET, null)?.let { existing ->
            if (runCatching { Base64.decode(existing, Base64.URL_SAFE or Base64.NO_WRAP).size == 32 }
                    .getOrDefault(false)) return existing
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .also { preferences.edit().putString(KEY_DEVICE_SECRET, it).apply() }
    }

    private fun String.toOkHttpUrl() = when {
        startsWith("ws://", ignoreCase = true) -> "http://${substring(5)}"
        startsWith("wss://", ignoreCase = true) -> "https://${substring(6)}"
        else -> this
    }.toHttpUrl()

    companion object {
        private const val TAG = "HealthWebSocket"
        private const val MAX_PENDING_MESSAGES = 200
        private const val RECONNECT_DELAY_MILLIS = 5_000L
        private const val PREFERENCES_NAME = "websocket_transport"
        private const val KEY_DEVICE_SECRET = "device_secret"

        @Volatile private var instance: HealthDataWebSocketClient? = null
        fun getInstance(context: Context): HealthDataWebSocketClient =
            instance ?: synchronized(this) {
                instance ?: HealthDataWebSocketClient(context).also { instance = it }
            }
    }
}
