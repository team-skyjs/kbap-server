package com.kbap.batch.notification.discovery

import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object FoodDiscoverySendWindow {
    const val AT_09_12_15_18 = "0 0 9,12,15,18 * * *"

    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val SLOTS = listOf(9, 12, 15, 18).map { LocalTime.of(it, 0) }
    private val OPEN_FROM: LocalTime = LocalTime.of(9, 0)
    private val OPEN_UNTIL: LocalTime = LocalTime.of(21, 0)

    fun startOfCurrentSlot(clock: Clock): LocalDateTime =
        currentSlotStart(clock).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

    fun slotTag(clock: Clock): String = "%02d".format(currentSlotStart(clock).hour)

    fun isOpen(clock: Clock): Boolean {
        val time = ZonedDateTime.now(clock.withZone(SEOUL)).toLocalTime()
        return !time.isBefore(OPEN_FROM) && time.isBefore(OPEN_UNTIL)
    }

    private fun currentSlotStart(clock: Clock): ZonedDateTime {
        val now = ZonedDateTime.now(clock.withZone(SEOUL))
        val today = SLOTS.lastOrNull { !now.toLocalTime().isBefore(it) }
        return today?.let { now.with(it) } ?: now.minusDays(1).with(SLOTS.last())
    }
}
