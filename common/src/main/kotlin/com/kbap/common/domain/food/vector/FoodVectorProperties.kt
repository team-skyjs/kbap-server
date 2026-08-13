package com.kbap.common.domain.food.vector

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("kbap.vector")
data class FoodVectorProperties(
    val enabled: Boolean = false,
    val uri: String = "",
    val database: String = "kbap",
    val collection: String = "foods",
)
