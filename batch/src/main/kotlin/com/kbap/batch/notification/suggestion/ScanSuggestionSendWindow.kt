package com.kbap.batch.notification

import com.kbap.common.domain.notification.model.MealSlot
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

object ScanSuggestionSendWindow {
    const val DAILY_AT_11_00 = "0 0 11 * * *"
    const val DAILY_AT_17_00 = "0 0 17 * * *"

    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

    fun startOfCurrentSlot(clock: Clock): LocalDateTime {
        val now = ZonedDateTime.now(clock.withZone(SEOUL))
        val today = MealSlot.entries.lastOrNull { !now.toLocalTime().isBefore(it.startTime) }
        val slotStart = today?.let { now.with(it.startTime) } ?: now.minusDays(1).with(MealSlot.entries.last().startTime)
        return slotStart.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    }
}
