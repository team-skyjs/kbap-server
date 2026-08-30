package com.kbap.common.domain.food.vector

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("kbap.vector")
data class FoodVectorProperties(
    val enabled: Boolean = false,
    val bucket: String = "",
    val index: String = "foods",
    val region: String = "ap-northeast-2",
)
