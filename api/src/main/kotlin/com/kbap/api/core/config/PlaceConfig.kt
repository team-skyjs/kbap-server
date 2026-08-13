package com.kbap.api.core.config

import com.kbap.common.port.place.PlaceSearchClient
import com.kbap.infra.place.KakaoPlaceSearchClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class PlaceConfig {
    @Bean
    @ConditionalOnMissingBean(PlaceSearchClient::class)
    fun placeSearchClient(
        @Value("\${kbap.kakao.rest-api-key:}") restApiKey: String,
    ): PlaceSearchClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(5)) }
        return KakaoPlaceSearchClient(RestClient.builder().requestFactory(requestFactory).build(), restApiKey)
    }
}
