package com.kbap.batch.notification

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object ScanSuggestionSendWindow {
    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val OPEN: LocalTime = LocalTime.of(8, 0)
    private val CLOSE: LocalTime = LocalTime.of(21, 0)

    fun isOpen(clock: Clock): Boolean {
        val now = LocalTime.now(clock.withZone(SEOUL))
        return !now.isBefore(OPEN) && now.isBefore(CLOSE)
    }

    fun startOfToday(clock: Clock): LocalDateTime =
        LocalDate.now(clock.withZone(SEOUL))
            .atStartOfDay(SEOUL)
            .withZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime()
}
