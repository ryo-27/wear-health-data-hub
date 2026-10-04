import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.inputStream()
        ?.use(::load)
}

fun configuredValue(environmentName: String, propertyName: String, defaultValue: String): String =
    System.getenv(environmentName)
        ?: localProperties.getProperty(propertyName)
        ?: defaultValue

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.example.wearhealthdatahub"
    // Wear OS 6の詳細な健康権限を利用するためAPI 36でコンパイルする。
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.wearhealthdatahub"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "HEALTH_WEBSOCKET_URL",
            configuredValue(
                "HEALTH_WEBSOCKET_URL",
                "health.websocket.url",
                "ws://10.0.2.2:8080/ingest",
            ).asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "HEALTH_DASHBOARD_TOKEN",
            configuredValue(
                "HEALTH_DASHBOARD_TOKEN",
                "health.dashboard.token",
                "development-token",
            ).asBuildConfigString(),
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        // Health Servicesとjava.time APIをKotlinから利用するためJava 11へ統一する。
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Wear端末が提供する任意の共有ライブラリ。存在しない端末でもアプリは起動できる。
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Wear Composeによる円形画面向けUI。
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation("androidx.fragment:fragment:1.9.0")
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.play.services.wearable)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.wear.tooling.preview)

    // Wear OSのセンサー統合API（Measure / Passive / Exercise）。
    implementation("androidx.health:health-services-client:1.1.0-rc02")

    // Health ServicesのListenableFutureをCoroutineからawaitするための依存関係。
    implementation("com.google.guava:guava:31.1-android")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.1.0")
    // 5.5.0以降はcompileSdk 37が必要なため、Wear OS 6（36.1）互換版を使用する。
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // Compose UIテストとPreview用ツール。
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}