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
import org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests
import org.springframework.core.retry.RetryPolicy
import org.springframework.web.client.RestClient
import io.kotest.matchers.string.shouldContain
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

private const val BASE_URL = "https://expo.test"
private const val SEND_URL = "$BASE_URL/--/api/v2/push/send"

class ExpoPushClientTest : BehaviorSpec({
    val quickRetry: RetryPolicy = ExpoPushClient.defaultRetryPolicy(maxRetries = 3, initialDelay = Duration.ofMillis(1), multiplier = 2.0)

    fun fixture(accessToken: String = ""): Pair<ExpoPushClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return ExpoPushClient.create(BASE_URL, accessToken, builder, quickRetry) to server
    }

    class LocalExpo(private val handlerDelay: Duration) : AutoCloseable {
        private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val active = AtomicInteger()
        val arrivals = java.util.concurrent.CopyOnWriteArrayList<Long>()
        var maxActive = 0
            private set

        init {
            server.createContext("/--/api/v2/push/send") { exchange ->
                arrivals += System.nanoTime()
                val now = active.incrementAndGet()
                synchronized(this) { if (now > maxActive) maxActive = now }
                val body = exchange.requestBody.readAllBytes()
                Thread.sleep(handlerDelay.toMillis())
                val messages: List<Map<String, Any>> = mapper.readValue(body, mapper.typeFactory.constructCollectionType(List::class.java, Map::class.java))
                val response = messages.joinToString(",", prefix = """{"data":[""", postfix = "]}") {
                    """{"status":"ok","id":"t${(it["to"] as String).removePrefix("ExponentPushToken[").removeSuffix("]")}"}"""
                }.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
                active.decrementAndGet()
            }
            server.executor = java.util.concurrent.Executors.newCachedThreadPool()
            server.start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        override fun close() = server.stop(0)
    }

    fun messages(count: Int, offset: Int = 0): List<PushMessage> =
        List(count) { i -> PushMessage("ExponentPushToken[${offset + i}]", "t${offset + i}", "b", mapOf("type" to "NEWS")) }

    fun okBody(from: Int, count: Int): String =
        (from until from + count).joinToString(",", prefix = """{"data":[""", postfix = "]}") { """{"status":"ok","id":"t$it"}""" }

    given("Expo push 어댑터") {
        `when`("250건을 보내면") {
            then("100·100·50 세 청크로 나눠 요청하고 티켓 250개를 입력 순서대로 돌려준다") {
                val (client, server) = fixture()
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

                val tickets = client.send(messages(250))

                tickets shouldHaveSize 250
                tickets[0].id shouldBe "t0"
                tickets[249].id shouldBe "t249"
                tickets.all { it.ok } shouldBe true
                server.verify()
            }
        }

        `when`("응답 티켓이 error 이면") {
            then("details.error 를 우선, 없으면 message 를 error 로 담는다") {
                val (client, server) = fixture()
                server.expect(requestTo(SEND_URL)).andRespond(
                    withSuccess(
                        """{"data":[{"status":"ok","id":"a"},{"status":"error","message":"m","details":{"error":"DeviceNotRegistered"}},{"status":"error","message":"m"}]}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

                val tickets = client.send(messages(3))

                tickets[0].ok shouldBe true
                tickets[0].id shouldBe "a"
                tickets[1].ok shouldBe false
                tickets[1].error shouldBe "DeviceNotRegistered"
                tickets[2].ok shouldBe false
                tickets[2].error shouldBe "m"
            }
        }

        `when`("두 번째 청크 호출이 재시도 한도까지 서버 오류이면") {
            then("그 청크만 전부 error 티켓이 되고 예외는 전파되지 않는다") {
                val (client, server) = fixture()
                server.expect(requestTo(SEND_URL)).andRespond(withSuccess(okBody(0, 100), MediaType.APPLICATION_JSON))
                server.expect(ExpectedCount.times(4), requestTo(SEND_URL)).andRespond(withServerError())

                val tickets = client.send(messages(150))

                tickets shouldHaveSize 150
                tickets.take(100).all { it.ok } shouldBe true
                tickets.drop(100).all { !it.ok } shouldBe true
                tickets[100].error!!.shouldNotBeBlank()
                server.verify()
            }
        }

        `when`("5xx 두 번 뒤 정상 응답이 오면") {
            then("지수 백오프로 재시도해 요청 3회 만에 전부 ok 티켓을 돌려준다") {
                val (client, server) = fixture()
                server.expect(ExpectedCount.times(2), requestTo(SEND_URL)).andRespond(withServerError())
                server.expect(requestTo(SEND_URL)).andRespond(withSuccess(okBody(0, 100), MediaType.APPLICATION_JSON))

                val tickets = client.send(messages(100))

                tickets.all { it.ok } shouldBe true
                server.verify()
            }
        }

        `when`("429 두 번 뒤 정상 응답이 오면") {
            then("재시도해 전부 ok 티켓을 돌려준다") {
                val (client, server) = fixture()
                server.expect(ExpectedCount.times(2), requestTo(SEND_URL)).andRespond(withTooManyRequests())
                server.expect(requestTo(SEND_URL)).andRespond(withSuccess(okBody(0, 1), MediaType.APPLICATION_JSON))

                client.send(messages(1)).single().ok shouldBe true
                server.verify()
            }
        }

        `when`("네트워크 오류 뒤 정상 응답이 오면") {
            then("재시도해 ok 티켓을 돌려준다") {
                val (client, server) = fixture()
                server.expect(requestTo(SEND_URL)).andRespond(withException(IOException("connection reset")))
                server.expect(requestTo(SEND_URL)).andRespond(withSuccess(okBody(0, 1), MediaType.APPLICATION_JSON))

                client.send(messages(1)).single().ok shouldBe true
                server.verify()
            }
        }

        `when`("5xx 가 재시도 한도를 넘겨 계속되면") {
            then("요청 4회 뒤 청크 전부 error 티켓이고 마지막 오류가 사유에 남는다") {
                val (client, server) = fixture()
                server.expect(ExpectedCount.times(4), requestTo(SEND_URL)).andRespond(withServerError())

                val tickets = client.send(messages(3))

                tickets.all { !it.ok } shouldBe true
                tickets[0].error!! shouldContain "500"
                server.verify()
            }
        }

        `when`("400 으로 거부되면") {
            then("재시도 없이 요청 1회로 청크 전부 error 티켓이다") {
                val (client, server) = fixture()
                server.expect(ExpectedCount.once(), requestTo(SEND_URL)).andRespond(withBadRequest())

                val tickets = client.send(messages(2))

                tickets.all { !it.ok } shouldBe true
                tickets[0].error!! shouldContain "400"
                server.verify()
            }
        }

        `when`("응답 본문이 JSON 이 아니면") {
            then("재시도 없이 청크 전부 error 티켓이다") {
                val (client, server) = fixture()
                server.expect(ExpectedCount.once(), requestTo(SEND_URL)).andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON))

                client.send(messages(2)).all { !it.ok } shouldBe true
                server.verify()
            }
        }

        `when`("실제 HTTP 로 250건을 보내면") {
            then("요청 3개가 겹치지 않고 하나씩 나가고 티켓 순서는 입력 순서와 같다") {
                LocalExpo(Duration.ofMillis(50)).use { expo ->
                    val tickets = ExpoPushClient.create(expo.baseUrl, "", quickRetry).send(messages(250))

                    tickets shouldHaveSize 250
                    tickets.forEachIndexed { i, t -> t.id shouldBe "t$i" }
                    expo.arrivals shouldHaveSize 3
                    expo.maxActive shouldBe 1
                }
            }
        }

        `when`("메시지 본문을 만들면") {
            then("sound·priority 기본값과 메시지의 channelId·data 를 함께 보낸다") {
                val (client, server) = fixture()
                server.expect(requestTo(SEND_URL))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$[0].to").value("ExponentPushToken[0]"))
                    .andExpect(jsonPath("$[0].title").value("t0"))
                    .andExpect(jsonPath("$[0].sound").value("default"))
                    .andExpect(jsonPath("$[0].priority").value("high"))
                    .andExpect(jsonPath("$[0].channelId").value("news"))
                    .andExpect(jsonPath("$[0].data.type").value("NEWS"))
                    .andRespond(withSuccess(okBody(0, 1), MediaType.APPLICATION_JSON))

                client.send(listOf(PushMessage("ExponentPushToken[0]", "t0", "b", mapOf("type" to "NEWS"), channelId = "news")))
                server.verify()
            }
        }

        `when`("ttlSeconds 가 지정된 메시지를 보내면") {
            then("ttl 을 초 단위 정수로 직렬화한다") {
                val (client, server) = fixture()
                server.expect(requestTo(SEND_URL))
                    .andExpect(jsonPath("$[0].ttl").value(10800))
                    .andRespond(withSuccess(okBody(0, 1), MediaType.APPLICATION_JSON))

                client.send(listOf(PushMessage("ExponentPushToken[0]", "t", "b", mapOf("type" to "SCAN_SUGGESTION"), ttlSeconds = 10800)))
                server.verify()
            }
        }

        `when`("ttlSeconds 가 없는 메시지를 보내면") {
            then("ttl 필드를 생략한다") {
                val (client, server) = fixture()
                server.expect(requestTo(SEND_URL))
                    .andExpect(jsonPath("$[0].ttl").doesNotExist())
                    .andRespond(withSuccess(okBody(0, 1), MediaType.APPLICATION_JSON))

                client.send(messages(1))
                server.verify()
            }
        }

        `when`("access token 이 설정돼 있으면") {
            then("Authorization Bearer 헤더를 붙인다") {
                val (client, server) = fixture(accessToken = "tok")
                server.expect(requestTo(SEND_URL))
                    .andExpect(header("Authorization", "Bearer tok"))
                    .andRespond(withSuccess(okBody(0, 1), MediaType.APPLICATION_JSON))

                client.send(messages(1))
                server.verify()
            }
        }

        `when`("access token 이 비어 있으면") {
            then("Authorization 헤더가 없다") {
                val (client, server) = fixture(accessToken = "")
                server.expect(requestTo(SEND_URL))
                    .andExpect(headerDoesNotExist("Authorization"))
                    .andRespond(withSuccess(okBody(0, 1), MediaType.APPLICATION_JSON))

                client.send(messages(1))
                server.verify()
            }
        }

        `when`("빈 목록을 보내면") {
            then("HTTP 호출 없이 빈 결과를 돌려준다") {
                val (client, server) = fixture()
                server.expect(ExpectedCount.never(), requestTo(SEND_URL))

                client.send(emptyList()) shouldHaveSize 0
                server.verify()
            }
        }
    }
})
