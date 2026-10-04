package com.example.wearhealthdatahub.data

import org.json.JSONObject

/**
 * Health Servicesの異なるDataPoint形式を、画面表示・ログ・保存で共通利用できる形にしたレコード。
 *
 * 時刻はログや外部ツールで扱いやすいISO-8601文字列として保持する。
 */
data class HealthDataRecord(
    val source: HealthDataSource,
    val dataType: String,
    val pointType: String,
    val value: String,
    val unit: String?,
    val startTime: String?,
    val endTime: String,
    val receivedAt: String,
    val accuracy: String? = null,
) {
    // 同じ取得元・データ型の古い値を最新値で置き換えるための識別子。
    val key: String = "${source.name}:$dataType"

    /** Logcat出力とSharedPreferences保存に使用するJSONへ変換する。 */
    fun toJson(): JSONObject = JSONObject()
        .put("source", source.name)
        .put("dataType", dataType)
        .put("pointType", pointType)
        .put("value", value)
        .put("unit", unit)
        .put("startTime", startTime)
        .put("endTime", endTime)
        .put("receivedAt", receivedAt)
        .put("accuracy", accuracy)

    companion object {
        /** SharedPreferencesに保存したJSONから最新値を復元する。 */
        fun fromJson(json: JSONObject): HealthDataRecord = HealthDataRecord(
            source = HealthDataSource.valueOf(json.getString("source")),
            dataType = json.getString("dataType"),
            pointType = json.getString("pointType"),
            value = json.getString("value"),
            unit = json.optNullableString("unit"),
            startTime = json.optNullableString("startTime"),
            endTime = json.getString("endTime"),
            receivedAt = json.getString("receivedAt"),
            accuracy = json.optNullableString("accuracy"),
        )
    }
}

/** データがどのHealth Services APIから届いたかを表す。 */
enum class HealthDataSource {
    MEASURE,
    PASSIVE,
    EXERCISE,
}

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotEmpty)
