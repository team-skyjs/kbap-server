package com.kbap.common.port.place

import com.kbap.common.domain.LanguageCode
import java.math.BigDecimal

interface PlaceSearchClient {
    fun searchNearbyRestaurants(longitude: BigDecimal, latitude: BigDecimal, lang: LanguageCode): List<FoundPlace>

    fun searchByKeyword(query: String, longitude: BigDecimal, latitude: BigDecimal, lang: LanguageCode): List<FoundPlace>
}

data class FoundPlace(
    val placeId: String?,
    val name: String,
    val address: String?,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
) {
    init {
        require(name.isNotBlank()) { "name 은 blank 일 수 없습니다" }
    }
}
