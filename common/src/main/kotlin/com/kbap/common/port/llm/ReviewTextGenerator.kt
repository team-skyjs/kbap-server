package com.kbap.common.port.llm

interface ReviewTextGenerator {
    fun generate(request: ReviewDraftRequest): String

    fun generateProcessedFood(request: ProcessedFoodReviewDraftRequest): ProcessedFoodReviewDraft
}

data class ReviewDraftRequest(
    val foodName: String,
    val description: String,
    val ingredients: List<String>,
    val language: String,
    val rating: Int,
    val maxCharacters: Int = 80,
    val style: String = "",
    val sentences: Int = 2,
    val avoidSimilarTo: String? = null,
)

data class ProcessedFoodReviewDraftRequest(
    val productName: String,
    val foodType: String?,
    val ingredients: List<String>,
    val language: String,
    val rating: Int,
    val maxCharacters: Int = 80,
    val style: String = "",
    val sentences: Int = 2,
    val avoidSimilarTo: String? = null,
)

data class ProcessedFoodReviewDraft(val text: String, val spiciness: Int?, val sweetness: Int?)
