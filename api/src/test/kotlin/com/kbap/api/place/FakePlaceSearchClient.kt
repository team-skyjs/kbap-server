package com.kbap.api.place

import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchClient
import com.kbap.common.port.place.PlaceSearchResult
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

class FakePlaceSearchClient : PlaceSearchClient {
    val requests: MutableList<Pair<String, Int>> = mutableListOf()
    var result: PlaceSearchResult = PlaceSearchResult(items = emptyList(), hasNext = false)
    var failure: RuntimeException? = null

    override fun search(query: String, page: Int): PlaceSearchResult {
        requests.add(query to page)
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
