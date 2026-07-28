package com.kbap.app.api.common.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kbap.common.application.auth.token.TokenIssuer
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import java.net.URI

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class RequestLoggingFilterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    init {
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        var appender = ListAppender<ILoggingEvent>()

        beforeEach {
            appender = ListAppender<ILoggingEvent>().apply { start() }
            rootLogger.addAppender(appender)
        }

        afterEach {
            rootLogger.detachAppender(appender)
            appender.stop()
        }

        fun eventsOf(loggerSuffix: String): List<ILoggingEvent> =
            appender.list.filter { it.loggerName.endsWith(loggerSuffix) }

        fun ILoggingEvent.value(key: String): Any? = keyValuePairs?.firstOrNull { it.key == key }?.value

        fun MvcResult.requestId(): String? = response.getHeader("X-Request-Id")

        fun callOk(): MvcResult = mockMvc.get("/api/v1/test-logging/ok").andReturn()

        fun callBusinessError(): MvcResult = mockMvc.get("/api/v1/test-logging/business").andReturn()

        fun callWithToken(memberId: Long): MvcResult =
            mockMvc.get("/api/v1/members/me/profile") {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(memberId, MemberRole.USER)}")
            }.andReturn()

        given("비즈니스 API 요청") {
            `when`("정상 처리되면") {
                then("응답에 상관 키 헤더가 실린다") {
                    callOk().requestId() shouldMatch
                        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
                }
            }

            `when`("인증 없이 인증 필요 API 를 호출해 401 로 거절되면") {
                then("거절 응답에도 상관 키 헤더가 실린다") {
                    val result = mockMvc.get("/api/v1/members/me/profile").andReturn()

                    result.response.status shouldBe 401
                    result.requestId() shouldNotBe null
                }
            }

            `when`("처리 중 예외가 발생하면") {
                then("에러 로그에 응답 헤더와 같은 상관 키가 담긴다") {
                    val result = callBusinessError()

                    val requestId = result.requestId()
                    requestId shouldNotBe null
                    eventsOf("GlobalExceptionHandler").single().mdcPropertyMap["requestId"] shouldBe requestId
                }
            }

            `when`("같은 API 를 두 번 호출하면") {
                then("요청마다 서로 다른 상관 키가 부여된다") {
                    val first = callOk().requestId()
                    val second = callOk().requestId()

                    first shouldNotBe second
                }
            }

            `when`("요청 처리가 끝나면") {
                then("상관 컨텍스트가 정리되어 다음 요청 로그를 오염시키지 않는다") {
                    callOk().requestId() shouldNotBe null

                    MDC.get("requestId") shouldBe null
                }
            }
        }

        given("프레임워크 경로 요청") {
            `when`("actuator 를 호출하면") {
                then("상관 키를 부여하지 않는다") {
                    mockMvc.get("/actuator/health").andReturn().requestId() shouldBe null
                }
            }

            `when`("actuator 를 호출하면 (흐름 로그)") {
                then("진입·응답 로그를 남기지 않는다") {
                    mockMvc.get("/actuator/health").andReturn()

                    eventsOf("RequestLoggingFilter") shouldHaveSize 0
                }
            }
        }

        given("요청 흐름 요약 로그") {
            `when`("요청이 정상 처리되면") {
                then("같은 상관 키로 진입·응답 로그가 한 쌍 남는다") {
                    val result = mockMvc.get("/api/v1/test-logging/ok?keyword=kimchi").andReturn()

                    val events = eventsOf("RequestLoggingFilter")
                    events shouldHaveSize 2

                    val (entry, exit) = events
                    entry.formattedMessage shouldContain "GET"
                    entry.formattedMessage shouldContain "/api/v1/test-logging/ok?keyword=kimchi"
                    exit.formattedMessage shouldContain "200"

                    entry.mdcPropertyMap["requestId"] shouldBe result.requestId()
                    exit.mdcPropertyMap["requestId"] shouldBe result.requestId()
                }
            }

            `when`("한글 검색어처럼 퍼센트 인코딩된 쿼리로 요청하면") {
                then("진입 로그에는 디코딩된 원문으로 남는다") {
                    mockMvc.get(URI.create("/api/v1/test-logging/ok?keyword=%EA%B9%80%EC%B9%98")).andReturn()

                    eventsOf("RequestLoggingFilter").first().formattedMessage shouldContain "keyword=김치"
                }
            }

            `when`("응답 로그가 남으면") {
                then("응답 상태와 소요 시간이 필드로 실린다") {
                    callOk()

                    val exit = eventsOf("RequestLoggingFilter").last()
                    exit.value("status") shouldBe 200
                    (exit.value("elapsedMs") as Long) shouldBeGreaterThanOrEqual 0L
                }
            }

            `when`("처리 중 예외로 에러 응답이 나가면") {
                then("응답 로그가 누락 없이 남는다") {
                    callBusinessError()

                    val exit = eventsOf("RequestLoggingFilter").last()
                    exit.value("status") shouldBe 400
                }
            }
        }

        given("인증된 회원의 요청") {
            `when`("요청이 처리되면") {
                then("로그에 회원 식별자가 담긴다") {
                    callWithToken(9_999_999L)

                    eventsOf("GlobalExceptionHandler").single().mdcPropertyMap["memberId"] shouldBe "9999999"
                }
            }

            `when`("이어서 비인증 요청이 같은 스레드로 처리되면") {
                then("앞선 회원 식별자가 남지 않는다") {
                    callWithToken(9_999_999L)
                    appender.list.clear()

                    callBusinessError()

                    val errorEvent = eventsOf("GlobalExceptionHandler").single()
                    errorEvent.mdcPropertyMap["requestId"] shouldNotBe null
                    errorEvent.mdcPropertyMap["memberId"] shouldBe null
                }
            }
        }

        given("비인증 요청") {
            `when`("처리 중 예외가 발생하면") {
                then("회원 식별자 없이 상관 키만으로 로그가 남는다") {
                    val result = callBusinessError()

                    val requestId = result.requestId()
                    requestId shouldNotBe null

                    val errorEvent = eventsOf("GlobalExceptionHandler").single()
                    errorEvent.mdcPropertyMap["requestId"] shouldBe requestId
                    errorEvent.mdcPropertyMap["memberId"] shouldBe null
                    result.response.status shouldBe 400
                }
            }
        }
    }
}
