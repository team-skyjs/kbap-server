package com.kbap.infra.place

import com.fasterxml.jackson.annotation.JsonProperty
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchClient
import com.kbap.common.port.place.PlaceSearchResult
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.math.BigDecimal

class KakaoPlaceSearchClient(
    private val restClient: RestClient,
    private val apiKey: String,
) : PlaceSearchClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun search(query: String, longitude: BigDecimal, latitude: BigDecimal, page: Int): PlaceSearchResult {
        if (apiKey.isBlank()) {
            log.warn("카카오 REST 키가 설정돼 있지 않아 장소 검색을 수행할 수 없다")
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        }
        val response = try {
            restClient.get()
                .uri(SEARCH_URL) { builder ->
                    builder.queryParam("query", query)
                        .queryParam("x", longitude.toPlainString())
                        .queryParam("y", latitude.toPlainString())
                        .queryParam("sort", "distance")
                        .queryParam("page", page)
                        .queryParam("size", PAGE_SIZE)
                        .build()
                }
                .header("Authorization", "KakaoAK $apiKey")
                .retrieve()
                .body(KakaoKeywordSearchResponse::class.java)
        } catch (e: RestClientException) {
            log.warn("카카오 장소 검색 실패: query={} x={} y={} page={}", query, longitude, latitude, page, e)
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        } ?: throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)

        return PlaceSearchResult(
            items = response.documents.mapNotNull { it.toFoundPlace() },
            hasNext = !response.meta.isEnd,
        )
    }

    companion object {
        const val SEARCH_URL = "https://dapi.kakao.com/v2/local/search/keyword.json"
        const val PAGE_SIZE = 15
    }
}

private data class KakaoKeywordSearchResponse(
    val documents: List<KakaoPlaceDocument> = emptyList(),
    val meta: KakaoMeta = KakaoMeta(),
)

private data class KakaoMeta(
    @param:JsonProperty("is_end") val isEnd: Boolean = true,
)

private data class KakaoPlaceDocument(
    val id: String? = null,
    @param:JsonProperty("place_name") val placeName: String? = null,
    @param:JsonProperty("address_name") val addressName: String? = null,
    @param:JsonProperty("road_address_name") val roadAddressName: String? = null,
    val x: String? = null,
    val y: String? = null,
) {
    fun toFoundPlace(): FoundPlace? {
        val name = placeName?.takeIf { it.isNotBlank() } ?: return null
        return FoundPlace(
            name = name,
            address = roadAddressName?.takeIf { it.isNotBlank() } ?: addressName?.takeIf { it.isNotBlank() },
            kakaoPlaceId = id?.takeIf { it.isNotBlank() },
            latitude = y?.toBigDecimalOrNull(),
            longitude = x?.toBigDecimalOrNull(),
        )
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { BigDecimal(it) }.getOrNull()
    }
}
