package com.kbap.api.place

import com.kbap.common.port.place.PlaceSearchClient
import org.springframework.stereotype.Service

@Service
class PlaceSearchService(
    private val placeSearchClient: PlaceSearchClient,
) {
    // 외부 검색 결과는 저장하지 않는 일시 데이터라 트랜잭션 경계가 없다.
    fun searchPlaces(query: String, page: Int): PlaceSearchResponse =
        PlaceSearchResponse.from(placeSearchClient.search(query.trim(), page))
}
