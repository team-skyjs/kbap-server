package com.kbap.api.infra.exchange

import com.kbap.common.domain.CurrencyCode
import com.kbap.common.port.exchange.ExchangeRateClient
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.HttpMessageConversionException
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.support.RestClientAdapter
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.http.HttpClient
import java.time.Duration

internal interface FrankfurterApi {
    @GetExchange("/v1/latest?base=EUR")
    fun latestByEur(): FrankfurterLatestResponse
}

internal data class FrankfurterLatestResponse(
    val rates: Map<String, BigDecimal> = emptyMap(),
)

class FrankfurterExchangeRateClient internal constructor(
    private val frankfurterApi: FrankfurterApi,
) : ExchangeRateClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun getKrwPerUnitOrNull(currency: CurrencyCode): BigDecimal? {
        if (currency == CurrencyCode.KRW) return KRW_PER_KRW
        return try {
            val rates = frankfurterApi.latestByEur().rates
            val krwPerEur = rates[CurrencyCode.KRW.name] ?: return null
            val targetPerEur = if (currency == CurrencyCode.EUR) BigDecimal.ONE else rates[currency.name] ?: return null
            krwPerEur.divide(targetPerEur, SCALE, RoundingMode.HALF_UP)
        } catch (e: RestClientException) {
            log.warn("환율 조회 실패: currency={}", currency, e)
            null
        } catch (e: HttpMessageConversionException) {
            log.warn("환율 응답 파싱 실패: currency={}", currency, e)
            null
        }
    }

    companion object {
        const val BASE_URL = "https://api.frankfurter.dev"
        private const val SCALE = 4
        private val KRW_PER_KRW = BigDecimal("1.0000")

        fun create(baseUrl: String): FrankfurterExchangeRateClient {
            val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(2)) }
            return create(baseUrl, RestClient.builder().requestFactory(requestFactory))
        }

        internal fun create(baseUrl: String, restClientBuilder: RestClient.Builder): FrankfurterExchangeRateClient {
            val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
            val restClient = restClientBuilder
                .baseUrl(baseUrl)
                .configureMessageConverters { converters ->
                    converters.disableDefaults().withJsonConverter(JacksonJsonHttpMessageConverter(mapper))
                }
                .build()
            val frankfurterApi = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(FrankfurterApi::class.java)
            return FrankfurterExchangeRateClient(frankfurterApi)
        }
    }
}
