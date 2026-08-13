package com.kbap.api.place

import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.math.BigDecimal

data class RecordedSearch(
    val query: String,
    val longitude: BigDecimal,
    val latitude: BigDecimal,
)

class FakePlaceSearchClient : PlaceSearchClient {
    val requests: MutableList<RecordedSearch> = mutableListOf()
    var result: List<FoundPlace> = emptyList()
    var failure: RuntimeException? = null

    override fun search(query: String, longitude: BigDecimal, latitude: BigDecimal): List<FoundPlace> {
        requests.add(RecordedSearch(query, longitude, latitude))
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
