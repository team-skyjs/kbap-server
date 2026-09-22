package com.kbap.api.order

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.order.model.OrderPlaceSnapshot
import com.kbap.common.domain.order.model.OrderPlaceSource
import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class OrderPlaceResolver(
    private val placeSearchClient: PlaceSearchClient,
    private val meter: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(latitude: BigDecimal, longitude: BigDecimal, lang: LanguageCode): OrderPlaceSnapshot? =
        try {
            val found = placeSearchClient.searchNearbyRestaurants(longitude, latitude, lang).firstOrNull()
            val snapshot = found?.let { snapshotOf(it, lang.code) }
            meter.counter("order.place.resolve", "result", if (snapshot != null) "hit" else "empty").increment()
            snapshot
        } catch (e: BusinessException) {
            if (e.errorCode != ErrorCode.PLACE_SEARCH_FAILED) throw e
            meter.counter("order.place.resolve", "result", "error").increment()
            log.warn("주문 식당 추정 실패 — {}", e.message)
            null
        }

    private fun snapshotOf(found: FoundPlace, language: String): OrderPlaceSnapshot? {
        val externalId = found.placeId?.takeIf { it.isNotBlank() } ?: return null
        return OrderPlaceSnapshot(
            source = OrderPlaceSource.GOOGLE_PLACE,
            externalId = externalId,
            name = found.name.take(OrderPlaceSnapshot.MAX_NAME_LENGTH),
            address = found.address?.take(OrderPlaceSnapshot.MAX_ADDRESS_LENGTH),
            language = language,
        )
    }
}
