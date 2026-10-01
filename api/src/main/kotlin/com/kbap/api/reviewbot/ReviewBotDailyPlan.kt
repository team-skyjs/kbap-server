package com.kbap.api.reviewbot

import java.time.LocalDate
import kotlin.random.Random

class ReviewBotDailyPlan private constructor(
    val target: Int,
    private val hours: List<Int>,
) {
    fun dueUntil(hour: Int): Int = hours.count { it <= hour }

    companion object {
        fun of(date: LocalDate, minPerDay: Int, maxPerDay: Int, firstHour: Int, lastHour: Int): ReviewBotDailyPlan {
            val random = Random(date.toEpochDay())
            val target = random.nextInt(minPerDay, maxPerDay + 1)
            return ReviewBotDailyPlan(target, List(target) { random.nextInt(firstHour, lastHour + 1) })
        }
    }
}
