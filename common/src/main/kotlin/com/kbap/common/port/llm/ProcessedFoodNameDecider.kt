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

data class NameDecisionUsage(val model: String, val inputTokens: Long, val outputTokens: Long)

open class NameDecisionFailure(message: String, val usage: NameDecisionUsage?, cause: Throwable? = null) : RuntimeException(message, cause)

class NameDecisionRefused(usage: NameDecisionUsage? = null) : NameDecisionFailure("모델이 판별을 거절했다(stop_reason=refusal)", usage)

class NameDecisionUnparsable(usage: NameDecisionUsage, cause: Throwable) : NameDecisionFailure("판별 응답을 해석하지 못했다", usage, cause)

class NameDecisionImageUnavailable(cause: Throwable) : RuntimeException("모델이 사진 URL 을 가져오지 못했다", cause)
