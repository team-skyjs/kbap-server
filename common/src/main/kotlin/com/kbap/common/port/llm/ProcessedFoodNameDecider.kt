package com.kbap.common.port.llm

fun interface ProcessedFoodNameDecider {
    fun decide(imageUrl: String, candidates: List<NameCandidate>): NameDecision
}

data class NameCandidate(val name: String, val maker: String?)

data class NameDecision(
    val choiceIndex: Int?,
    val confidence: Double,
    val readName: String?,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
)

class NameDecisionRefused : RuntimeException("모델이 판별을 거절했다(stop_reason=refusal)")
