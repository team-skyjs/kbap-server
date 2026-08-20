package com.kbap.api.scan

import com.kbap.common.domain.CurrencyCode
import com.kbap.common.port.exchange.ExchangeRateClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

class FakeExchangeRateClient : ExchangeRateClient {
    private val rates = mutableMapOf(
        CurrencyCode.KRW to BigDecimal("1.0000"),
        CurrencyCode.USD to BigDecimal("1400.5000"),
        CurrencyCode.JPY to BigDecimal("9.9999"),
    )
    var failAll: Boolean = false

    fun program(currency: CurrencyCode, krwPerUnit: BigDecimal) {
        rates[currency] = krwPerUnit
    }

    override fun getKrwPerUnitOrNull(currency: CurrencyCode): BigDecimal? {
        if (failAll) return null
        return rates[currency]
    }
}

@Configuration
class FakeExchangeRateConfig {
    @Bean
    fun fakeExchangeRateClient(): FakeExchangeRateClient = FakeExchangeRateClient()
}
