package com.example.wearhealthdatahub.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.example.wearhealthdatahub.data.HealthDataRecord
import com.example.wearhealthdatahub.health.HealthServicesManager
import com.example.wearhealthdatahub.health.HealthServicesState
import com.example.wearhealthdatahub.presentation.theme.WearHealthDataHubTheme
import kotlinx.coroutines.launch

/** 権限要求、Health Servicesの初期化、Wear Compose画面をつなぐエントリーポイント。 */
class MainActivity : ComponentActivity() {
    private lateinit var healthServicesManager: HealthServicesManager

    // バックグラウンド健康権限は、対応するフォアグラウンド権限の許可後に別途要求する。
    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        initializeHealthServices()
    }

    private val foregroundPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        requestBackgroundPermissionOrInitialize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthServicesManager = HealthServicesManager(this)

        setContent {
            // StateFlowを監視し、受信した最新データでWear画面を自動更新する。
            val state by healthServicesManager.state.collectAsState()
            val records by healthServicesManager.records.collectAsState()
            HealthDataApp(
                state = state,
                records = records,
                onStartExercise = { exerciseType ->
                    lifecycleScope.launch {
                        healthServicesManager.startExercise(exerciseType)
                    }
                },
                onEndExercise = {
                    lifecycleScope.launch {
                        healthServicesManager.endExercise()
                    }
                },
            )
        }

        requestForegroundPermissions()
    }

    override fun onDestroy() {
        healthServicesManager.close()
        super.onDestroy()
    }

    private fun requestForegroundPermissions() {
        // 未許可の権限だけをまとめて要求し、既に許可された項目は再表示しない。
        val missing = buildList {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            addAll(healthPermissions())
        }.filterNot(::hasPermission)

        if (missing.isEmpty()) {
            requestBackgroundPermissionOrInitialize()
        } else {
            foregroundPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestBackgroundPermissionOrInitialize() {
        val permission = backgroundHealthPermission()
        // Androidではバックグラウンド権限を先に要求できないため、健康権限を再確認する。
        val hasAnyHealthPermission = healthPermissions().any(::hasPermission)
        if (permission != null && !hasPermission(permission) && hasAnyHealthPermission) {
            backgroundPermissionLauncher.launch(permission)
        } else {
            initializeHealthServices()
        }
    }

    private fun initializeHealthServices() {
        lifecycleScope.launch {
            healthServicesManager.initialize()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun healthPermissions(): List<String> =
        // Wear OS 6（API 36）からBODY_SENSORSは詳細な健康権限へ分割された。
        if (Build.VERSION.SDK_INT >= 36) {
            listOf(
                "android.permission.health.READ_HEART_RATE",
                "android.permission.health.READ_OXYGEN_SATURATION",
                "android.permission.health.READ_SKIN_TEMPERATURE",
                "android.permission.health.READ_RESPIRATORY_RATE",
                "android.permission.health.READ_HEART_RATE_VARIABILITY",
                "android.permission.health.READ_VO2_MAX",
            )
        } else {
            listOf(Manifest.permission.BODY_SENSORS)
        }

    private fun backgroundHealthPermission(): String? =
        // API 32以下ではバックグラウンド心拍用の追加実行時権限はない。
        when {
            Build.VERSION.SDK_INT >= 36 ->
                "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
            Build.VERSION.SDK_INT >= 33 ->
                Manifest.permission.BODY_SENSORS_BACKGROUND
            else -> null
        }
}

/** 対応運動種別の選択、運動開始・終了、最新データ一覧を表示するWear OS画面。 */
@Composable
private fun HealthDataApp(
    state: HealthServicesState,
    records: List<HealthDataRecord>,
    onStartExercise: (androidx.health.services.client.data.ExerciseType) -> Unit,
    onEndExercise: () -> Unit,
) {
    WearHealthDataHubTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            val transformationSpec = rememberTransformationSpec()
            // 種別ボタンを押すたび、端末が対応する運動種別を順番に切り替える。
            var selectedExerciseIndex by remember(state.exerciseTypes) { mutableIntStateOf(0) }
            val selectedExercise = state.exerciseTypes.getOrNull(selectedExerciseIndex)

            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    contentPadding = contentPadding,
                    state = listState,
                ) {
                    item {
                        ListHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text("Health Data Hub")
                        }
                    }
                    item {
                        StatusText(state)
                    }
                    if (state.activeExerciseType == null && selectedExercise != null) {
                        item {
                            Button(
                                onClick = {
                                    selectedExerciseIndex =
                                        (selectedExerciseIndex + 1) % state.exerciseTypes.size
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec),
                            ) {
                                Text("種別: $selectedExercise")
                            }
                        }
                        item {
                            Button(
                                onClick = { onStartExercise(selectedExercise) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec),
                            ) {
                                Text("運動を開始")
                            }
                        }
                    } else if (state.activeExerciseType != null) {
                        item {
                            Button(
                                onClick = onEndExercise,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec),
                            ) {
                                Text("運動を終了")
                            }
                        }
                    }
                    item {
                        ListHeader {
                            Text("最新データ (${records.size})")
                        }
                    }
                    if (records.isEmpty()) {
                        item {
                            Text("データ待機中")
                        }
                    } else {
                        // TransformingLazyColumnのitemsはインデックス指定なので最新値を順に参照する。
                        items(records.size) { index ->
                            HealthRecordText(records[index])
                        }
                    }
                }
            }
        }
    }
}

/** 初期化結果、登録データ型数、現在の運動状態を簡潔に表示する。 */
@Composable
private fun StatusText(state: HealthServicesState) {
    val text = buildString {
        if (state.isLoading) {
            append("初期化中…")
        } else {
            append("Measure ${state.measureTypes.size} / Passive ${state.passiveTypes.size}")
            append("\n運動: ${state.exerciseState}")
            state.message?.let {
                append("\n")
                append(it)
            }
        }
    }
    Text(text)
}

/** 1件の最新値を「データ型: 値 単位」と取得元・ポイント形式で表示する。 */
@Composable
private fun HealthRecordText(record: HealthDataRecord) {
    Text(
        text = buildString {
            append(record.dataType)
            append(": ")
            append(record.value)
            record.unit?.let { append(" $it") }
            append("\n")
            append(record.source.name.lowercase())
            append(" / ")
            append(record.pointType)
        },
    )
}

@WearPreviewDevices
@Composable
private fun DefaultPreview() {
    HealthDataApp(
        state = HealthServicesState(isLoading = false),
        records = emptyList(),
        onStartExercise = {},
        onEndExercise = {},
    )
}
