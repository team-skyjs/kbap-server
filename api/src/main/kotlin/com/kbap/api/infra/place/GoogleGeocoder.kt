package com.kbap.api.infra.place

import com.kbap.common.domain.order.model.Order
import com.kbap.common.port.place.ReverseGeocoder
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.HttpMessageConversionException
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.support.RestClientAdapter
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Duration

internal interface GoogleGeocodingApi {
    @GetExchange("/maps/api/geocode/json")
    fun reverseGeocode(
        @RequestParam latlng: String,
        @RequestParam language: String,
        @RequestParam key: String,
    ): GoogleGeocodingResponse
}

internal data class GoogleGeocodingResponse(
    val status: String? = null,
    val results: List<GoogleGeocodingResult> = emptyList(),
)

internal data class GoogleGeocodingResult(
    val formattedAddress: String? = null,
)

class GoogleGeocoder internal constructor(
    private val googleGeocodingApi: GoogleGeocodingApi,
    private val apiKey: String,
) : ReverseGeocoder {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun getRoadAddressOrNull(latitude: BigDecimal, longitude: BigDecimal): String? {
        return try {
            val response = googleGeocodingApi.reverseGeocode(
                latlng = "${latitude.toPlainString()},${longitude.toPlainString()}",
                language = "ko",
                key = apiKey,
            )
            if (response.status != "OK") {
                log.warn("역지오코딩 결과 없음: status={}", response.status)
                return null
            }
            val address = response.results.firstOrNull()?.formattedAddress?.takeIf { it.isNotBlank() }
            if (address == null) {
                log.warn("역지오코딩 응답에 주소가 없음: status={}", response.status)
                return null
            }
            address.take(Order.MAX_ADDRESS_LENGTH)
        } catch (e: RestClientException) {
            log.warn("역지오코딩 실패: {}", e.javaClass.simpleName)
            null
        } catch (e: HttpMessageConversionException) {
            log.warn("역지오코딩 응답 파싱 실패: {}", e.javaClass.simpleName)
            null
        }
    }

    companion object {
        const val BASE_URL = "https://maps.googleapis.com"

        fun create(apiKey: String): GoogleGeocoder {
            val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(2)) }
            return create(apiKey, RestClient.builder().requestFactory(requestFactory))
        }

        internal fun create(apiKey: String, restClientBuilder: RestClient.Builder): GoogleGeocoder {
            val mapper = JsonMapper.builder()
                .addModule(kotlinModule())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build()
            val restClient = restClientBuilder
                .baseUrl(BASE_URL)
                .configureMessageConverters { converters ->
                    converters.disableDefaults().withJsonConverter(JacksonJsonHttpMessageConverter(mapper))
                }
                .build()
            val googleGeocodingApi = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(GoogleGeocodingApi::class.java)
            return GoogleGeocoder(googleGeocodingApi, apiKey)
        }
    }
}
