package com.kbap.api.review

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ProcessedFoodReviewSwitch(
    @Value("\${kbap.review.processed-food-write-enabled:false}") var writeEnabled: Boolean,
)
