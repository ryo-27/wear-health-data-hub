package com.example.wearhealthdatahub.data

import android.content.Context
import android.util.Log
import com.example.wearhealthdatahub.network.HealthDataWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * 取得元とデータ型ごとの最新値を保持し、UI・Logcat・永続ストレージへ配信する。
 *
 * PassiveListenerServiceからアプリ画面がない状態でも呼ばれるため、プロセス内で共有する。
 */
class HealthDataStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val recordsByKey = loadRecords().associateByTo(linkedMapOf(), HealthDataRecord::key)
    private val mutableRecords = MutableStateFlow(recordsByKey.values.toList())
    private val webSocketClient = HealthDataWebSocketClient.getInstance(context)

    val records: StateFlow<List<HealthDataRecord>> = mutableRecords.asStateFlow()

    // 複数のHealth Servicesコールバックから同時に更新されてもMapと保存内容を壊さない。
    @Synchronized
    fun publish(records: List<HealthDataRecord>) {
        if (records.isEmpty()) return

        records.forEach { record ->
            recordsByKey[record.key] = record
            // 1レコード1行のJSONにすることで、Logcatから機械的に抽出しやすくする。
            Log.d(TAG, record.toJson().toString())
        }
        val latest = recordsByKey.values.sortedByDescending(HealthDataRecord::receivedAt)
        mutableRecords.value = latest
        webSocketClient.send(records)

        // 高頻度なMeasure更新ではディスクへ書かず、バックグラウンド受信時だけ最新値を保存する。
        if (records.any { it.source == HealthDataSource.PASSIVE }) {
            persist(latest.filter { it.source == HealthDataSource.PASSIVE })
        }
    }

    private fun loadRecords(): List<HealthDataRecord> = runCatching {
        val array = JSONArray(preferences.getString(KEY_RECORDS, "[]"))
        buildList {
            repeat(array.length()) { index ->
                add(HealthDataRecord.fromJson(array.getJSONObject(index)))
            }
        }
    }.onFailure {
        Log.w(TAG, "保存済み健康データを読み込めませんでした", it)
    }.getOrDefault(emptyList())

    private fun persist(records: List<HealthDataRecord>) {
        // 履歴全件ではなく、取得元・データ型ごとの最新値だけを保存する。
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    companion object {
        private const val TAG = "HealthData"
        private const val PREFERENCES_NAME = "health_data"
        private const val KEY_RECORDS = "latest_records"

        @Volatile
        private var instance: HealthDataStore? = null

        /** ActivityとServiceから同じ状態を参照するためのプロセス内シングルトン。 */
        fun getInstance(context: Context): HealthDataStore =
            instance ?: synchronized(this) {
                instance ?: HealthDataStore(context).also { instance = it }
            }
    }
}
