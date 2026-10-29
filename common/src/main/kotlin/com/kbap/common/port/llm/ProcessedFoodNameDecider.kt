package com.kbap.common.port.llm

fun interface ProcessedFoodNameDecider {
    fun decide(imageUrl: String, candidates: List<NameCandidate>): NameDecision
}

data class NameCandidate(val name: String, val maker: String?)

data class NameDecision(
    val choice: Int?,
    val confidence: Double,
    val readName: String?,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
)

class NameDecisionRefused : RuntimeException("모델이 판별을 거절했다(stop_reason=refusal)")

class NameDecisionImageUnavailable(cause: Throwable) : RuntimeException("모델이 사진 URL 을 가져오지 못했다", cause)
