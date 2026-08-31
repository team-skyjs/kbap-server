package com.kbap.api.infra.place

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal

private const val GEOCODE_URL =
    "${GoogleGeocoder.BASE_URL}/maps/api/geocode/json?latlng=37.5636000%2C126.9834000&language=ko&key=test-key"

private val LAT = BigDecimal("37.5636000")
private val LNG = BigDecimal("126.9834000")

class GoogleGeocoderTest : BehaviorSpec({
    fun fixture(): Pair<GoogleGeocoder, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return GoogleGeocoder.create("test-key", builder) to server
    }

    given("구글 역지오코딩") {
        `when`("좌표 변환이 성공하면") {
            then("latlng·한국어 파라미터로 요청하고 첫 결과의 주소를 돌려준다") {
                val (geocoder, server) = fixture()
                server.expect(requestTo(GEOCODE_URL))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(
                        withSuccess(
                            """{"status":"OK","results":[{"formatted_address":"서울 중구 소공로 51"},{"formatted_address":"두번째"}]}""",
                            MediaType.APPLICATION_JSON,
                        ),
                    )

                geocoder.getRoadAddressOrNull(LAT, LNG) shouldBe "서울 중구 소공로 51"
                server.verify()
            }
        }

        `when`("결과가 없으면(ZERO_RESULTS)") {
            then("null 을 돌려준다") {
                val (geocoder, server) = fixture()
                server.expect(requestTo(GEOCODE_URL))
                    .andRespond(withSuccess("""{"status":"ZERO_RESULTS","results":[]}""", MediaType.APPLICATION_JSON))

                geocoder.getRoadAddressOrNull(LAT, LNG) shouldBe null
            }
        }

        `when`("키가 거절되면(REQUEST_DENIED)") {
            then("null 을 돌려준다") {
                val (geocoder, server) = fixture()
                server.expect(requestTo(GEOCODE_URL))
                    .andRespond(withSuccess("""{"status":"REQUEST_DENIED","results":[]}""", MediaType.APPLICATION_JSON))

                geocoder.getRoadAddressOrNull(LAT, LNG) shouldBe null
            }
        }

        `when`("제공처가 서버 오류를 돌려주면") {
            then("null 을 돌려준다") {
                val (geocoder, server) = fixture()
                server.expect(requestTo(GEOCODE_URL)).andRespond(withServerError())

                geocoder.getRoadAddressOrNull(LAT, LNG) shouldBe null
            }
        }

        `when`("응답 본문이 JSON 이 아니면") {
            then("null 을 돌려준다") {
                val (geocoder, server) = fixture()
                server.expect(requestTo(GEOCODE_URL))
                    .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON))

                geocoder.getRoadAddressOrNull(LAT, LNG) shouldBe null
            }
        }
    }
})
