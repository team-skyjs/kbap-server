package com.kbap.api.infra.place

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.startsWith
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal

private const val SUCCESS_BODY = """
{
  "places": [
    {
      "id": "ChIJgangnam001",
      "displayName": { "text": "한밥집 강남점", "languageCode": "ko" },
      "formattedAddress": "서울 강남구 테헤란로 123",
      "location": { "latitude": 37.4979502, "longitude": 127.0276368 }
    },
    {
      "displayName": { "text": "한밥집 신촌점", "languageCode": "ko" },
      "formattedAddress": "서울 서대문구 창천동 45",
      "location": { "latitude": 37.5559, "longitude": 126.9366 }
    },
    {
      "formattedAddress": "이름 없는 항목 — 제외 대상",
      "location": { "latitude": 1.0, "longitude": 2.0 }
    }
  ]
}
"""

private val LAT = BigDecimal("37.4979502")
private val LNG = BigDecimal("127.0276368")

class GooglePlaceSearchClientTest : BehaviorSpec({
    fun fixture(apiKey: String = "test-key"): Pair<GooglePlaceSearchClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return GooglePlaceSearchClient.create(apiKey, builder) to server
    }

    given("구글 장소 검색") {
        `when`("주변 식당 조회가 성공하면") {
            then("Nearby Search(New) 형식(restaurant·20건·DISTANCE·반경 500m·FieldMask)으로 요청하고 목록을 매핑한다") {
                val (client, server) = fixture()
                server.expect(requestTo("${GooglePlaceSearchClient.BASE_URL}/v1/places:searchNearby"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Goog-Api-Key", "test-key"))
                    .andExpect(header("X-Goog-FieldMask", GooglePlaceSearchClient.FIELD_MASK))
                    .andExpect(jsonPath("$.includedTypes[0]").value("restaurant"))
                    .andExpect(jsonPath("$.maxResultCount").value(GooglePlaceSearchClient.RESULT_LIMIT))
                    .andExpect(jsonPath("$.rankPreference").value("DISTANCE"))
                    .andExpect(jsonPath("$.languageCode").value("ko"))
                    .andExpect(jsonPath("$.locationRestriction.circle.center.latitude").value(LAT.toDouble()))
                    .andExpect(jsonPath("$.locationRestriction.circle.center.longitude").value(LNG.toDouble()))
                    .andExpect(jsonPath("$.locationRestriction.circle.radius").value(GooglePlaceSearchClient.NEARBY_RADIUS_METERS))
                    .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON))

                val result = client.searchNearbyRestaurants(LNG, LAT, LanguageCode.KO)

                result.size shouldBe 2
                result[0].placeId shouldBe "ChIJgangnam001"
                result[0].name shouldBe "한밥집 강남점"
                result[0].address shouldBe "서울 강남구 테헤란로 123"
                result[0].latitude shouldBe BigDecimal("37.4979502")
                result[0].longitude shouldBe BigDecimal("127.0276368")
                server.verify()
            }
        }

        `when`("키워드 검색이 성공하면") {
            then("Text Search(New) 형식(textQuery·pageSize 20·locationBias 2km)으로 요청하고 목록을 매핑한다") {
                val (client, server) = fixture()
                server.expect(requestTo("${GooglePlaceSearchClient.BASE_URL}/v1/places:searchText"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Goog-Api-Key", "test-key"))
                    .andExpect(header("X-Goog-FieldMask", GooglePlaceSearchClient.FIELD_MASK))
                    .andExpect(jsonPath("$.textQuery").value("마리김밥"))
                    .andExpect(jsonPath("$.pageSize").value(GooglePlaceSearchClient.RESULT_LIMIT))
                    .andExpect(jsonPath("$.languageCode").value("vi"))
                    .andExpect(jsonPath("$.locationBias.circle.center.latitude").value(LAT.toDouble()))
                    .andExpect(jsonPath("$.locationBias.circle.radius").value(GooglePlaceSearchClient.SEARCH_BIAS_RADIUS_METERS))
                    .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON))

                val result = client.searchByKeyword("마리김밥", LNG, LAT, LanguageCode.VI)

                result.size shouldBe 2
                result[1].name shouldBe "한밥집 신촌점"
                server.verify()
            }
        }

        `when`("중국어 간체·번체로 요청하면") {
            then("구글 표기(zh-CN·zh-TW)로 매핑해 보낸다") {
                val (client, server) = fixture()
                server.expect(jsonPath("$.languageCode").value("zh-CN"))
                    .andRespond(withSuccess("""{"places": []}""", MediaType.APPLICATION_JSON))
                server.expect(jsonPath("$.languageCode").value("zh-TW"))
                    .andRespond(withSuccess("""{"places": []}""", MediaType.APPLICATION_JSON))

                client.searchNearbyRestaurants(LNG, LAT, LanguageCode.ZH_HANS)
                client.searchByKeyword("김밥", LNG, LAT, LanguageCode.ZH_HANT)
                server.verify()
            }
        }

        `when`("결과가 없으면") {
            then("빈 목록을 반환한다") {
                val (client, server) = fixture()
                server.expect(requestTo(startsWith(GooglePlaceSearchClient.BASE_URL)))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

                client.searchNearbyRestaurants(LNG, LAT, LanguageCode.EN).size shouldBe 0
            }
        }

        `when`("구글이 5xx 로 실패하면") {
            then("PLACE-001 로 감싼다") {
                val (client, server) = fixture()
                server.expect(requestTo(startsWith(GooglePlaceSearchClient.BASE_URL)))
                    .andRespond(withServerError())

                shouldThrow<BusinessException> { client.searchNearbyRestaurants(LNG, LAT, LanguageCode.KO) }
                    .errorCode shouldBe ErrorCode.PLACE_SEARCH_FAILED
            }
        }

        `when`("구글이 400 으로 거절하면") {
            then("PLACE-001 로 감싼다") {
                val (client, server) = fixture()
                server.expect(requestTo(startsWith(GooglePlaceSearchClient.BASE_URL)))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST))

                shouldThrow<BusinessException> { client.searchByKeyword("김밥", LNG, LAT, LanguageCode.KO) }
                    .errorCode shouldBe ErrorCode.PLACE_SEARCH_FAILED
            }
        }

        `when`("응답이 JSON 이 아니면") {
            then("PLACE-001 로 감싼다") {
                val (client, server) = fixture()
                server.expect(requestTo(startsWith(GooglePlaceSearchClient.BASE_URL)))
                    .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON))

                shouldThrow<BusinessException> { client.searchNearbyRestaurants(LNG, LAT, LanguageCode.KO) }
                    .errorCode shouldBe ErrorCode.PLACE_SEARCH_FAILED
            }
        }

        `when`("API 키가 설정돼 있지 않으면") {
            then("외부 호출 없이 PLACE-001 로 실패한다") {
                val (client, server) = fixture(apiKey = "")

                shouldThrow<BusinessException> { client.searchNearbyRestaurants(LNG, LAT, LanguageCode.KO) }
                    .errorCode shouldBe ErrorCode.PLACE_SEARCH_FAILED
                server.verify()
            }
        }
    }
})
