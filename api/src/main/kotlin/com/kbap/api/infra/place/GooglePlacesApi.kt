package com.kbap.api.infra.place

import com.kbap.common.port.place.FoundPlace
import org.springframework.web.service.annotation.PostExchange
import java.math.BigDecimal

internal interface GooglePlacesApi {
    @PostExchange("/v1/places:searchNearby")
    fun searchNearby(@org.springframework.web.bind.annotation.RequestBody request: GoogleNearbySearchRequest): GooglePlacesResponse

    @PostExchange("/v1/places:searchText")
    fun searchText(@org.springframework.web.bind.annotation.RequestBody request: GoogleTextSearchRequest): GooglePlacesResponse
}

internal data class GoogleNearbySearchRequest(
    val includedTypes: List<String>,
    val maxResultCount: Int,
    val rankPreference: String,
    val languageCode: String,
    val locationRestriction: GoogleLocationCircle,
)

internal data class GoogleTextSearchRequest(
    val textQuery: String,
    val pageSize: Int,
    val languageCode: String,
    val locationBias: GoogleLocationCircle,
)

internal data class GoogleLocationCircle(val circle: GoogleCircle)

internal data class GoogleCircle(val center: GoogleLatLng, val radius: Double)

internal data class GoogleLatLng(val latitude: Double, val longitude: Double)

internal data class GooglePlacesResponse(
    val places: List<GooglePlace> = emptyList(),
)

internal data class GooglePlace(
    val displayName: GoogleLocalizedText? = null,
    val formattedAddress: String? = null,
    val location: GoogleCoordinates? = null,
) {
    fun toFoundPlace(): FoundPlace? {
        val name = displayName?.text?.takeIf { it.isNotBlank() } ?: return null
        return FoundPlace(
            name = name,
            address = formattedAddress?.takeIf { it.isNotBlank() },
            latitude = location?.latitude?.let(BigDecimal::valueOf),
            longitude = location?.longitude?.let(BigDecimal::valueOf),
        )
    }
}

internal data class GoogleLocalizedText(
    val text: String? = null,
    val languageCode: String? = null,
)

internal data class GoogleCoordinates(
    val latitude: Double? = null,
    val longitude: Double? = null,
)
