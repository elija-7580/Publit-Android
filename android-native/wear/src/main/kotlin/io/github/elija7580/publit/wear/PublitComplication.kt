// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Instant

/**
 * Publit on the watch face (the Apple Watch complications of iOS Publit#18): during a trip started on
 * the phone it counts down to the next step (leave, board, get off) with the line; otherwise it opens
 * Publit's departures nearby with one tap.
 */
class PublitComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(this, type, null, preview = true)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(this, request.complicationType, LiveTripStore.trip(this).value, preview = false)

    companion object {
        /** Asks the watch face for fresh data, e.g. when a trip starts, changes or ends. */
        fun refresh(ctx: Context) = runCatching {
            ComplicationDataSourceUpdateRequester.create(ctx, ComponentName(ctx, PublitComplicationService::class.java)).requestUpdateAll()
        }

        private fun text(s: String): ComplicationText = PlainComplicationText.Builder(s).build()

        fun build(ctx: Context, type: ComplicationType, trip: LiveTrip?, preview: Boolean): ComplicationData? {
            val icon = MonochromaticImage.Builder(Icon.createWithResource(ctx, R.drawable.ic_notification)).build()
            val live = trip?.takeIf { it.next > System.currentTimeMillis() }
            val tap = PendingIntent.getActivity(ctx, 3,
                Intent(ctx, if (live != null) LiveTripActivity::class.java else WearActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val countdown: ComplicationText? = when {
                live != null -> TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                    CountDownTimeReference(Instant.ofEpochMilli(live.next))).build()
                preview -> text("4 min")
                else -> null
            }
            val line = live?.line ?: if (preview) "U6" else null
            val cue = live?.let { LiveTripStore.cueLabel(ctx, it.cue) }
            val description = text(live?.headline ?: ctx.getString(R.string.nearby))
            return when (type) {
                ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(countdown ?: text("Publit"), description)
                    .setMonochromaticImage(icon).setTitle(line?.let(::text)).setTapAction(tap).build()
                ComplicationType.LONG_TEXT -> {
                    val title = when {
                        live != null -> listOfNotNull(cue?.takeIf { it.isNotEmpty() }, live.line).joinToString(" · ").ifEmpty { live.headline }
                        preview -> "Departs in · U6"
                        else -> ctx.getString(R.string.nearby)
                    }
                    LongTextComplicationData.Builder(countdown ?: text(ctx.getString(R.string.nearby)), description)
                        .setTitle(text(if (live != null || preview) title else "Publit")).setMonochromaticImage(icon).setTapAction(tap).build()
                }
                else -> null
            }
        }
    }
}
