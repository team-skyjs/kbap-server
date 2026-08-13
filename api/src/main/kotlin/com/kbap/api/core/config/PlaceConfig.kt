package com.kbap.api.core.config

import com.kbap.common.port.place.PlaceSearchClient
import com.kbap.infra.place.KakaoPlaceSearchClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PlaceConfig {
    @Bean
    @ConditionalOnMissingBean(PlaceSearchClient::class)
    fun placeSearchClient(
        @Value("\${kbap.kakao.rest-api-key:}") restApiKey: String,
    ): PlaceSearchClient = KakaoPlaceSearchClient.create(restApiKey)
}
