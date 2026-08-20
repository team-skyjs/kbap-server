package com.kbap.api.core.config

import com.kbap.common.port.place.PlaceSearchClient
import com.kbap.common.port.place.ReverseGeocoder
import com.kbap.api.infra.place.GoogleGeocoder
import com.kbap.api.infra.place.GooglePlaceSearchClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PlaceConfig {
    @Bean
    @ConditionalOnMissingBean(PlaceSearchClient::class)
    fun placeSearchClient(
        @Value("\${kbap.google.places-api-key:}") placesApiKey: String,
    ): PlaceSearchClient = GooglePlaceSearchClient.create(placesApiKey)

    @Bean
    @ConditionalOnMissingBean(ReverseGeocoder::class)
    fun reverseGeocoder(
        @Value("\${kbap.google.places-api-key:}") placesApiKey: String,
    ): ReverseGeocoder = GoogleGeocoder.create(placesApiKey)
}
