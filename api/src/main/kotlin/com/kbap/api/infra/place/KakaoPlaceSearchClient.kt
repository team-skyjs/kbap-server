package com.kbap.api.infra.place

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchClient
import com.kbap.common.port.place.PlaceSearchPage
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.HttpMessageConversionException
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
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

    override fun searchNearby(query: String, longitude: BigDecimal, latitude: BigDecimal): List<FoundPlace> =
        callKakao(query, longitude, latitude, page = 1, size = NEARBY_LIMIT)
            .documents.mapNotNull { it.toFoundPlace() }

    override fun searchPage(query: String, longitude: BigDecimal, latitude: BigDecimal, page: Int): PlaceSearchPage {
        val response = callKakao(query, longitude, latitude, page = page, size = SEARCH_PAGE_SIZE)
        return PlaceSearchPage(
            items = response.documents.mapNotNull { it.toFoundPlace() },
            hasNext = !response.meta.isEnd,
        )
    }

    private fun callKakao(
        query: String,
        longitude: BigDecimal,
        latitude: BigDecimal,
        page: Int,
        size: Int,
    ): KakaoKeywordSearchResponse {
        if (apiKey.isBlank()) {
            log.warn("카카오 REST 키가 설정돼 있지 않아 장소 검색을 수행할 수 없다")
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        }
        return try {
            kakaoLocalApi.searchKeyword(
                query = query,
                x = longitude.toPlainString(),
                y = latitude.toPlainString(),
                sort = "distance",
                page = page,
                size = size,
            )
        } catch (e: RestClientException) {
            log.warn("카카오 장소 검색 실패: query={} page={}", query, page, e)
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        } catch (e: HttpMessageConversionException) {
            log.warn("카카오 장소 검색 응답 파싱 실패: query={} page={}", query, page, e)
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        }
    }

    companion object {
        const val BASE_URL = "https://dapi.kakao.com"
        const val NEARBY_LIMIT = 10
        const val SEARCH_PAGE_SIZE = 15

        fun create(apiKey: String): KakaoPlaceSearchClient {
            val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(5)) }
            return create(apiKey, RestClient.builder().requestFactory(requestFactory))
        }

        internal fun create(apiKey: String, restClientBuilder: RestClient.Builder): KakaoPlaceSearchClient {
            val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
            val restClient = restClientBuilder
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "KakaoAK $apiKey")
                .configureMessageConverters { converters ->
                    converters.disableDefaults().withJsonConverter(JacksonJsonHttpMessageConverter(mapper))
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
