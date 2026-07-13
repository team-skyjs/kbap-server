package com.meogo.domain.food

interface FoodScoringSource {
    fun nextChunk(page: Int, size: Int): List<Food>
}
