package com.kbap.api.home

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalDateTime

@ConfigurationProperties("kbap.home.marketing-popular")
data class HomeMarketingPopularProperties(
    val startsAt: LocalDateTime? = null,
    val endsAt: LocalDateTime? = null,
    val foodIds: List<Long> = emptyList(),
) {
    fun activeFoodIds(now: LocalDateTime): List<Long> {
        if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt)) return emptyList()
        if (now.isBefore(startsAt) || !now.isBefore(endsAt)) return emptyList()
        return foodIds.distinct()
    }
}
