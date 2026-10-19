package com.kbap.api.reviewbot

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class ReviewBotPriorityList {
    val koreanNames: List<String> = ClassPathResource(RESOURCE).inputStream.bufferedReader(Charsets.UTF_8)
        .readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    companion object {
        const val RESOURCE = "review-bot-priority.txt"
        const val MAX_BOT_REVIEWS = 3
    }
}
