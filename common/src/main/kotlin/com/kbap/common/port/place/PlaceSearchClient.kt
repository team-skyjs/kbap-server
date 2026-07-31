package com.kbap.common.port.place

import java.math.BigDecimal

// 구현이 외부 지도 검색(카카오 등)을 호출한다. 제공자를 바꿔도 이 계약은 그대로다.
fun interface PlaceSearchClient {
    fun search(query: String, page: Int): PlaceSearchResult
}

data class PlaceSearchResult(
    val items: List<FoundPlace>,
    val hasNext: Boolean,
)

data class FoundPlace(
    val name: String,
    val address: String?,
    val kakaoPlaceId: String?,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
) {
    init {
        require(name.isNotBlank()) { "name 은 blank 일 수 없습니다" }
    }
}
