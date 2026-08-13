package com.kbap.api.place

import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchClient
import com.kbap.common.port.place.PlaceSearchPage
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.math.BigDecimal

data class RecordedSearch(
    val query: String,
    val longitude: BigDecimal,
    val latitude: BigDecimal,
    val page: Int?,
)

class FakePlaceSearchClient : PlaceSearchClient {
    val requests: MutableList<RecordedSearch> = mutableListOf()
    var result: List<FoundPlace> = emptyList()
    var hasNext: Boolean = false
    var failure: RuntimeException? = null

    override fun searchNearby(query: String, longitude: BigDecimal, latitude: BigDecimal): List<FoundPlace> {
        requests.add(RecordedSearch(query, longitude, latitude, page = null))
        failure?.let { throw it }
        return result
    }

    override fun searchPage(query: String, longitude: BigDecimal, latitude: BigDecimal, page: Int): PlaceSearchPage {
        requests.add(RecordedSearch(query, longitude, latitude, page = page))
        failure?.let { throw it }
        return PlaceSearchPage(items = result, hasNext = hasNext)
    }

    fun reset() {
        requests.clear()
        result = emptyList()
        hasNext = false
        failure = null
    }

    fun returns(vararg places: FoundPlace, hasNext: Boolean = false) {
        result = places.toList()
        this.hasNext = hasNext
    }
}

@Configuration
class FakePlaceSearchConfig {
    @Bean
    @Primary
    fun fakePlaceSearchClient(): FakePlaceSearchClient = FakePlaceSearchClient()
}
