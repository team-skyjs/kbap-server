package com.kbap.batch.notification

import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object ScanSuggestionSendWindow {
    const val LUNCH_CRON = "0 0 12 * * *"
    const val DINNER_CRON = "0 0 18 * * *"

    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val OPEN: LocalTime = LocalTime.of(8, 0)
    private val CLOSE: LocalTime = LocalTime.of(21, 0)
    private val SLOT_STARTS: List<LocalTime> = listOf(LocalTime.of(12, 0), LocalTime.of(18, 0))

    fun isOpen(clock: Clock): Boolean {
        val now = LocalTime.now(clock.withZone(SEOUL))
        return !now.isBefore(OPEN) && now.isBefore(CLOSE)
    }

    fun startOfCurrentSlot(clock: Clock): LocalDateTime {
        val now = ZonedDateTime.now(clock.withZone(SEOUL))
        val today = SLOT_STARTS.lastOrNull { !now.toLocalTime().isBefore(it) }
        val slotStart = today?.let { now.with(it) } ?: now.minusDays(1).with(SLOT_STARTS.last())
        return slotStart.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    }
}
