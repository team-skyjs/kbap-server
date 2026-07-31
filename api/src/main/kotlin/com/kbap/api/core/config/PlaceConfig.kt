package com.kbap.api.core.config

import com.kbap.common.port.place.PlaceSearchClient
import com.kbap.infra.place.KakaoPlaceSearchClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

// 검색 제공자 교체 지점 — 컨트롤러는 PlaceSearchClient 만 알고, 구현 선택은 여기서만 한다.
// 키가 없어도 빈은 조립한다(부팅 유지) — 실제 호출만 PLACE-001 로 실패한다.
@Configuration
class PlaceConfig {
    @Bean
    @ConditionalOnMissingBean(PlaceSearchClient::class)
    fun placeSearchClient(
        @Value("\${kbap.kakao.rest-api-key:}") restApiKey: String,
    ): PlaceSearchClient = KakaoPlaceSearchClient(RestClient.create(), restApiKey)
}
