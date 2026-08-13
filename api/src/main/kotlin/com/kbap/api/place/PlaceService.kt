package com.kbap.api.place

import com.kbap.common.port.place.PlaceSearchClient
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class PlaceService(
    private val placeSearchClient: PlaceSearchClient,
) {
    fun getNearbyPlaces(latitude: BigDecimal, longitude: BigDecimal): PlaceNearbyResponse =
        PlaceNearbyResponse.from(placeSearchClient.searchNearby(RESTAURANT_KEYWORD, longitude, latitude))

    fun searchPlacePage(query: String, latitude: BigDecimal, longitude: BigDecimal, page: Int): PlaceSearchPageResponse =
        PlaceSearchPageResponse.from(placeSearchClient.searchPage(query.trim(), longitude, latitude, page))

    companion object {
        const val RESTAURANT_KEYWORD = "음식점"
    }
}
