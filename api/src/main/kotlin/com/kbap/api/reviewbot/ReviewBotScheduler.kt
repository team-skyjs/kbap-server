package com.kbap.api.reviewbot

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.ZonedDateTime

@Component
@ConditionalOnProperty(prefix = "kbap.review-bot", name = ["enabled"], havingValue = "true")
class ReviewBotScheduler(
    private val writer: ReviewBotWriter,
) {
    @Scheduled(cron = "0 0 9-22 * * *", zone = "Asia/Seoul")
    fun tick() {
        writer.writeDue(ZonedDateTime.now(ZoneId.of("Asia/Seoul")))
    }
}
