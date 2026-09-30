package com.kbap.api.core

import com.kbap.api.IntegrationTest
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get

@IntegrationTest
class GlobalExceptionHandlerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: javax.sql.DataSource

    private val mapper = jacksonObjectMapper()

    init {
        val handlerLogger =
            LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as ch.qos.logback.classic.Logger
        var appender = ListAppender<ILoggingEvent>()

        beforeEach {
            appender = ListAppender<ILoggingEvent>().apply { start() }
            handlerLogger.addAppender(appender)
        }

        afterEach {
            handlerLogger.detachAppender(appender)
            appender.stop()
        }

        fun ILoggingEvent.value(key: String): Any? = keyValuePairs?.firstOrNull { it.key == key }?.value

        fun MvcResult.body() = mapper.readTree(response.getContentAsString(Charsets.UTF_8))

        given("비즈니스 예외(4xx)를 던지는 요청") {
            `when`("응답이 나가면") {
                then("예외 타입·에러 코드·상태·요청 URI 가 담긴 WARN 로그가 남는다") {
                    val result = mockMvc.get("/api/test-logging/business").andReturn()

                    result.response.status shouldBe 400

                    val event = appender.list.single()
                    event.level shouldBe Level.WARN
                    event.value("exception") shouldBe "BusinessException"
                    event.value("errorCode") shouldBe "MEMBER-003"
                    event.value("status") shouldBe 400
                    event.value("uri") shouldBe "/api/test-logging/business"
                    event.mdcPropertyMap["requestId"] shouldBe result.response.getHeader("X-Request-Id")
                }
            }
        }

        given("필수 쿼리 파라미터를 채우지 않은 요청") {
            `when`("필수 파라미터를 아예 빠뜨리면") {
                then("신규 핸들러 없이 400 COMMON-002 봉투로 응답한다") {
                    val result = mockMvc.get("/api/home").andReturn()

                    result.response.status shouldBe 400
                    result.body().path("success").asBoolean() shouldBe false
                    result.body().path("code").asText() shouldBe "COMMON-002"
                }
            }

            `when`("필수 파라미터를 빈 값으로 보내면") {
                then("누락과 같은 400 COMMON-002 로 응답한다") {
                    val result = mockMvc.get("/api/home?lang=").andReturn()

                    result.response.status shouldBe 400
                    result.body().path("code").asText() shouldBe "COMMON-002"
                }
            }

            `when`("필수 파라미터를 공백 문자열로 보내면") {
                then("누락과 같은 400 COMMON-002 로 응답한다") {
                    val result = mockMvc.get("/api/home") {
                        param("lang", "  ")
                    }.andReturn()

                    result.response.status shouldBe 400
                    result.body().path("code").asText() shouldBe "COMMON-002"
                }
            }
        }

        given("스프링이 상태 코드를 아는 예외") {
            `when`("매핑되지 않은 경로를 호출하면") {
                then("500 이 아니라 404 로, 봉투를 유지한 채 WARN 로그가 남는다") {
                    val result = mockMvc.get("/api/nope").andReturn()

                    result.response.status shouldBe 404
                    result.body().path("success").asBoolean() shouldBe false
                    result.body().path("code").asText() shouldBe "COMMON-002"

                    val event = appender.list.single()
                    event.level shouldBe Level.WARN
                    event.value("status") shouldBe 404
                }
            }

            `when`("지원하지 않는 HTTP 메서드로 호출하면") {
                then("500 이 아니라 405 로 응답한다") {
                    val result = mockMvc.delete("/api/test-logging/ok").andReturn()

                    result.response.status shouldBe 405
                    result.body().path("code").asText() shouldBe "COMMON-002"
                    appender.list.single().level shouldBe Level.WARN
                }
            }
        }

        given("교착으로 희생된 요청") {
            `when`("두 요청이 같은 두 행을 반대 순서로 잠그면") {
                then("한쪽은 200, 희생된 쪽은 409 COMMON-004 이고 WARN 으로 남는다 — 다시 보내면 풀리는 오류를 500·ERROR 로 올리지 않는다") {
                    val foodIds = dataSource.connection.use { c ->
                        listOf("교착음식A", "교착음식B").map { name ->
                            c.prepareStatement(
                                "INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations, " +
                                    "ingredients, content_status, status, created_at, updated_at) " +
                                    "VALUES (?, '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6)) " +
                                    "ON DUPLICATE KEY UPDATE content_status = 'READY'",
                            ).use { ps -> ps.setString(1, name); ps.executeUpdate() }
                            c.prepareStatement("SELECT id FROM food WHERE korean_name = ?").use { ps ->
                                ps.setString(1, name)
                                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                            }
                        }
                    }
                    val (a, b) = foodIds
                    val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
                    val responses = listOf(a to b, b to a).map { (first, second) ->
                        executor.submit<MvcResult> {
                            mockMvc.get("/api/test-logging/lock-both?first=$first&second=$second").andReturn()
                        }
                    }
                    executor.shutdown()
                    val results = responses.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }

                    results.map { it.response.status }.sorted() shouldBe listOf(200, 409)
                    val victim = results.single { it.response.status == 409 }
                    victim.body().path("code").asText() shouldBe "COMMON-004"
                    val event = appender.list.single { it.value("status") == 409 }
                    event.level shouldBe Level.WARN
                    event.value("errorCode") shouldBe "COMMON-004"
                }
            }
        }

        given("미처리 예외를 던지는 요청") {
            `when`("응답이 나가면") {
                then("공통 응답 봉투(COMMON-003, 500)로 응답한다") {
                    val result = mockMvc.get("/api/test-logging/unhandled").andReturn()

                    result.response.status shouldBe 500
                    result.body().path("success").asBoolean() shouldBe false
                    result.body().path("code").asText() shouldBe "COMMON-003"
                }
            }

            then("스택트레이스를 포함한 ERROR 로그가 남는다") {
                mockMvc.get("/api/test-logging/unhandled").andReturn()

                val event = appender.list.single()
                event.level shouldBe Level.ERROR
                event.throwableProxy shouldNotBe null
                event.value("exception") shouldBe "IllegalStateException"
                event.value("errorCode") shouldBe "COMMON-003"
                event.value("status") shouldBe 500
                event.value("uri") shouldBe "/api/test-logging/unhandled"
            }
        }
    }
}
