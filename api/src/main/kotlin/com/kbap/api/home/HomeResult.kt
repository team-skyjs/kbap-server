package com.kbap.api.home

import com.kbap.api.food.FoodSummaryView
import com.kbap.api.food.RecentScannedFoodView
import com.kbap.api.ingredient.AvoidedIngredientView
import com.kbap.api.review.FoodRating
import com.kbap.api.review.TopRatedFoodRating

data class HomeResult(
    val avoidedSubstances: List<AvoidedIngredientView>,
    val popularFoods: List<FoodSummaryView>,
    val mostReviewedFoods: List<FoodSummaryView>,
    val recentScans: List<RecentScannedFoodView>,
    val bookmarkedFoodIds: Set<Long>,
    val ratings: Map<Long, FoodRating>,
    val topRatedFoods: List<TopRatedFoodView>? = null,
)

data class TopRatedFoodView(
    val summary: FoodSummaryView,
    val rating: TopRatedFoodRating,
)
