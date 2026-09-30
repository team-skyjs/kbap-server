package com.kbap.common.port.llm

fun interface ReviewTextGenerator {
    fun generate(request: ReviewDraftRequest): String
}

data class ReviewDraftRequest(
    val foodName: String,
    val description: String,
    val ingredients: List<String>,
    val language: String,
    val rating: Int,
)
