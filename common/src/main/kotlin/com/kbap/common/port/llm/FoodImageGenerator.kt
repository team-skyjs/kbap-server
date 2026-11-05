package com.kbap.common.port.llm

interface FoodImageGenerator {
    fun generate(prompt: String, quality: String): GeneratedFoodImage
}

class GeneratedFoodImage(
    val bytes: ByteArray,
    val inputTokens: Long,
    val outputTokens: Long,
)
