package com.example.wearhealthdatahub.data

import android.os.SystemClock
import androidx.health.services.client.data.CumulativeDataPoint
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.SampleDataPoint
import androidx.health.services.client.data.StatisticalDataPoint
import java.time.Instant

/**
 * Health Services固有のDataPointContainerを、アプリ共通のHealthDataRecordへ変換する。
 */
object HealthDataMapper {
    fun map(
        container: DataPointContainer,
        source: HealthDataSource,
    ): List<HealthDataRecord> {
        val receivedAt = Instant.now()
        // Sample/Intervalの時刻は端末起動後の経過時間なので、推定起動時刻を基準に実時刻へ直す。
        val bootInstant = receivedAt.minusMillis(SystemClock.elapsedRealtime())

        return buildList {
            // コンテナに含まれる4種類のDataPointを漏れなく同じレコード形式へ揃える。
            container.sampleDataPoints.forEach {
                add(it.toRecord(source, bootInstant, receivedAt))
            }
            container.intervalDataPoints.forEach {
                add(it.toRecord(source, bootInstant, receivedAt))
            }
            container.cumulativeDataPoints.forEach {
                add(it.toRecord(source, receivedAt))
            }
            container.statisticalDataPoints.forEach {
                add(it.toRecord(source, receivedAt))
            }
        }
    }

    private fun SampleDataPoint<*>.toRecord(
        source: HealthDataSource,
        bootInstant: Instant,
        receivedAt: Instant,
    ) = HealthDataRecord(
        source = source,
        dataType = dataType.name,
        pointType = "sample",
        value = value.toString(),
        unit = unitFor(dataType.name),
        startTime = null,
        endTime = bootInstant.plus(timeDurationFromBoot).toString(),
        receivedAt = receivedAt.toString(),
        accuracy = accuracy?.toString(),
    )

    private fun IntervalDataPoint<*>.toRecord(
        source: HealthDataSource,
        bootInstant: Instant,
        receivedAt: Instant,
    ) = HealthDataRecord(
        source = source,
        dataType = dataType.name,
        pointType = "interval",
        value = value.toString(),
        unit = unitFor(dataType.name),
        startTime = bootInstant.plus(startDurationFromBoot).toString(),
        endTime = bootInstant.plus(endDurationFromBoot).toString(),
        receivedAt = receivedAt.toString(),
        accuracy = accuracy?.toString(),
    )

    private fun CumulativeDataPoint<*>.toRecord(
        source: HealthDataSource,
        receivedAt: Instant,
    ) = HealthDataRecord(
        source = source,
        dataType = dataType.name,
        pointType = "cumulative",
        value = total.toString(),
        unit = unitFor(dataType.name),
        startTime = start.toString(),
        endTime = end.toString(),
        receivedAt = receivedAt.toString(),
    )

    private fun StatisticalDataPoint<*>.toRecord(
        source: HealthDataSource,
        receivedAt: Instant,
    ) = HealthDataRecord(
        source = source,
        dataType = dataType.name,
        pointType = "statistical",
        value = """{"min":$min,"max":$max,"average":$average}""",
        unit = unitFor(dataType.name),
        startTime = start.toString(),
        endTime = end.toString(),
        receivedAt = receivedAt.toString(),
    )

    // DataType名はHealth Services側の表記揺れを吸収してから単位を判定する。
    private fun unitFor(dataTypeName: String): String? {
        val name = dataTypeName.normalized()
        return when {
            "HEARTRATE" in name -> "bpm"
            "PACE" in name -> "ms/km"
            "SPEED" in name -> "m/s"
            "DISTANCE" in name || "ELEVATION" in name || "STRIDELENGTH" in name -> "m"
            "GROUNDCONTACTTIME" in name -> "ms"
            "DURATION" in name -> "s"
            "CALOR" in name -> "kcal"
            "STEPSPERMINUTE" in name -> "steps/min"
            "STEP" in name || "REP" in name || "STROKE" in name || "LAPCOUNT" in name -> "count"
            "FLOOR" in name -> "floors"
            "VERTICALRATIO" in name -> "%"
            "VERTICALOSCILLATION" in name -> "cm"
            "VO2MAX" in name -> "mL/kg/min"
            "LOCATION" in name -> "location"
            else -> null
        }
    }

    private fun String.normalized(): String =
        uppercase().filter(Char::isLetterOrDigit)
}
