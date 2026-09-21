package com.kbap.common.infra.push

import com.kbap.common.port.push.PushReceipt
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

private const val BASE_URL = "https://expo.test"
private const val RECEIPTS_URL = "$BASE_URL/--/api/v2/push/getReceipts"

class ExpoPushReceiptFetcherTest : BehaviorSpec({
    fun fixture(accessToken: String = ""): Pair<ExpoPushReceiptFetcher, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return ExpoPushReceiptFetcher.create(BASE_URL, accessToken, builder) to server
    }

    given("Expo 영수증 조회 어댑터") {
        `when`("ok·코드 있는 오류·코드 없는 오류가 섞여 오고 한 건은 응답에 없으면") {
            then("있는 영수증만 계약대로 옮기고 없는 id 는 결과에서 빠진다") {
                val (fetcher, server) = fixture("secret")
                server.expect(requestTo(RECEIPTS_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer secret"))
                    .andExpect(jsonPath("$.ids.length()").value(4))
                    .andExpect(jsonPath("$.ids[0]").value("a"))
                    .andRespond(
                        withSuccess(
                            """{"data":{
                              "a":{"status":"ok"},
                              "b":{"status":"error","message":"not registered","details":{"error":"DeviceNotRegistered"}},
                              "c":{"status":"error","message":"provider failed"}
                            }}""",
                            MediaType.APPLICATION_JSON,
                        ),
                    )

                val receipts = fetcher.fetch(listOf("a", "b", "c", "d"))

                receipts shouldHaveSize 3
                receipts["a"] shouldBe PushReceipt(ok = true)
                receipts["b"] shouldBe PushReceipt(ok = false, errorCode = "DeviceNotRegistered", message = "not registered")
                receipts["c"] shouldBe PushReceipt(ok = false, errorCode = null, message = "provider failed")
                server.verify()
            }
        }

        `when`("1,500건을 조회하면") {
            then("1000·500 두 요청으로 나눈다") {
                val (fetcher, server) = fixture()
                server.expect(requestTo(RECEIPTS_URL))
                    .andExpect(jsonPath("$.ids.length()").value(1000))
                    .andRespond(withSuccess("""{"data":{"t0":{"status":"ok"}}}""", MediaType.APPLICATION_JSON))
                server.expect(requestTo(RECEIPTS_URL))
                    .andExpect(jsonPath("$.ids.length()").value(500))
                    .andRespond(withSuccess("""{"data":{"t1000":{"status":"ok"}}}""", MediaType.APPLICATION_JSON))

                fetcher.fetch(List(1500) { "t$it" }).keys shouldBe setOf("t0", "t1000")
                server.verify()
            }
        }

        `when`("조회할 id 가 없으면") {
            then("요청하지 않는다") {
                val (fetcher, server) = fixture()

                fetcher.fetch(emptyList()) shouldHaveSize 0
                server.verify()
            }
        }

        `when`("Expo 가 5xx 로 답하면") {
            then("예외를 그대로 올린다") {
                val (fetcher, server) = fixture()
                server.expect(requestTo(RECEIPTS_URL)).andRespond(withServerError())

                shouldThrow<RestClientException> { fetcher.fetch(listOf("a")) }
            }
        }
    }
})
