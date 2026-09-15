package com.kbap.api.review

data class ReviewLiked(
    val reviewId: Long,
    val authorMemberId: Long,
    val foodId: Long,
)
