package com.kbap.api.infra.place

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchClient
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.HttpMessageConversionException
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.support.RestClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Duration

class GooglePlaceSearchClient internal constructor(
    private val googlePlacesApi: GooglePlacesApi,
    private val apiKey: String,
) : PlaceSearchClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun searchNearbyRestaurants(
        longitude: BigDecimal,
        latitude: BigDecimal,
        lang: LanguageCode,
    ): List<FoundPlace> =
        callGoogle("nearby") {
            googlePlacesApi.searchNearby(
                GoogleNearbySearchRequest(
                    includedTypes = listOf(RESTAURANT_TYPE),
                    maxResultCount = RESULT_LIMIT,
                    rankPreference = "DISTANCE",
                    languageCode = googleLanguageCode(lang),
                    locationRestriction = circle(latitude, longitude, NEARBY_RADIUS_METERS),
                ),
            )
        }

    override fun searchByKeyword(
        query: String,
        longitude: BigDecimal,
        latitude: BigDecimal,
        lang: LanguageCode,
    ): List<FoundPlace> =
        callGoogle("search") {
            googlePlacesApi.searchText(
                GoogleTextSearchRequest(
                    textQuery = query,
                    pageSize = RESULT_LIMIT,
                    languageCode = googleLanguageCode(lang),
                    locationBias = circle(latitude, longitude, SEARCH_BIAS_RADIUS_METERS),
                ),
            )
        }

    private fun callGoogle(purpose: String, request: () -> GooglePlacesResponse): List<FoundPlace> {
        if (apiKey.isBlank()) {
            log.warn("구글 Places API 키가 설정돼 있지 않아 장소 검색을 수행할 수 없다")
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        }
        return try {
            request().places.mapNotNull { it.toFoundPlace() }
        } catch (e: RestClientException) {
            log.warn("구글 장소 검색 실패: purpose={}", purpose, e)
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        } catch (e: HttpMessageConversionException) {
            log.warn("구글 장소 검색 응답 파싱 실패: purpose={}", purpose, e)
            throw BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
        }
    }

    private fun circle(latitude: BigDecimal, longitude: BigDecimal, radius: Double): GoogleLocationCircle =
        GoogleLocationCircle(
            GoogleCircle(
                center = GoogleLatLng(latitude = latitude.toDouble(), longitude = longitude.toDouble()),
                radius = radius,
            ),
        )

    companion object {
        const val BASE_URL = "https://places.googleapis.com"
        const val FIELD_MASK = "places.id,places.displayName,places.formattedAddress,places.location"
        const val RESTAURANT_TYPE = "restaurant"
        const val RESULT_LIMIT = 20
        const val NEARBY_RADIUS_METERS = 500.0
        const val SEARCH_BIAS_RADIUS_METERS = 2000.0

        fun googleLanguageCode(lang: LanguageCode): String =
            when (lang) {
                LanguageCode.ZH_HANS -> "zh-CN"
                LanguageCode.ZH_HANT -> "zh-TW"
                else -> lang.code
            }

        fun create(apiKey: String): GooglePlaceSearchClient {
            val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(5)) }
            return create(apiKey, RestClient.builder().requestFactory(requestFactory))
        }

        internal fun create(apiKey: String, restClientBuilder: RestClient.Builder): GooglePlaceSearchClient {
            val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
            val restClient = restClientBuilder
                .baseUrl(BASE_URL)
                .defaultHeader("X-Goog-Api-Key", apiKey)
                .defaultHeader("X-Goog-FieldMask", FIELD_MASK)
                .configureMessageConverters { converters ->
                    converters.disableDefaults().withJsonConverter(JacksonJsonHttpMessageConverter(mapper))
                }
                .build()
            val googlePlacesApi = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(GooglePlacesApi::class.java)
            return GooglePlaceSearchClient(googlePlacesApi, apiKey)
        }
    }
}
