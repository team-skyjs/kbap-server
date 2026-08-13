package com.kbap.common.port.place

import java.math.BigDecimal

fun interface PlaceSearchClient {
    fun search(query: String, longitude: BigDecimal, latitude: BigDecimal): List<FoundPlace>
}

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
