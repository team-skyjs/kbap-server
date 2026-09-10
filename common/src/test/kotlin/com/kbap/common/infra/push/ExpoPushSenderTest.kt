package com.kbap.common.infra.push

import com.kbap.common.port.push.PushMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

private const val BASE_URL = "https://expo.test"
private const val SEND_URL = "$BASE_URL/--/api/v2/push/send"

class ExpoPushSenderTest : BehaviorSpec({
    fun fixture(accessToken: String = ""): Pair<ExpoPushSender, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return ExpoPushSender.create(BASE_URL, accessToken, builder) to server
    }

    fun messages(count: Int, offset: Int = 0): List<PushMessage> =
        List(count) { i -> PushMessage("ExponentPushToken[${offset + i}]", "t${offset + i}", "b", mapOf("type" to "NEWS")) }

    fun okBody(from: Int, count: Int): String =
        (from until from + count).joinToString(",", prefix = """{"data":[""", postfix = "]}") { """{"status":"ok","id":"t$it"}""" }

    given("Expo push 어댑터") {
        `when`("250건을 보내면") {
            then("100·100·50 세 청크로 나눠 요청하고 티켓 250개를 입력 순서대로 돌려준다") {
                val (sender, server) = fixture()
                server.expect(requestTo(SEND_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.length()").value(100))
                    .andRespond(withSuccess(okBody(0, 100), MediaType.APPLICATION_JSON))
                server.expect(requestTo(SEND_URL))
                    .andExpect(jsonPath("$.length()").value(100))
                    .andRespond(withSuccess(okBody(100, 100), MediaType.APPLICATION_JSON))
                server.expect(requestTo(SEND_URL))
                    .andExpect(jsonPath("$.length()").value(50))
                    .andRespond(withSuccess(okBody(200, 50), MediaType.APPLICATION_JSON))

                val tickets = sender.send(messages(250))

                tickets shouldHaveSize 250
                tickets[0].id shouldBe "t0"
                tickets[249].id shouldBe "t249"
                tickets.all { it.ok } shouldBe true
                server.verify()
            }
        }

        `when`("응답 티켓이 error 이면") {
            then("details.error 를 우선, 없으면 message 를 error 로 담는다") {
                val (sender, server) = fixture()
                server.expect(requestTo(SEND_URL)).andRespond(
                    withSuccess(
                        """{"data":[{"status":"ok","id":"a"},{"status":"error","message":"m","details":{"error":"DeviceNotRegistered"}},{"status":"error","message":"m"}]}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

                val tickets = sender.send(messages(3))

                tickets[0].ok shouldBe true
                tickets[0].id shouldBe "a"
                tickets[1].ok shouldBe false
                tickets[1].error shouldBe "DeviceNotRegistered"
                tickets[2].ok shouldBe false
                tickets[2].error shouldBe "m"
            }
        }

        `when`("두 번째 청크 호출이 서버 오류이면") {
            then("그 청크만 전부 error 티켓이 되고 예외는 전파되지 않는다") {
                val (sender, server) = fixture()
                server.expect(requestTo(SEND_URL)).andRespond(withSuccess(okBody(0, 100), MediaType.APPLICATION_JSON))
                server.expect(requestTo(SEND_URL)).andRespond(withServerError())

                val tickets = sender.send(messages(150))

                tickets shouldHaveSize 150
                tickets.take(100).all { it.ok } shouldBe true
                tickets.drop(100).all { !it.ok } shouldBe true
                tickets[100].error!!.shouldNotBeBlank()
            }
        }

        `when`("메시지 본문을 만들면") {
            then("sound·priority·channelId 기본값과 data 를 함께 보낸다") {
                val (sender, server) = fixture()
                server.expect(requestTo(SEND_URL))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$[0].to").value("ExponentPushToken[0]"))
                    .andExpect(jsonPath("$[0].title").value("t0"))
                    .andExpect(jsonPath("$[0].sound").value("default"))
                    .andExpect(jsonPath("$[0].priority").value("high"))
                    .andExpect(jsonPath("$[0].channelId").value("default"))
                    .andExpect(jsonPath("$[0].data.type").value("NEWS"))
                    .andRespond(withSuccess(okBody(0, 1), MediaType.APPLICATION_JSON))

                sender.send(messages(1))
                server.verify()
            }
        }

        `when`("access token 이 설정돼 있으면") {
            then("Authorization Bearer 헤더를 붙인다") {
                val (sender, server) = fixture(accessToken = "tok")
                server.expect(requestTo(SEND_URL))
                    .andExpect(header("Authorization", "Bearer tok"))
                    .andRespond(withSuccess(okBody(0, 1), MediaType.APPLICATION_JSON))

                sender.send(messages(1))
                server.verify()
            }
        }

        `when`("access token 이 비어 있으면") {
            then("Authorization 헤더가 없다") {
                val (sender, server) = fixture(accessToken = "")
                server.expect(requestTo(SEND_URL))
                    .andExpect(headerDoesNotExist("Authorization"))
                    .andRespond(withSuccess(okBody(0, 1), MediaType.APPLICATION_JSON))

                sender.send(messages(1))
                server.verify()
            }
        }

        `when`("빈 목록을 보내면") {
            then("HTTP 호출 없이 빈 결과를 돌려준다") {
                val (sender, server) = fixture()
                server.expect(ExpectedCount.never(), requestTo(SEND_URL))

                sender.send(emptyList()) shouldHaveSize 0
                server.verify()
            }
        }
    }
})
