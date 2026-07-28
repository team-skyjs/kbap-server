package com.kbap.api.common.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.encoder.Encoder
import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest(properties = ["logging.structured.format.console=ecs"])
@Import(MySqlContainerConfig::class)
class StructuredConsoleLoggingTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    init {
        given("staging·prod 의 JSON 구조화 로그 설정") {
            `when`("콘솔 어펜더의 인코더를 확인하면") {
                then("상관 키·회원 식별자가 JSON 필드로 나간다") {
                    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
                    val console = root.getAppender("CONSOLE") as ConsoleAppender<*>

                    @Suppress("UNCHECKED_CAST")
                    val encoder = console.encoder as Encoder<Any>

                    MDC.put(RequestLoggingFilter.REQUEST_ID_KEY, "test-request-id")
                    MDC.put(RequestLoggingFilter.MEMBER_ID_KEY, "42")
                    val event = LoggingEvent(Logger::class.java.name, root, Level.INFO, "hello", null, null)
                    val json = String(encoder.encode(event), Charsets.UTF_8)
                    MDC.clear()

                    json shouldContain "\"requestId\":\"test-request-id\""
                    json shouldContain "\"memberId\":\"42\""
                    json.startsWith("{") shouldBe true
                }
            }
        }
    }
}
