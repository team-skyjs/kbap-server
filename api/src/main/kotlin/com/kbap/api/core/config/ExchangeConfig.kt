package com.kbap.api.core.config

import com.kbap.api.infra.exchange.FrankfurterExchangeRateClient
import com.kbap.common.port.exchange.ExchangeRateClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ExchangeConfig {
    @Bean
    @ConditionalOnMissingBean(ExchangeRateClient::class)
    fun exchangeRateClient(
        @Value("\${kbap.exchange.base-url}") baseUrl: String,
    ): ExchangeRateClient = FrankfurterExchangeRateClient.create(baseUrl)
}
