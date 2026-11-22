package life.furrer.nine.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object DateFmt {
    private const val MILLIS_PER_MINUTE = 60_000L

    private val DAY_HEADER_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    /** DTSTART/Instances.BEGIN for an all-day event is midnight UTC with EVENT_TIMEZONE="UTC" —
     *  the single most common calendar-client bug. Converting through the system default zone can
     *  land on the wrong calendar date — especially near a DST transition, where the local zone's
     *  UTC offset for "midnight UTC" can fall on the previous local day — so this always reads the
     *  date components directly off the UTC-anchored instant instead. Never run an all-day value
     *  through [formatTime] or any other local-zone-aware path. */
    fun allDayToLocalDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()

    /** Every Nine app renders 24-hour time, always — not configurable. [zone] is the device's own
     *  unless a caller that already works in an explicit zone (to stay testable) passes it, so the
     *  time it shows agrees with the day that caller computed in that same zone. */
    fun formatTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(TIME_FORMAT)

    /** e.g. "45 min", "3h", "3h 30m" — an event's duration in its selected-row metadata line. */
    fun formatDuration(durationMillis: Long): String {
        val totalMinutes = (durationMillis / MILLIS_PER_MINUTE).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0 -> "$minutes min"
            minutes == 0 -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    /** e.g. "— 2h 15m —" — the separator shown between events more than 90 minutes apart, so
     *  free time is legible without drawing an hour grid. */
    fun formatGapLabel(durationMillis: Long): String = "— ${formatDuration(durationMillis)} —"

    /** The day-group header every time-ordered list in the suite writes above a day's first row —
     *  "TODAY" / "YESTERDAY" / "MON 4 Aug". Lived as a private function in P9's [CallLogRows]
     *  until M9's message threads wanted the identical wording; moved here on that second
     *  consumer rather than duplicated, the same "extract on demand, not speculatively" call
     *  `docs/core-extraction-plan.md` asks for.
     *  Takes [today] rather than reading the clock so callers stay testable — every existing
     *  caller already threads a `today` through for exactly that reason. */
    fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()} ${day.format(DAY_HEADER_FORMAT)}"
    }
}
