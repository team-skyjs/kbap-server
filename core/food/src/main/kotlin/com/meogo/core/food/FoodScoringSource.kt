package com.meogo.core.food

interface FoodScoringSource {
    fun nextChunk(page: Int, size: Int): List<Food>
}
