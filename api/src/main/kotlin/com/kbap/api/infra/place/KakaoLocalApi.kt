package com.kbap.api.infra.place

import com.fasterxml.jackson.annotation.JsonProperty
import com.kbap.common.port.place.FoundPlace
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import java.math.BigDecimal

internal interface KakaoLocalApi {
    @GetExchange("/v2/local/search/keyword.json")
    fun searchKeyword(
        @RequestParam query: String,
        @RequestParam x: String,
        @RequestParam y: String,
        @RequestParam sort: String,
        @RequestParam page: Int,
        @RequestParam size: Int,
    ): KakaoKeywordSearchResponse
}

internal data class KakaoKeywordSearchResponse(
    val documents: List<KakaoPlaceDocument> = emptyList(),
    val meta: KakaoMeta = KakaoMeta(),
)

internal data class KakaoMeta(
    @param:JsonProperty("is_end") val isEnd: Boolean = true,
)

internal data class KakaoPlaceDocument(
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
            latitude = y?.toBigDecimalOrNull(),
            longitude = x?.toBigDecimalOrNull(),
        )
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { BigDecimal(it) }.getOrNull()
    }
}
