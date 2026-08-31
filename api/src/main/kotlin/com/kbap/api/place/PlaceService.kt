package com.kbap.api.place

import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.place.PlaceSearchClient
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class PlaceService(
    private val placeSearchClient: PlaceSearchClient,
) {
    fun getNearbyPlaces(latitude: BigDecimal, longitude: BigDecimal, lang: LanguageCode): PlaceNearbyResponse =
        PlaceNearbyResponse.from(placeSearchClient.searchNearbyRestaurants(longitude, latitude, lang))

    fun searchPlaces(query: String, latitude: BigDecimal, longitude: BigDecimal, lang: LanguageCode): PlaceSearchListResponse =
        PlaceSearchListResponse.from(placeSearchClient.searchByKeyword(query.trim(), longitude, latitude, lang))
}
