package com.kbap.api.scan

// 인터페이스는 테스트 대역용 — DocumentDB $search 는 로컬·Testcontainers 재현이 불가해 통합 테스트가 fake 를 주입한다.
fun interface SimilarFoodSearcher {
    fun search(embedding: FloatArray, limit: Int): List<SimilarFoodDocument>
}

data class SimilarFoodDocument(
    val foodId: Long,
    val score: Double,
)
