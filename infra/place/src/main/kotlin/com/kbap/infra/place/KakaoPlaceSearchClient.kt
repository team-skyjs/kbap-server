package com.kbap.infra.place

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchClient
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.math.BigDecimal

class KakaoPlaceSearchClient(
    private val restClient: RestClient,
    private val apiKey: String,
) : PlaceSearchClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override fun search(query: String, longitude: BigDecimal, latitude: BigDecimal): List<FoundPlace> {
        if (apiKey.isBlank()) {
            log.warn("카카오 REST 키가 설정돼 있지 않아 장소 검색을 수행할 수 없다")
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        }
        val body = try {
            restClient.get()
                .uri(SEARCH_URL) { builder ->
                    builder.queryParam("query", query)
                        .queryParam("x", longitude.toPlainString())
                        .queryParam("y", latitude.toPlainString())
                        .queryParam("sort", "distance")
                        .queryParam("size", TOP_LIMIT)
                        .build()
                }
                .header("Authorization", "KakaoAK $apiKey")
                .retrieve()
                .body(String::class.java)
        } catch (e: RestClientException) {
            log.warn("카카오 장소 검색 실패: query={}", query, e)
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        } ?: throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)

        val response = try {
            mapper.readValue(body, KakaoKeywordSearchResponse::class.java)
        } catch (e: JacksonException) {
            log.warn("카카오 장소 검색 응답 파싱 실패: query={}", query, e)
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        }

        return response.documents.mapNotNull { it.toFoundPlace() }
    }

    companion object {
        const val SEARCH_URL = "https://dapi.kakao.com/v2/local/search/keyword.json"
        const val TOP_LIMIT = 10
    }
}

private data class KakaoKeywordSearchResponse(
    val documents: List<KakaoPlaceDocument> = emptyList(),
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
