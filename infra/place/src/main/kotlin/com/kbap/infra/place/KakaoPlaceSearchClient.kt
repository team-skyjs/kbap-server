package com.kbap.infra.place

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchClient
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.HttpMessageConversionException
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.support.RestClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Duration

class KakaoPlaceSearchClient internal constructor(
    private val kakaoLocalApi: KakaoLocalApi,
    private val apiKey: String,
) : PlaceSearchClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun search(query: String, longitude: BigDecimal, latitude: BigDecimal): List<FoundPlace> {
        if (apiKey.isBlank()) {
            log.warn("카카오 REST 키가 설정돼 있지 않아 장소 검색을 수행할 수 없다")
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        }
        val response = try {
            kakaoLocalApi.searchKeyword(
                query = query,
                x = longitude.toPlainString(),
                y = latitude.toPlainString(),
                sort = "distance",
                size = TOP_LIMIT,
            )
        } catch (e: RestClientException) {
            log.warn("카카오 장소 검색 실패: query={}", query, e)
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        } catch (e: HttpMessageConversionException) {
            log.warn("카카오 장소 검색 응답 파싱 실패: query={}", query, e)
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        }

        return response.documents.mapNotNull { it.toFoundPlace() }
    }

    companion object {
        const val BASE_URL = "https://dapi.kakao.com"
        const val TOP_LIMIT = 10

        fun create(apiKey: String): KakaoPlaceSearchClient {
            val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(5)) }
            return create(apiKey, RestClient.builder().requestFactory(requestFactory))
        }

        internal fun create(apiKey: String, restClientBuilder: RestClient.Builder): KakaoPlaceSearchClient {
            val mapper = jacksonObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val restClient = restClientBuilder
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "KakaoAK $apiKey")
                .messageConverters { converters ->
                    converters.clear()
                    converters.add(MappingJackson2HttpMessageConverter(mapper))
                }
                .build()
            val kakaoLocalApi = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(KakaoLocalApi::class.java)
            return KakaoPlaceSearchClient(kakaoLocalApi, apiKey)
        }
    }
}
