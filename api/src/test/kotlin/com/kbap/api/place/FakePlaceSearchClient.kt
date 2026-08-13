package com.kbap.api.place

import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchClient
import com.kbap.common.port.place.PlaceSearchResult
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.math.BigDecimal

data class RecordedSearch(
    val query: String,
    val longitude: BigDecimal,
    val latitude: BigDecimal,
    val page: Int,
)

class FakePlaceSearchClient : PlaceSearchClient {
    val requests: MutableList<RecordedSearch> = mutableListOf()
    var result: PlaceSearchResult = PlaceSearchResult(items = emptyList(), hasNext = false)
    var failure: RuntimeException? = null

    override fun search(query: String, longitude: BigDecimal, latitude: BigDecimal, page: Int): PlaceSearchResult {
        requests.add(RecordedSearch(query, longitude, latitude, page))
        failure?.let { throw it }
        return result
    }

    fun reset() {
        requests.clear()
        result = PlaceSearchResult(items = emptyList(), hasNext = false)
        failure = null
    }

    fun returns(vararg places: FoundPlace, hasNext: Boolean = false) {
        result = PlaceSearchResult(items = places.toList(), hasNext = hasNext)
    }
}

@Configuration
class FakePlaceSearchConfig {
    @Bean
    @Primary
    fun fakePlaceSearchClient(): FakePlaceSearchClient = FakePlaceSearchClient()
}
