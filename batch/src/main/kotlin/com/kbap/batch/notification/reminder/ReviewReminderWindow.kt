package com.kbap.batch.notification.reminder

import com.kbap.batch.notification.nowInJvmZone
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

object ReviewReminderWindow {
    val MIN_AGE: Duration = Duration.ofHours(1)
    val MAX_AGE: Duration = Duration.ofHours(25)

    fun of(clock: Clock): ClosedRange<LocalDateTime> {
        val now = clock.nowInJvmZone()
        return now.minus(MAX_AGE)..now.minus(MIN_AGE)
    }
}
