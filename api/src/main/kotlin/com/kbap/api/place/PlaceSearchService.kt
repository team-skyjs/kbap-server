package com.kbap.api.place

import com.kbap.common.port.place.PlaceSearchClient
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class PlaceSearchService(
    private val placeSearchClient: PlaceSearchClient,
) {
    fun searchPlaces(latitude: BigDecimal, longitude: BigDecimal, query: String?): PlaceSearchResponse {
        val keyword = query?.trim()?.takeIf { it.isNotEmpty() } ?: RESTAURANT_KEYWORD
        return PlaceSearchResponse.from(placeSearchClient.search(keyword, longitude, latitude))
    }

    companion object {
        const val RESTAURANT_KEYWORD = "음식점"
    }
}
