package com.example.wearhealthdatahub.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.PassiveListenerConfig
import com.example.wearhealthdatahub.data.HealthDataMapper
import com.example.wearhealthdatahub.data.HealthDataSource
import com.example.wearhealthdatahub.data.HealthDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Health Servicesの初期化状態と、画面表示に必要な対応情報をまとめたUI状態。 */
data class HealthServicesState(
    val isLoading: Boolean = true,
    val measureTypes: List<String> = emptyList(),
    val passiveTypes: List<String> = emptyList(),
    val exerciseTypes: List<ExerciseType> = emptyList(),
    val activeExerciseType: ExerciseType? = null,
    val exerciseState: String = "未開始",
    val message: String? = null,
)

/**
 * Measure・Passive・Exerciseの各クライアントをまとめて管理する。
 *
 * 固定のDataTypeを要求せず、端末のcapabilitiesと許可済み権限の両方を満たす型だけを登録する。
 */
class HealthServicesManager(context: Context) {
    private val appContext = context.applicationContext
    private val healthClient = HealthServices.getClient(appContext)
    private val measureClient = healthClient.measureClient
    private val passiveClient = healthClient.passiveMonitoringClient
    private val exerciseClient = healthClient.exerciseClient
    private val store = HealthDataStore.getInstance(appContext)
    private val registeredMeasureTypes = mutableSetOf<DeltaDataType<*, *>>()
    private val mutableState = MutableStateFlow(HealthServicesState())

    val state: StateFlow<HealthServicesState> = mutableState.asStateFlow()
    val records = store.records

    // MeasureClientは短時間・高頻度のスポット測定用。現行仕様では主に心拍が届く。
    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability,
        ) {
            Log.d(TAG, "Measure availability: ${dataType.name}=$availability")
        }

        override fun onDataReceived(data: DataPointContainer) {
            publish(data, HealthDataSource.MEASURE)
        }
    }

    // ExerciseClientからは運動状態、リアルタイム値、累積値、統計値が同じ更新で届く。
    private val exerciseCallback = object : ExerciseUpdateCallback {
        override fun onRegistered() {
            Log.d(TAG, "Exercise callback registered")
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            updateMessage("運動データのコールバック登録に失敗しました: ${throwable.message}")
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            publish(update.latestMetrics, HealthDataSource.EXERCISE)
            mutableState.value = mutableState.value.copy(
                exerciseState = update.exerciseStateInfo.state.toString(),
            )
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {
            publish(lapSummary.lapMetrics, HealthDataSource.EXERCISE)
        }

        override fun onAvailabilityChanged(
            dataType: DataType<*, *>,
            availability: Availability,
        ) {
            Log.d(TAG, "Exercise availability: ${dataType.name}=$availability")
        }
    }

    /**
     * 各クライアントの対応能力を取得し、現在の権限で利用可能な全データ型を登録する。
     */
    suspend fun initialize() {
        runCatching {
            // 運動再開時の状態も受け取れるよう、capabilities確認より先に登録する。
            exerciseClient.setUpdateCallback(exerciseCallback)

            val measureCapabilities = measureClient.getCapabilitiesAsync().await()
            val passiveCapabilities = passiveClient.getCapabilitiesAsync().await()
            val exerciseCapabilities = exerciseClient.getCapabilitiesAsync().await()

            val measurable = measureCapabilities.supportedDataTypesMeasure
                .filter(::hasPermissionFor)
                .toSet()
                .filterTo(mutableSetOf()) { dataType ->
                    // MeasureClientはDataType単位でコールバック登録が必要。
                    runCatching {
                        measureClient.registerMeasureCallback(dataType, measureCallback)
                        registeredMeasureTypes += dataType
                    }.onFailure {
                        Log.w(TAG, "Measure登録失敗: ${dataType.name}", it)
                    }.isSuccess
                }

            val supportedPassiveTypes = passiveCapabilities.supportedDataTypesPassiveMonitoring
                .filter(::hasPassivePermissionFor)
                .toSet()
            val passiveTypes = runCatching {
                if (supportedPassiveTypes.isNotEmpty()) {
                    // Service登録はアプリ終了後も維持され、更新は省電力のためバッチ配信される。
                    val configBuilder = PassiveListenerConfig.builder()
                        .setDataTypes(supportedPassiveTypes)
                    if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                        configBuilder.setShouldUserActivityInfoBeRequested(true)
                    }
                    passiveClient.setPassiveListenerServiceAsync(
                        PassiveDataService::class.java,
                        configBuilder.build(),
                    ).await()
                }
                supportedPassiveTypes
            }.onFailure {
                Log.e(TAG, "Passiveデータの登録に失敗しました", it)
            }.getOrDefault(emptySet())

            val passiveMessage = if (
                supportedPassiveTypes.isNotEmpty() && passiveTypes.isEmpty()
            ) {
                "Passiveデータの登録に失敗しました"
            } else {
                null
            }

            mutableState.value = HealthServicesState(
                isLoading = false,
                measureTypes = measurable.names(),
                passiveTypes = passiveTypes.names(),
                exerciseTypes = exerciseCapabilities.supportedExerciseTypes
                    .sortedBy(ExerciseType::toString),
                exerciseState = "未開始",
                message = passiveMessage,
            )
        }.onFailure {
            Log.e(TAG, "Health Servicesの初期化に失敗しました", it)
            mutableState.value = mutableState.value.copy(
                isLoading = false,
                message = "初期化に失敗しました: ${it.message}",
            )
        }
    }

    /** 選択した運動種別が端末で提供できる全データ型を使って運動記録を開始する。 */
    suspend fun startExercise(exerciseType: ExerciseType) {
        runCatching {
            val capabilities = exerciseClient.getCapabilitiesAsync().await()
                .getExerciseTypeCapabilities(exerciseType)
            val dataTypes = capabilities.supportedDataTypes
                .filter(::hasPermissionFor)
                .toSet()
            // LOCATIONを要求するExerciseConfigではGPSを有効にする必要がある。
            val gpsEnabled = DataType.LOCATION in dataTypes
            val config = ExerciseConfig.builder(exerciseType)
                .setDataTypes(dataTypes)
                .setIsGpsEnabled(gpsEnabled)
                .build()

            exerciseClient.startExerciseAsync(config).await()
            mutableState.value = mutableState.value.copy(
                activeExerciseType = exerciseType,
                exerciseState = ExerciseState.ACTIVE.toString(),
                message = "${dataTypes.size}種類の運動データを登録しました",
            )
        }.onFailure {
            Log.e(TAG, "運動を開始できませんでした", it)
            updateMessage("運動を開始できませんでした: ${it.message}")
        }
    }

    /** 現在このアプリが所有している運動セッションを終了する。 */
    suspend fun endExercise() {
        runCatching {
            exerciseClient.endExerciseAsync().await()
            mutableState.value = mutableState.value.copy(
                activeExerciseType = null,
                exerciseState = "終了",
                message = null,
            )
        }.onFailure {
            Log.e(TAG, "運動を終了できませんでした", it)
            updateMessage("運動を終了できませんでした: ${it.message}")
        }
    }

    /** Activity破棄時にフォアグラウンド向けコールバックだけを解除する。 */
    fun close() {
        registeredMeasureTypes.forEach { dataType ->
            measureClient.unregisterMeasureCallbackAsync(dataType, measureCallback)
        }
        registeredMeasureTypes.clear()
        exerciseClient.clearUpdateCallbackAsync(exerciseCallback)
    }

    private fun publish(container: DataPointContainer, source: HealthDataSource) {
        store.publish(HealthDataMapper.map(container, source))
    }

    private fun hasPermissionFor(dataType: DataType<*, *>): Boolean {
        val normalizedName = dataType.name.uppercase().filter(Char::isLetterOrDigit)
        // Health Servicesの公式権限区分に沿って、データ型ごとの実行時権限を確認する。
        healthPermissionFor(normalizedName)?.let { return hasPermission(it) }
        return when {
            "LOCATION" in normalizedName || "ABSOLUTEELEVATION" in normalizedName ->
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun hasPassivePermissionFor(dataType: DataType<*, *>): Boolean {
        if (!hasPermissionFor(dataType)) return false
        val normalizedName = dataType.name.uppercase().filter(Char::isLetterOrDigit)
        // バックグラウンド健康データは各読取権限に加えて専用権限が必要。
        return healthPermissionFor(normalizedName) == null || hasBackgroundHealthPermission()
    }

    private fun hasBackgroundHealthPermission(): Boolean =
        when {
            Build.VERSION.SDK_INT >= 36 ->
                hasPermission("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND")
            Build.VERSION.SDK_INT >= 33 ->
                hasPermission(Manifest.permission.BODY_SENSORS_BACKGROUND)
            else -> true
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun healthPermissionFor(normalizedDataTypeName: String): String? {
        if (Build.VERSION.SDK_INT < 36) {
            return if (normalizedDataTypeName.containsAny(HEALTH_DATA_TYPE_MARKERS)) {
                Manifest.permission.BODY_SENSORS
            } else {
                null
            }
        }

        return when {
            "OXYGENSATURATION" in normalizedDataTypeName -> READ_OXYGEN_SATURATION
            "SKINTEMPERATURE" in normalizedDataTypeName -> READ_SKIN_TEMPERATURE
            "RESPIRATORYRATE" in normalizedDataTypeName -> READ_RESPIRATORY_RATE
            "HEARTRATEVARIABILITY" in normalizedDataTypeName -> READ_HEART_RATE_VARIABILITY
            "VO2MAX" in normalizedDataTypeName -> READ_VO2_MAX
            "HEARTRATE" in normalizedDataTypeName -> READ_HEART_RATE
            else -> null
        }
    }

    private fun String.containsAny(markers: Set<String>): Boolean =
        markers.any { it in this }

    private fun Collection<DataType<*, *>>.names(): List<String> =
        map { it.name }.sorted()

    private fun updateMessage(message: String) {
        mutableState.value = mutableState.value.copy(message = message)
    }

    private companion object {
        const val TAG = "HealthServices"
        const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        const val READ_OXYGEN_SATURATION =
            "android.permission.health.READ_OXYGEN_SATURATION"
        const val READ_SKIN_TEMPERATURE =
            "android.permission.health.READ_SKIN_TEMPERATURE"
        const val READ_RESPIRATORY_RATE =
            "android.permission.health.READ_RESPIRATORY_RATE"
        const val READ_HEART_RATE_VARIABILITY =
            "android.permission.health.READ_HEART_RATE_VARIABILITY"
        const val READ_VO2_MAX = "android.permission.health.READ_VO2_MAX"
        val HEALTH_DATA_TYPE_MARKERS = setOf(
            "HEARTRATE",
            "OXYGENSATURATION",
            "SKINTEMPERATURE",
            "RESPIRATORYRATE",
            "VO2MAX",
        )
    }
}
