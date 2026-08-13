package com.kbap.infra.place

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val SUCCESS_BODY = """
{
  "documents": [
    {
      "id": "27290047",
      "place_name": "한밥집 강남점",
      "address_name": "서울 강남구 역삼동 123",
      "road_address_name": "서울 강남구 테헤란로 123",
      "x": "127.0276368",
      "y": "37.4979502"
    },
    {
      "id": "12345678",
      "place_name": "한밥집 신촌점",
      "address_name": "서울 서대문구 창천동 45",
      "road_address_name": "",
      "x": "126.9366",
      "y": "37.5559"
    }
  ],
  "meta": { "is_end": false }
}
"""

private val LAT = BigDecimal("37.4979502")
private val LNG = BigDecimal("127.0276368")

class KakaoPlaceSearchClientTest : BehaviorSpec({
    fun fixture(apiKey: String = "test-key"): Pair<KakaoPlaceSearchClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return KakaoPlaceSearchClient(builder.build(), apiKey) to server
    }

    given("카카오 장소 검색") {
        `when`("검색이 성공하면") {
            then("문서를 FoundPlace 목록으로 매핑한다") {
                val (client, server) = fixture()
                server.expect(
                    requestTo(
                        org.hamcrest.Matchers.containsString(
                            "query=" + URLEncoder.encode("음식점", StandardCharsets.UTF_8),
                        ),
                    ),
                )
                    .andExpect(queryParam("x", LNG.toPlainString()))
                    .andExpect(queryParam("y", LAT.toPlainString()))
                    .andExpect(queryParam("sort", "distance"))
                    .andExpect(queryParam("size", KakaoPlaceSearchClient.TOP_LIMIT.toString()))
                    .andExpect(header("Authorization", "KakaoAK test-key"))
                    .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON))

                val result = client.search("음식점", LNG, LAT)

                result.size shouldBe 2
                result[0].name shouldBe "한밥집 강남점"
                result[0].latitude shouldBe BigDecimal("37.4979502")
                result[0].longitude shouldBe BigDecimal("127.0276368")
                server.verify()
            }
        }

        `when`("도로명주소가 비어 있으면") {
            then("지번주소로 대체한다") {
                val (client, server) = fixture()
                server.expect(requestTo(org.hamcrest.Matchers.startsWith(KakaoPlaceSearchClient.SEARCH_URL)))
                    .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON))

                val result = client.search("음식점", LNG, LAT)

                result[0].address shouldBe "서울 강남구 테헤란로 123"
                result[1].address shouldBe "서울 서대문구 창천동 45"
            }
        }

        `when`("결과가 없으면") {
            then("빈 목록을 반환한다") {
                val (client, server) = fixture()
                server.expect(requestTo(org.hamcrest.Matchers.startsWith(KakaoPlaceSearchClient.SEARCH_URL)))
                    .andRespond(
                        withSuccess(
                            """{"documents": [], "meta": {"is_end": true}}""",
                            MediaType.APPLICATION_JSON,
                        ),
                    )

                val result = client.search("음식점", LNG, LAT)

                result.size shouldBe 0
            }
        }

        `when`("카카오가 5xx 로 실패하면") {
            then("PLACE-001 로 감싼다") {
                val (client, server) = fixture()
                server.expect(requestTo(org.hamcrest.Matchers.startsWith(KakaoPlaceSearchClient.SEARCH_URL)))
                    .andRespond(withServerError())

                val exception = shouldThrow<BusinessException> { client.search("음식점", LNG, LAT) }

                exception.errorCode shouldBe ErrorCode.PLACE_SEARCH_FAILED
            }
        }

        `when`("카카오가 401 로 거절하면") {
            then("PLACE-001 로 감싼다") {
                val (client, server) = fixture()
                server.expect(requestTo(org.hamcrest.Matchers.startsWith(KakaoPlaceSearchClient.SEARCH_URL)))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

                val exception = shouldThrow<BusinessException> { client.search("음식점", LNG, LAT) }

                exception.errorCode shouldBe ErrorCode.PLACE_SEARCH_FAILED
            }
        }

        `when`("응답이 JSON 이 아니면") {
            then("PLACE-001 로 감싼다") {
                val (client, server) = fixture()
                server.expect(requestTo(org.hamcrest.Matchers.startsWith(KakaoPlaceSearchClient.SEARCH_URL)))
                    .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON))

                val exception = shouldThrow<BusinessException> { client.search("음식점", LNG, LAT) }

                exception.errorCode shouldBe ErrorCode.PLACE_SEARCH_FAILED
            }
        }

        `when`("REST 키가 설정돼 있지 않으면") {
            then("외부 호출 없이 PLACE-001 로 실패한다") {
                val (client, server) = fixture(apiKey = "")

                val exception = shouldThrow<BusinessException> { client.search("음식점", LNG, LAT) }

                exception.errorCode shouldBe ErrorCode.PLACE_SEARCH_FAILED
                server.verify()
            }
        }
    }
})
