package com.kbap.api.home

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("kbap.home.marketing-popular")
data class HomeMarketingPopularProperties(
    val foodIds: List<Long> = emptyList(),
)
