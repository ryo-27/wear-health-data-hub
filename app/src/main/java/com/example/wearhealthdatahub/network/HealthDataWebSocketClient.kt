package com.example.wearhealthdatahub.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.wearhealthdatahub.BuildConfig
import com.example.wearhealthdatahub.data.HealthDataRecord
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * HealthDataRecordを開発PCのWebダッシュボードへ送るプロセス内WebSocketクライアント。
 *
 * 接続が切れている間は上限付きキューへ保持し、再接続時に古い順から送信する。
 */
class HealthDataWebSocketClient private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val pendingMessages = ArrayDeque<String>()
    private val deviceId = loadOrCreateDeviceId()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isConnecting = false

    @Volatile
    private var nextConnectionAttemptAtMillis = 0L

    fun send(records: List<HealthDataRecord>) {
        if (records.isEmpty() || BuildConfig.HEALTH_WEBSOCKET_URL.isBlank()) return

        val message = JSONObject()
            .put("type", "health-data")
            .put("deviceId", deviceId)
            .put("sentAt", Instant.now().toString())
            .put("records", JSONArray().apply {
                records.forEach { put(it.toJson()) }
            })
            .toString()

        synchronized(this) {
            val socket = webSocket
            if (socket != null && socket.send(message)) return

            if (pendingMessages.size >= MAX_PENDING_MESSAGES) {
                pendingMessages.removeFirst()
                Log.w(TAG, "送信キュー上限のため最古のWebSocketメッセージを破棄しました")
            }
            pendingMessages.addLast(message)
            connectIfNeeded()
        }
    }

    @Synchronized
    private fun connectIfNeeded() {
        if (webSocket != null || isConnecting) return

        val retryDelay = nextConnectionAttemptAtMillis - SystemClock.elapsedRealtime()
        if (retryDelay > 0) {
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, retryDelay)
            return
        }
        isConnecting = true

        runCatching {
            // OkHttpはWebSocket接続でもRequest URLにhttp/httpsスキームを要求し、
            // newWebSocket内でws/wssへアップグレードする。
            val url = BuildConfig.HEALTH_WEBSOCKET_URL.toOkHttpUrl()
                .newBuilder()
                .addQueryParameter("token", BuildConfig.HEALTH_DASHBOARD_TOKEN)
                .build()
            client.newWebSocket(
                Request.Builder().url(url).build(),
                listener,
            )
        }.onFailure {
            isConnecting = false
            scheduleReconnect()
            Log.e(TAG, "WebSocket URLが不正です: ${BuildConfig.HEALTH_WEBSOCKET_URL}", it)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@HealthDataWebSocketClient) {
                this@HealthDataWebSocketClient.webSocket = webSocket
                isConnecting = false
                nextConnectionAttemptAtMillis = 0L
                while (pendingMessages.isNotEmpty()) {
                    if (!webSocket.send(pendingMessages.first())) break
                    pendingMessages.removeFirst()
                }
            }
            Log.i(TAG, "Webダッシュボードへ接続しました")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { JSONObject(text) }
                .getOrNull()
                ?.takeIf { it.optString("type") == "error" }
                ?.let { Log.e(TAG, "Webサーバー拒否: ${it.optString("message")}") }
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
            }
        }
        Log.w(TAG, message)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        nextConnectionAttemptAtMillis = SystemClock.elapsedRealtime() + RECONNECT_DELAY_MILLIS
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, RECONNECT_DELAY_MILLIS)
    }

    private val reconnect = Runnable {
        synchronized(this) {
            if (pendingMessages.isNotEmpty()) connectIfNeeded()
        }
    }

    private fun loadOrCreateDeviceId(): String {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }

        return UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }
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
        private const val KEY_DEVICE_ID = "device_id"

        @Volatile
        private var instance: HealthDataWebSocketClient? = null

        fun getInstance(context: Context): HealthDataWebSocketClient =
            instance ?: synchronized(this) {
                instance ?: HealthDataWebSocketClient(context).also { instance = it }
            }
    }
}
