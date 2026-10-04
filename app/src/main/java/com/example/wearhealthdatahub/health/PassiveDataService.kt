package com.example.wearhealthdatahub.health

import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.HealthEvent
import androidx.health.services.client.data.UserActivityInfo
import com.example.wearhealthdatahub.data.HealthDataMapper
import com.example.wearhealthdatahub.data.HealthDataSource
import com.example.wearhealthdatahub.data.HealthDataStore

/**
 * アプリ画面が存在しないときも、Health ServicesからPassiveデータを受信するService。
 *
 * Health Services側からのみバインドできるよう、ManifestでPASSIVE_DATA_BINDING権限を指定する。
 */
class PassiveDataService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        // バッチ内の全DataPointを共通形式へ変換し、最新値として保存する。
        val records = HealthDataMapper.map(dataPoints, HealthDataSource.PASSIVE)
        HealthDataStore.getInstance(applicationContext).publish(records)
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        Log.d(TAG, "User activity: $info")
    }

    override fun onHealthEventReceived(event: HealthEvent) {
        Log.d(TAG, "Health event: $event")
    }

    private companion object {
        const val TAG = "PassiveHealthData"
    }
}
