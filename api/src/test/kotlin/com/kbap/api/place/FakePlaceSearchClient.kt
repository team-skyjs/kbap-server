package com.kbap.api.place

import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.math.BigDecimal

data class RecordedSearch(
    val query: String?,
    val longitude: BigDecimal,
    val latitude: BigDecimal,
    val lang: LanguageCode,
)

class FakePlaceSearchClient : PlaceSearchClient {
    val requests: MutableList<RecordedSearch> = mutableListOf()
    var result: List<FoundPlace> = emptyList()
    var failure: RuntimeException? = null

    override fun searchNearbyRestaurants(
        longitude: BigDecimal,
        latitude: BigDecimal,
        lang: LanguageCode,
    ): List<FoundPlace> {
        requests.add(RecordedSearch(query = null, longitude = longitude, latitude = latitude, lang = lang))
        failure?.let { throw it }
        return result
    }

    override fun searchByKeyword(
        query: String,
        longitude: BigDecimal,
        latitude: BigDecimal,
        lang: LanguageCode,
    ): List<FoundPlace> {
        requests.add(RecordedSearch(query = query, longitude = longitude, latitude = latitude, lang = lang))
        failure?.let { throw it }
        return result
    }

    fun reset() {
        requests.clear()
        result = emptyList()
        failure = null
    }

    fun returns(vararg places: FoundPlace) {
        result = places.toList()
    }
}

@Configuration
class FakePlaceSearchConfig {
    @Bean
    @Primary
    fun fakePlaceSearchClient(): FakePlaceSearchClient = FakePlaceSearchClient()
}
