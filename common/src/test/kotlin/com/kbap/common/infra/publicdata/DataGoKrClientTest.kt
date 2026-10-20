package com.kbap.common.infra.publicdata

import com.kbap.common.port.publicdata.PublicDataCallException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Duration

private const val BASE_URL = "https://apis.test"
private const val KEY = "abc+def/ghi=="
private const val ENCODED_KEY = "abc%2Bdef%2Fghi%3D%3D"

class DataGoKrClientTest : BehaviorSpec({
    val quickRetry = DataGoKrClient.defaultRetryPolicy(maxRetries = 3, initialDelay = Duration.ofMillis(1))

    fun fixture(): Pair<DataGoKrClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return DataGoKrClient.create(BASE_URL, KEY, builder, quickRetry) to server
    }

    val haccpPage = """
        {"header":{"resultCode":"OK","resultMessage":"success"},
         "body":{"items":[{"item":{"prdlstReportNo":"2011022182815","prdlstNm":"새우깡","prdkind":"과자","manufacture":"알수없음 ","seller":"",
                  "rawmtrl":"밀가루","allergy":"밀, 새우","barcode":"8801043026529","imgurl1":"http://img/1.jpg","imgurl2":"http://img/2.jpg"}},
                 {"item":{"prdlstNm":"번호 없는 행"}}],
                 "totalCount":"361","pageNo":"1","numOfRows":"100"}}
    """.trimIndent()

    given("HACCP 제품정보 페이지 조회") {
        `when`("정상 응답이면") {
            then("품목보고번호 있는 행만 제품으로 옮기고 문자열 totalCount 를 숫자로 읽는다, 인증키는 퍼센트 인코딩된다") {
                val (client, server) = fixture()
                server.expect(requestTo(org.hamcrest.Matchers.containsString("ServiceKey=$ENCODED_KEY")))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(queryParam("prdkind", "%EC%9C%A0%ED%83%95%EB%A9%B4"))
                    .andRespond(withSuccess(haccpPage, MediaType.APPLICATION_JSON))

                val page = DataGoKrHaccpProductSource(client).getPage("유탕면", 1, 100)

                page.totalCount shouldBe 361
                page.items.size shouldBe 1
                page.items.single().reportNo shouldBe "2011022182815"
                page.items.single().manufacturer shouldBe "알수없음"
                page.items.single().seller shouldBe null
                server.verify()
            }
        }

        `when`("5xx 가 세 번 난 뒤 성공하면") {
            then("재시도로 결과를 받는다 — 빈 목록으로 오인하지 않는다") {
                val (client, server) = fixture()
                server.expect(ExpectedCount.times(3), requestTo(org.hamcrest.Matchers.startsWith(BASE_URL))).andRespond(withServerError())
                server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL))).andRespond(withSuccess(haccpPage, MediaType.APPLICATION_JSON))

                DataGoKrHaccpProductSource(client).getPage("유탕면", 1, 100).items.size shouldBe 1
                server.verify()
            }
        }

        `when`("5xx 가 재시도 한도를 넘으면") {
            then("예외 메시지에 인증키가 없다") {
                val (client, server) = fixture()
                server.expect(ExpectedCount.times(4), requestTo(org.hamcrest.Matchers.startsWith(BASE_URL))).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT))

                val error = shouldThrow<PublicDataCallException> { DataGoKrHaccpProductSource(client).getPage("유탕면", 1, 100) }

                error.message!! shouldContain "HTTP 504"
                error.message!! shouldNotContain KEY
                error.message!! shouldNotContain ENCODED_KEY
                error.cause shouldBe null
            }
        }

        `when`("인증키가 등록되지 않았으면(403 + errMsg)") {
            then("재시도 없이 errMsg 를 담아 실패한다") {
                val (client, server) = fixture()
                server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                    .andRespond(
                        withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                            .body("""{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","returnReasonCode":"30"}}}"""),
                    )

                val error = shouldThrow<PublicDataCallException> { DataGoKrHaccpProductSource(client).getPage("과자", 1, 100) }

                error.message!! shouldContain "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"
                error.message!! shouldNotContain KEY
                server.verify()
            }
        }
    }

    given("식품영양성분DB 제조사 조회") {
        `when`("품목보고번호로 상용제품 행이 있으면") {
            then("첫 MAKER_NM 을 돌려주고, 행이 없으면 null") {
                val (client, server) = fixture()
                server.expect(requestTo(org.hamcrest.Matchers.containsString("ITEM_REPORT_NO=2001054954178")))
                    .andRespond(withSuccess("""{"header":{"resultCode":"00"},"body":{"items":[{"MAKER_NM":"None"},{"MAKER_NM":"㈜농심"}],"totalCount":2}}""", MediaType.APPLICATION_JSON))
                server.expect(requestTo(org.hamcrest.Matchers.containsString("ITEM_REPORT_NO=1")))
                    .andRespond(withSuccess("""{"header":{"resultCode":"00"},"body":{"pageNo":1,"totalCount":0,"numOfRows":10}}""", MediaType.APPLICATION_JSON))

                val lookup = DataGoKrFoodMakerLookup(client)

                lookup.getMakerOrNull("2001054954178") shouldBe "㈜농심"
                lookup.getMakerOrNull("1") shouldBe null
                server.verify()
            }
        }
    }
})
