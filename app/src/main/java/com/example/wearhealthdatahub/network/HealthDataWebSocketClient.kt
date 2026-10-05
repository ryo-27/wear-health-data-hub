package com.example.wearhealthdatahub.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.example.wearhealthdatahub.data.HealthDataRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** 時計で指定した PC のローカル受信サーバーへ健康データを送る。 */
class HealthDataWebSocketClient private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
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
    private val mutableServerIp = MutableStateFlow(preferences.getString(KEY_SERVER_IP, null)?.let(::normalizeIpv4))
    val serverIp: StateFlow<String?> = mutableServerIp.asStateFlow()
    private val mutablePairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = mutablePairingCode.asStateFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isConnecting = false
    @Volatile private var authenticated = false
    @Volatile private var foregroundActive = false
    @Volatile private var nextConnectionAttemptAtMillis = 0L
    private var connectionGeneration = 0

    @Synchronized
    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
        if (active) {
            connectIfNeeded()
        } else {
            mutablePairingCode.value = null
            if (pendingMessages.isEmpty()) {
                connectionGeneration++
                webSocket?.close(1000, "画面終了")
                webSocket = null
                authenticated = false
                isConnecting = false
                handler.removeCallbacks(reconnect)
            }
        }
    }

    /** 利用者が時計で入力した PC の IPv4 アドレスを保存し、接続を切り替える。 */
    @Synchronized
    fun setServerIp(input: String): Boolean {
        val ip = normalizeIpv4(input) ?: return false
        if (mutableServerIp.value == ip) return true
        preferences.edit().putString(KEY_SERVER_IP, ip).apply()
        mutableServerIp.value = ip
        pendingMessages.clear()
        mutablePairingCode.value = null
        connectionGeneration++
        webSocket?.close(1000, "PC 接続先変更")
        webSocket = null
        authenticated = false
        isConnecting = false
        nextConnectionAttemptAtMillis = 0L
        handler.removeCallbacks(reconnect)
        if (foregroundActive) connectIfNeeded()
        return true
    }

    fun send(records: List<HealthDataRecord>) {
        if (records.isEmpty() || mutableServerIp.value == null) return
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
        val ip = mutableServerIp.value ?: return
        if ((!foregroundActive && pendingMessages.isEmpty()) || webSocket != null || isConnecting) return
        val retryDelay = nextConnectionAttemptAtMillis - SystemClock.elapsedRealtime()
        if (retryDelay > 0) {
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, retryDelay)
            return
        }
        isConnecting = true
        val generation = ++connectionGeneration
        runCatching {
            client.newWebSocket(
                Request.Builder().url("http://$ip:$SERVER_PORT/ingest").build(),
                createListener(generation),
            )
        }.onFailure {
            if (generation == connectionGeneration) {
                isConnecting = false
                scheduleReconnect()
            }
            Log.e(TAG, "PC 接続先が不正: $ip", it)
        }
    }

    private fun createListener(generation: Int) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@HealthDataWebSocketClient) {
                if (generation != connectionGeneration || (!foregroundActive && pendingMessages.isEmpty())) {
                    webSocket.close(1000, "接続不要")
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
            synchronized(this@HealthDataWebSocketClient) {
                if (generation != connectionGeneration || this@HealthDataWebSocketClient.webSocket !== webSocket) return
                when (message.optString("type")) {
                    "device-ready" -> {
                        authenticated = true
                        mutablePairingCode.value = message.optString("pairingCode").takeIf { it.length == 8 }
                        while (pendingMessages.isNotEmpty()) {
                            if (!webSocket.send(pendingMessages.first())) break
                            pendingMessages.removeFirst()
                        }
                        if (!foregroundActive && pendingMessages.isEmpty()) webSocket.close(1000, "送信完了")
                        Log.i(TAG, "PC の受信サーバーへ接続した")
                    }
                    "paired" -> mutablePairingCode.value = null
                    "error" -> Log.e(TAG, "サーバー拒否: ${message.optString("message")}")
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect(generation, webSocket, "切断: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDisconnect(generation, webSocket, "接続失敗: ${t.message}")
        }
    }

    private fun handleDisconnect(generation: Int, disconnectedSocket: WebSocket, message: String) {
        synchronized(this) {
            if (generation != connectionGeneration) return
            if (webSocket === disconnectedSocket || webSocket == null) {
                webSocket = null
                isConnecting = false
                authenticated = false
                mutablePairingCode.value = null
                Log.w(TAG, message)
                scheduleReconnect()
            }
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!foregroundActive && pendingMessages.isEmpty()) return
        nextConnectionAttemptAtMillis = SystemClock.elapsedRealtime() + RECONNECT_DELAY_MILLIS
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, RECONNECT_DELAY_MILLIS)
    }

    private val reconnect = Runnable {
        synchronized(this) {
            if (foregroundActive || pendingMessages.isNotEmpty()) connectIfNeeded()
        }
    }

    private fun loadOrCreateDeviceSecret(): String {
        preferences.getString(KEY_DEVICE_SECRET, null)?.let { existing ->
            if (runCatching { Base64.decode(existing, Base64.URL_SAFE or Base64.NO_WRAP).size == 32 }
                    .getOrDefault(false)) return existing
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .also { preferences.edit().putString(KEY_DEVICE_SECRET, it).apply() }
    }

    companion object {
        private const val TAG = "HealthWebSocket"
        private const val SERVER_PORT = 8080
        private const val MAX_PENDING_MESSAGES = 200
        private const val RECONNECT_DELAY_MILLIS = 5_000L
        private const val PREFERENCES_NAME = "websocket_transport"
        private const val KEY_DEVICE_SECRET = "device_secret"
        private const val KEY_SERVER_IP = "server_ip"

        /** IP だけを受け付け、送信パスとポートはアプリが固定する。 */
        private fun normalizeIpv4(input: String): String? {
            val parts = input.trim().split('.')
            if (parts.size != 4) return null
            val octets = parts.map { part ->
                val value = part.toIntOrNull() ?: return null
                if (value !in 0..255 || part != value.toString()) return null
                value
            }
            if (octets[0] == 0 || octets[0] == 127 || octets[0] >= 224 ||
                (octets[0] == 169 && octets[1] == 254)) return null
            return octets.joinToString(".")
        }

        @Volatile private var instance: HealthDataWebSocketClient? = null
        fun getInstance(context: Context): HealthDataWebSocketClient =
            instance ?: synchronized(this) {
                instance ?: HealthDataWebSocketClient(context).also { instance = it }
            }
    }
}
