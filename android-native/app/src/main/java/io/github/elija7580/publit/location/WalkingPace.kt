package io.github.elija7580.publit.location

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.elija7580.publit.data.SmartRouting
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Your usual walking speed for smart routing (WalkingPace on iOS): from Health Connect (the phone's step
 * counter and fitness apps). Measured speed samples of the last 30 days when there are any, else steps and
 * distance of the last seven days. Read on the device only; only the speed is kept.
 */
object WalkingPace {
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
    )

    fun available(ctx: Context) = runCatching { HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    suspend fun granted(ctx: Context): Boolean = runCatching {
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
            .containsAll(permissions - HealthPermission.getReadPermission(SpeedRecord::class))
    }.getOrDefault(false)

    /** Measures and stores the speed in m/s; null without enough walking or without access. */
    suspend fun measure(ctx: Context): Double? {
        if (!available(ctx) || !granted(ctx)) return null
        val client = HealthConnectClient.getOrCreate(ctx)
        val end = Instant.now()
        val speed = runCatching {
            // Walking speed samples: only the ones at walking pace (0.6–2.2 m/s), averaged.
            val recs = client.readRecords(ReadRecordsRequest(SpeedRecord::class, TimeRangeFilter.between(end.minus(30, ChronoUnit.DAYS), end))).records
            recs.flatMap { it.samples }.map { it.speed.inMetersPerSecond }.filter { it in 0.6..2.2 }.takeIf { it.size >= 20 }?.average()
        }.getOrNull() ?: runCatching {
            val r = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL), TimeRangeFilter.between(end.minus(7, ChronoUnit.DAYS), end)))
            val steps = r[StepsRecord.COUNT_TOTAL] ?: 0L
            val meters = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
            // Step length at a usual walking cadence (about 1.8 steps a second), as on iOS without a measured pace.
            if (steps > 2_000 && meters > 0) meters / steps * 1.8 else null
        }.getOrNull()
        val v = speed?.takeIf { it in 0.6..2.2 } ?: return null
        SmartRouting.prefs?.edit()?.putFloat(SmartRouting.MEASURED_SPEED, v.toFloat())?.apply()
        return v
    }
}

/** Health Connect asks apps to explain why they read health data; Publit shows its privacy policy. */
class HealthRationaleActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://elija-7580.github.io/publit-site/privacy/"))) }
        finish()
    }
}
