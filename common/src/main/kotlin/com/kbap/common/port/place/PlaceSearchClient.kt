package com.kbap.common.port.place

import java.math.BigDecimal

interface PlaceSearchClient {
    fun searchNearby(query: String, longitude: BigDecimal, latitude: BigDecimal): List<FoundPlace>

    fun searchPage(query: String, longitude: BigDecimal, latitude: BigDecimal, page: Int): PlaceSearchPage
}

data class PlaceSearchPage(
    val items: List<FoundPlace>,
    val hasNext: Boolean,
)

data class FoundPlace(
    val name: String,
    val address: String?,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
) {
    init {
        require(name.isNotBlank()) { "name 은 blank 일 수 없습니다" }
    }
}
